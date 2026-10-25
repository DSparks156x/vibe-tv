package com.vibe.tvlauncher

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vibe.tvlauncher.data.AppEntry
import com.vibe.tvlauncher.data.AppRepo
import com.vibe.tvlauncher.data.Cinemeta
import com.vibe.tvlauncher.data.Http
import com.vibe.tvlauncher.data.ContinueItem
import com.vibe.tvlauncher.data.Meta
import com.vibe.tvlauncher.data.Nuvio
import com.vibe.tvlauncher.data.NuvioProfile
import com.vibe.tvlauncher.data.Weather
import com.vibe.tvlauncher.data.WeatherApi
import com.vibe.tvlauncher.settings.LauncherPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class HomeState(
    val hero: List<Meta> = emptyList(),
    val continueWatching: List<ContinueItem> = emptyList(),
    val library: List<Meta> = emptyList(),
    val topPicks: List<Meta> = emptyList(),
    val movies: List<Meta> = emptyList(),
    val shows: List<Meta> = emptyList(),
    val topRated: List<Meta> = emptyList(),
    val trailers: List<Meta> = emptyList(),
    val apps: List<AppEntry> = emptyList(),
    /** True once the Vibe Nuvio fork is installed and readable. */
    val nuvio: Boolean = false,
    val profile: NuvioProfile? = null,
    val profiles: List<NuvioProfile> = emptyList(),
    /** Recently aired episodes of shows you follow that you haven't reached yet. */
    val newEpisodes: List<ContinueItem> = emptyList(),
    val homeEvent: Int = 0,
    /** "movie"/"series" -> rows of (title, items) for the Movies and Shows tabs. */
    val browse: Map<String, List<Pair<String, List<Meta>>>> = emptyMap(),
    val weather: Weather? = null,
    /** "Because you watch <genre>" rows built from the Nuvio watch history. */
    val tasteRows: List<Pair<String, List<Meta>>> = emptyList(),
    /** Rows from the user's own Nuvio add-on catalogs (non-Cinemeta). */
    val addonRows: List<Pair<String, List<Meta>>> = emptyList(),
    /** File path or URL of the profile picture, if one is set. */
    val avatar: String? = null,
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private val cacheDir = File(app.filesDir, "cache").apply { mkdirs() }

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state

    private var lastCatalogLoad = 0L
    private var lastWeatherLoad = 0L
    private var lastSettings: List<Any?>? = null

    // Nuvio notifies this URI when watch progress / library change, so rows update live.
    private val nuvioObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            nuvioReload?.cancel()
            nuvioReload = viewModelScope.launch {
                kotlinx.coroutines.delay(500)
                loadNuvio()
            }
        }
    }
    private var nuvioReload: kotlinx.coroutines.Job? = null

    override fun onCleared() {
        getApplication<Application>().contentResolver.unregisterContentObserver(nuvioObserver)
    }

    init {
        runCatching {
            app.contentResolver.registerContentObserver(Nuvio.changesUri, true, nuvioObserver)
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { loadFromCache() }
            refresh(force = true)
        }
    }

    fun homePressed() = _state.update { it.copy(homeEvent = it.homeEvent + 1) }

    fun reloadApps() {
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) { AppRepo.load(getApplication()) }
            _state.update { it.copy(apps = apps) }
        }
    }

    /** Called on every resume; only hits the network when data is stale. */
    fun refresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        // Local provider query, cheap enough to run on every resume.
        viewModelScope.launch { loadNuvio() }
        // Settings may have changed while we were in the background.
        val app = getApplication<Application>()
        val settings = listOf(LauncherPrefs.personalize(app), LauncherPrefs.tasteWeight(app), LauncherPrefs.units(app), LauncherPrefs.location(app))
        val settingsChanged = settings != lastSettings
        if (settingsChanged && lastSettings != null) recompute()
        if (settingsChanged) lastWeatherLoad = 0
        lastSettings = settings
        if (force || now - lastWeatherLoad > 20 * 60_000) {
            lastWeatherLoad = now
            viewModelScope.launch { loadWeather() }
        }
        _state.update { it.copy(avatar = findAvatar()) }
        if (force || now - lastCatalogLoad > 30 * 60_000) {
            lastCatalogLoad = now
            viewModelScope.launch { loadCatalogs() }
        }
    }

    /** Continue watching, library and profile straight from the Nuvio fork. */
    private suspend fun loadNuvio() {
        val app = getApplication<Application>()
        val data = withContext(Dispatchers.IO) {
            if (!Nuvio.isInstalled(app)) return@withContext null
            Triple(Nuvio.continueWatching(app), Nuvio.library(app), Nuvio.profile(app)) to Nuvio.profiles(app)
        }
        _state.update {
            if (data == null) {
                it.copy(nuvio = false, continueWatching = emptyList(), library = emptyList(), profile = null)
            } else {
                val now = System.currentTimeMillis()
                val cw = data.first.first
                // Forget hides once Nuvio stops listing the item (or after 6h, e.g. rewatching).
                hiddenCw.entries.removeIf { (id, t) -> now - t > 6 * 3_600_000L || cw.none { c -> c.meta.id == id } }
                it.copy(
                    nuvio = true,
                    continueWatching = cw.filter { c -> !hiddenCw.containsKey(c.meta.id) }.take(30),
                    library = data.first.second.take(60),
                    profile = data.first.third,
                    profiles = data.second,
                )
            }
        }
        if (data != null) {
            loadCwGenres()
            recompute()
            loadAddonRows()
            loadNewEpisodes()
        }
    }

    // ---- Long-press actions (written through the Nuvio fork; it notifies us, we reload) ----

    suspend fun libraryContains(meta: Meta): Boolean? =
        withContext(Dispatchers.IO) { Nuvio.libraryContains(getApplication(), meta) }

    private fun nuvioAction(failMsg: String, block: (Application) -> Boolean) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { block(getApplication()) }
            if (!ok) android.widget.Toast.makeText(getApplication(), failMsg, android.widget.Toast.LENGTH_SHORT).show()
            loadNuvio()
        }
    }

    fun toggleLibrary(meta: Meta) = nuvioAction("Couldn't update the Nuvio library") { Nuvio.toggleLibrary(it, meta) != null }
    fun markWatched(item: ContinueItem) = hideAfter(item, "Couldn't mark as watched") { Nuvio.markWatched(it, item) }
    fun removeProgress(item: ContinueItem) = hideAfter(item, "Couldn't remove it") { Nuvio.removeProgress(it, item) }

    /** contentId -> time hidden. Keeps a confirmed removal off the row even if Nuvio's list is a moment stale. */
    private val hiddenCw = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun hideAfter(item: ContinueItem, failMsg: String, block: (Application) -> Boolean) {
        val id = item.meta.id
        // Optimistic: drop it now, put it back if Nuvio says no.
        hiddenCw[id] = System.currentTimeMillis()
        _state.update { s -> s.copy(continueWatching = s.continueWatching.filter { it.meta.id != id }) }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { block(getApplication()) }
            if (!ok) {
                hiddenCw.remove(id)
                android.widget.Toast.makeText(getApplication(), failMsg, android.widget.Toast.LENGTH_SHORT).show()
            }
            loadNuvio()
        }
    }
    fun switchProfile(id: String) = nuvioAction("Couldn't switch profile (PIN locked?)") { Nuvio.setProfile(it, id) }

    // ---- Your apps editing ----

    private fun favoriteKeys(): List<String> = _state.value.apps.filter { it.favorite }.map { it.key }

    fun moveFavorite(key: String, delta: Int) {
        val favs = favoriteKeys().toMutableList()
        val i = favs.indexOf(key)
        val j = i + delta
        if (i < 0 || j !in favs.indices) return
        favs[i] = favs[j].also { favs[j] = favs[i] }
        AppRepo.saveFavorites(getApplication(), favs)
        reloadApps()
    }

    fun setAppHidden(key: String, hidden: Boolean) {
        AppRepo.setHidden(getApplication(), key, hidden)
        if (hidden) AppRepo.saveFavorites(getApplication(), favoriteKeys().filter { it != key })
        reloadApps()
    }

    fun setFavorite(key: String, favorite: Boolean) {
        val favs = favoriteKeys().filter { it != key }.let { if (favorite) it + key else it }
        AppRepo.saveFavorites(getApplication(), favs)
        reloadApps()
    }

    // ---- New episodes: shows from your library / Continue watching with an episode that aired recently ----

    private val episodeCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Cinemeta.Episode>>>()

    private suspend fun loadNewEpisodes() {
        val s = _state.value
        val now = System.currentTimeMillis()
        val window = 21L * 24 * 3_600_000 // three weeks
        // Furthest episode reached per show, from Continue watching.
        val reached = s.continueWatching.filter { it.meta.type == "series" && it.season != null && it.episode != null }
            .associate { it.meta.id to (it.season!! to it.episode!!) }
        // Next-up items already sit in Continue watching; don't repeat them.
        val inCw = s.continueWatching.mapNotNull { c -> c.videoId }.toSet()
        val shows = (s.continueWatching.map { it.meta } + s.library)
            .filter { it.type == "series" && it.isImdb }.distinctBy { it.id }.take(25)
        val items = withContext(Dispatchers.IO) {
            shows.map { show ->
                async {
                    val cached = episodeCache[show.id]
                    val eps = if (cached != null && now - cached.first < 6 * 3_600_000L) cached.second
                    else Cinemeta.episodes(show.id).also { episodeCache[show.id] = now to it }
                    val last = reached[show.id]
                    eps.filter { it.released in (now - window)..now }
                        .filter { e -> last == null || e.season > last.first || (e.season == last.first && e.episode > last.second) }
                        .filter { it.id !in inCw }
                        .maxByOrNull { it.released }
                        ?.let { e ->
                            ContinueItem(
                                meta = show,
                                videoId = e.id,
                                progress = 0f,
                                season = e.season,
                                episode = e.episode,
                                lastWatched = e.released,
                                episodeTitle = e.title,
                                nextUp = true,
                                thumbnail = e.thumbnail,
                            )
                        }
                }
            }.mapNotNull { it.await() }.sortedByDescending { it.lastWatched }
        }
        _state.update { it.copy(newEpisodes = items) }
    }

    // ---- Personalization: genre taste from Nuvio history, seen titles hidden from picks ----

    private var rawMovies: List<Meta> = emptyList()
    private var rawShows: List<Meta> = emptyList()
    private var rawRated: List<Meta> = emptyList()
    private val genreCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    private var tasteKey: List<String> = emptyList()

    /** Continue watching items don't carry genres; look them up once on Cinemeta. */
    private suspend fun loadCwGenres() = withContext(Dispatchers.IO) {
        val missing = _state.value.continueWatching
            .filter { it.meta.isImdb && !genreCache.containsKey(it.meta.id) }
            .take(15)
        missing.map { c ->
            async { genreCache[c.meta.id] = Cinemeta.meta(c.meta.type, c.meta.id)?.genres ?: emptyList() }
        }.forEach { it.await() }
    }

    /** Genre -> weight. Things you're watching count double vs things you only saved. */
    private fun tasteWeights(s: HomeState): Map<String, Double> {
        val w = HashMap<String, Double>()
        s.continueWatching.forEach { c -> genreCache[c.meta.id].orEmpty().forEach { g -> w[g] = (w[g] ?: 0.0) + 2.0 } }
        s.library.forEach { m -> m.genres.forEach { g -> w[g] = (w[g] ?: 0.0) + 1.0 } }
        return w
    }

    private fun seenIds(s: HomeState): Set<String> =
        s.continueWatching.map { it.meta.id }.toSet() + s.library.map { it.id }

    private fun recompute() {
        val s = _state.value
        val app = getApplication<Application>()
        val personalize = LauncherPrefs.personalize(app)
        val taste = LauncherPrefs.tasteWeight(app)
        val seen = if (personalize) seenIds(s) else emptySet()
        val weights = if (personalize) tasteWeights(s) else emptyMap()
        val total = weights.values.sum()
        // Alternate movies and shows so the hero and top picks feel mixed, like Google TV.
        val mixed = buildList {
            val n = maxOf(rawMovies.size, rawShows.size)
            for (i in 0 until n) {
                rawMovies.getOrNull(i)?.let(::add)
                rawShows.getOrNull(i)?.let(::add)
            }
        }.filter { it.isImdb && it.id !in seen }
        val hero = mixed.take(8)
        val rest = mixed.drop(8)
        // Blend taste match with popularity so picks stay fresh but lean toward your genres.
        val picks = if (total <= 0) rest else rest.withIndex().sortedByDescending { (i, m) ->
            val affinity = m.genres.sumOf { weights[it] ?: 0.0 } / total
            affinity * taste + (1.0 - i.toDouble() / rest.size) * (1.0 - taste)
        }.map { it.value }
        _state.update {
            it.copy(
                hero = hero,
                topPicks = picks.take(20),
                movies = rawMovies,
                shows = rawShows,
                topRated = rawRated,
                trailers = mixed.filter { m -> m.trailerYt != null }.take(20),
            )
        }
        val top = weights.entries.sortedByDescending { it.value }.take(2).map { it.key }
        if (top != tasteKey) {
            tasteKey = top
            viewModelScope.launch { loadTasteRows(top) }
        }
    }

    private suspend fun loadTasteRows(genres: List<String>) {
        val rows = withContext(Dispatchers.IO) {
            genres.map { g ->
                async {
                    val enc = android.net.Uri.encode(g)
                    val movies = runCatching { Cinemeta.parseCatalog(Cinemeta.catalogRaw("movie", "top/genre=$enc")) }
                        .getOrDefault(emptyList())
                    val shows = runCatching { Cinemeta.parseCatalog(Cinemeta.catalogRaw("series", "top/genre=$enc")) }
                        .getOrDefault(emptyList())
                    val mixed = buildList {
                        for (i in 0 until maxOf(movies.size, shows.size)) {
                            movies.getOrNull(i)?.let(::add)
                            shows.getOrNull(i)?.let(::add)
                        }
                    }
                    "Because you watch $g" to mixed
                }
            }.map { it.await() }
        }
        val seen = seenIds(_state.value)
        _state.update { st ->
            st.copy(tasteRows = rows.map { (t, l) -> t to l.filter { it.id !in seen }.take(25) }.filter { it.second.isNotEmpty() })
        }
    }

    private var lastAddonLoad = 0L

    /** Home rows from the user's own add-ons (Cinemeta is already covered). */
    private suspend fun loadAddonRows() {
        val now = System.currentTimeMillis()
        if (now - lastAddonLoad < 30 * 60_000) return
        lastAddonLoad = now
        val app = getApplication<Application>()
        val rows = withContext(Dispatchers.IO) {
            val catalogs = Nuvio.addonCatalogs(app).filter { !it.isCinemeta && (it.type == "movie" || it.type == "series") }
            val chosen = catalogs.filter { it.showInHome }.ifEmpty { catalogs }.take(8)
            chosen.map { c ->
                async {
                    val metas = runCatching { Cinemeta.parseCatalog(Http.get(c.url)) }.getOrDefault(emptyList())
                    "${c.name} · ${c.addon}" to metas.take(30)
                }
            }.map { it.await() }.filter { it.second.isNotEmpty() }
        }
        _state.update { it.copy(addonRows = rows) }
    }

    private val catalogs = listOf(
        "movie_top" to ("movie" to "top"),
        "series_top" to ("series" to "top"),
        "movie_rated" to ("movie" to "imdbRating"),
    )

    private suspend fun loadCatalogs() = withContext(Dispatchers.IO) {
        val jobs = catalogs.map { (key, tc) ->
            async {
                runCatching { Cinemeta.catalogRaw(tc.first, tc.second) }
                    .onSuccess { File(cacheDir, "$key.json").writeText(it) }
                    .onFailure { Log.w(TAG, "catalog $key failed", it) }
                    .getOrNull()
            }
        }
        val raws = jobs.map { it.await() }
        // Offline (e.g. TV waking from standby): retry on the next resume instead of in 30 minutes.
        if (raws.all { it == null }) lastCatalogLoad = 0
        if (raws.any { it != null }) {
            applyCatalogs(raws.map { it?.let(Cinemeta::parseCatalog) ?: emptyList() })
        }
    }

    private fun loadFromCache() {
        val cached = catalogs.map { (key, _) ->
            File(cacheDir, "$key.json").takeIf { it.exists() }
                ?.let { runCatching { Cinemeta.parseCatalog(it.readText()) }.getOrNull() } ?: emptyList()
        }
        if (cached.any { it.isNotEmpty() }) applyCatalogs(cached)
    }

    private fun applyCatalogs(lists: List<List<Meta>>) {
        val (movies, shows, rated) = lists
        rawMovies = movies
        rawShows = shows
        rawRated = rated
        recompute()
    }

    /**
     * Profile picture: push one with
     * `adb push me.jpg /sdcard/Android/data/com.vibe.tvlauncher/files/avatar.jpg`,
     * or pass `--es avatarUrl <url>` to the activity.
     */
    private fun findAvatar(): String? {
        val dir = getApplication<Application>().getExternalFilesDir(null)
        val file = listOf("avatar.jpg", "avatar.png", "avatar.webp")
            .map { File(dir, it) }.firstOrNull { it.exists() }
        return file?.absolutePath ?: prefs.getString("avatarUrl", null)
    }

    fun setAvatarUrl(url: String) {
        prefs.edit().putString("avatarUrl", url).apply()
        _state.update { it.copy(avatar = findAvatar()) }
    }

    /** `--es location "lat,lon"` pins the weather location instead of guessing from the IP. */
    fun setLocation(value: String) {
        prefs.edit().putString("location", value).apply()
        viewModelScope.launch { loadWeather() }
    }

    private suspend fun loadWeather() {
        val w = withContext(Dispatchers.IO) {
            runCatching {
                val app = getApplication<Application>()
                val pinned = LauncherPrefs.location(app)
                val guessF = java.util.Locale.getDefault().country == "US"
                val found = (if (pinned != null) WeatherApi.place(pinned, guessF) else null) ?: WeatherApi.locate() ?: return@runCatching null
                val place = when (LauncherPrefs.units(app)) {
                    "f" -> found.copy(fahrenheit = true)
                    "c" -> found.copy(fahrenheit = false)
                    else -> found
                }
                WeatherApi.current(place)
            }.onFailure { Log.w(TAG, "weather failed", it) }.getOrNull()
        }
        if (w != null) _state.update { it.copy(weather = w) } else lastWeatherLoad = 0
    }

    private val browseLoading = mutableSetOf<String>()

    /** Loads the genre rows behind the Movies / Shows tabs, once per session. */
    fun loadBrowse(type: String) {
        if (_state.value.browse[type]?.isNotEmpty() == true || !browseLoading.add(type)) return
        viewModelScope.launch {
            val genres = if (type == "movie") MOVIE_GENRES else SHOW_GENRES
            val year = java.time.Year.now().value
            val specs = buildList {
                add("Popular ${if (type == "movie") "movies" else "shows"}" to "top")
                add("New in $year" to "year/genre=$year")
                add("Top rated" to "imdbRating")
                genres.forEach { add(it to "top/genre=${android.net.Uri.encode(it)}") }
            }
            val rows = withContext(Dispatchers.IO) {
                specs.map { (title, path) ->
                    async {
                        val file = File(cacheDir, "browse_${type}_${path.hashCode()}.json")
                        val raw = runCatching { Cinemeta.catalogRaw(type, path).also { file.writeText(it) } }
                            .getOrElse { file.takeIf { f -> f.exists() }?.readText() }
                        title to (raw?.let { runCatching { Cinemeta.parseCatalog(it) }.getOrNull() } ?: emptyList())
                    }
                }.map { it.await() }.filter { it.second.isNotEmpty() }
            }
            browseLoading.remove(type)
            _state.update { it.copy(browse = it.browse + (type to rows)) }
        }
    }

    companion object {
        private val MOVIE_GENRES = listOf("Action", "Comedy", "Sci-Fi", "Thriller", "Animation", "Drama", "Horror", "Documentary")
        private val SHOW_GENRES = listOf("Drama", "Comedy", "Crime", "Sci-Fi", "Animation", "Reality-TV", "Documentary", "Mystery")
        private const val TAG = "VibeTV"
    }
}
