package com.vibe.tvlauncher.data.voice

import android.content.Context
import android.net.Uri
import android.util.Log
import com.vibe.tvlauncher.data.AppEntry
import com.vibe.tvlauncher.data.AppRepo
import com.vibe.tvlauncher.data.Cinemeta
import com.vibe.tvlauncher.data.ContinueItem
import com.vibe.tvlauncher.data.Http
import com.vibe.tvlauncher.data.Meta
import com.vibe.tvlauncher.data.Nuvio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The single thing a request most likely means; run automatically for play/open requests. */
sealed interface TopHit {
    data class Title(val meta: Meta, val season: Int? = null, val episode: Int? = null, val play: Boolean) : TopHit
    data class Resume(val item: ContinueItem) : TopHit
    data class App(val app: AppEntry) : TopHit
    data class YouTube(val query: String) : TopHit
}

data class VoiceResults(
    val transcript: String,
    val intent: VoiceIntent,
    val reply: String,
    val top: TopHit?,
    /** Start [top] after a short countdown (direct commands) instead of just showing it. */
    val autoRun: Boolean,
    val titles: List<Meta>,
    val continueMatches: List<ContinueItem>,
    val youtube: List<String>,
    val apps: List<AppEntry>,
    /** Set when the language model was wanted but unavailable (no key, offline, bad key). */
    val notice: String? = null,
)

/** Snapshot of everything on this TV the assistant can point at. */
class Snapshot(
    val continueWatching: List<ContinueItem>,
    val library: List<Meta>,
    val apps: List<AppEntry>,
    val searchCatalogs: List<SearchCatalog>,
) {
    fun toAssistantContext(): AssistantContext {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val fmtTime = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
        fun ago(ms: Long): String {
            if (ms <= 0) return "unknown"
            val t = Instant.ofEpochMilli(ms).atZone(zone)
            val days = today.toEpochDay() - t.toLocalDate().toEpochDay()
            val day = when (days) {
                0L -> "today"
                1L -> "yesterday"
                in 2..6 -> "${t.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }} ($days days ago)"
                else -> t.toLocalDate().toString()
            }
            return "$day ${t.format(fmtTime)}"
        }
        val cw = continueWatching.mapIndexed { i, c ->
            val m = c.meta
            val ep = if (c.season != null && c.episode != null) ", S${c.season}E${c.episode}" + (c.episodeTitle?.let { " \"$it\"" } ?: "") else ""
            val state = if (c.nextUp) "next episode up" else "${(c.progress.coerceIn(0f, 1f) * 100).toInt()}% watched"
            "c${i + 1}: ${m.name} (${m.type}$ep, $state, last watched ${ago(c.lastWatched)})"
        }
        val lib = library.take(150).mapIndexed { i, m ->
            "l${i + 1}: ${m.name} (${m.type}${m.year?.let { ", $it" } ?: ""})"
        }
        val now = java.time.ZonedDateTime.now(zone)
        return AssistantContext(
            now = now.format(DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd HH:mm", Locale.US)),
            continueWatching = cw,
            library = lib,
            apps = apps.map { it.label },
        )
    }
}

/** A user add-on catalog that only works with a search query (Stremio `extraRequired: ["search"]`). */
data class SearchCatalog(val baseUrl: String, val type: String, val id: String) {
    fun url(q: String) = baseUrl.removeSuffix("/manifest.json").trimEnd('/') +
        "/catalog/$type/${Uri.encode(id)}/search=${Uri.encode(q)}.json"
}

/** Turns an interpreted request into real catalog titles, Continue watching items and apps. */
object Resolver {
    private const val TAG = "VibeVoice"
    private const val LOOKUP_BUDGET_MS = 1800L
    private val lookupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun snapshot(context: Context): Snapshot = Snapshot(
        continueWatching = Nuvio.continueWatching(context).sortedByDescending { it.lastWatched },
        library = Nuvio.library(context),
        apps = apps(context),
        searchCatalogs = searchCatalogs(context),
    )

    // Loading app icons is slow on TV CPUs; installs are rare, so reuse the list for a while.
    @Volatile private var appsCache: Pair<Long, List<AppEntry>>? = null

    private fun apps(context: Context): List<AppEntry> {
        appsCache?.let { (at, list) -> if (System.currentTimeMillis() - at < 10 * 60_000) return list }
        return runCatching { AppRepo.load(context) }.getOrDefault(emptyList())
            .also { appsCache = System.currentTimeMillis() to it }
    }

    private fun searchCatalogs(context: Context): List<SearchCatalog> = try {
        context.contentResolver.query(Uri.parse("content://${Nuvio.PACKAGE}.launcher/addons"), null, null, null, null)?.use { c ->
            val out = mutableListOf<SearchCatalog>()
            while (c.moveToNext()) {
                val base = c.getString(c.getColumnIndexOrThrow("base_url")) ?: continue
                if ("cinemeta" in base) continue // queried directly below
                val arr = runCatching { org.json.JSONArray(c.getString(c.getColumnIndexOrThrow("catalogs")) ?: "[]") }.getOrNull() ?: continue
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val req = o.optJSONArray("extraRequired") ?: continue
                    val names = (0 until req.length()).map { req.optString(it) }
                    if (names == listOf("search") && o.optString("type") in setOf("movie", "series")) {
                        out += SearchCatalog(base, o.optString("type"), o.optString("id"))
                    }
                }
            }
            out
        } ?: emptyList()
    } catch (e: Exception) {
        Log.w(TAG, "addon catalogs unavailable: ${e.message}")
        emptyList()
    }

    suspend fun resolve(transcript: String, intent: VoiceIntent, snap: Snapshot, notice: String?): VoiceResults = coroutineScope {
        val q = intent.query.trim()
        val refItems = intent.refs.mapNotNull { ref ->
            val n = ref.drop(1).toIntOrNull()?.minus(1) ?: return@mapNotNull null
            when (ref.firstOrNull()) {
                'c' -> snap.continueWatching.getOrNull(n)
                'l' -> snap.library.getOrNull(n)?.let { m -> snap.continueWatching.firstOrNull { it.meta.id == m.id } ?: libraryAsItem(m) }
                else -> null
            }
        }
        val refMetas = intent.refs.mapNotNull { ref ->
            if (ref.firstOrNull() == 'l') snap.library.getOrNull((ref.drop(1).toIntOrNull() ?: 0) - 1) else null
        }

        // Continue watching items whose name matches what was said.
        val cwMatches = (refItems.filter { it.progress >= 0f && snap.continueWatching.contains(it) } +
            if (q.isNotEmpty()) snap.continueWatching.filter { score(q, it.meta.name) >= 0.8 } else emptyList()).distinctBy { it.meta.id }

        val apps = if (q.isNotEmpty() || intent.app != null) matchApps(intent.app ?: q, snap.apps) else emptyList()

        // Ground titles in real catalogs. The model's suggestions and the raw query are all looked up.
        val guesses = intent.titles.ifEmpty {
            if (q.isNotEmpty() && intent.action in setOf(Action.PLAY, Action.SHOW_RESULTS)) listOf(TitleGuess(q)) else emptyList()
        }.take(10)
        // Lookups run outside this scope so a slow catalog can't hold the answer up: whatever
        // resolves within the budget is shown, stragglers are dropped.
        val lookups = guesses.map { g -> lookupScope.async { lookup(g, snap.searchCatalogs) } }
        val deadline = System.currentTimeMillis() + LOOKUP_BUDGET_MS
        val resolved = lookups.map { d ->
            withTimeoutOrNull((deadline - System.currentTimeMillis()).coerceAtLeast(1)) { d.await() } ?: emptyList()
        }
        // Best match for each guess first (in the model's order), then the rest of the search hits.
        val firsts = resolved.mapNotNull { it.firstOrNull() }
        // Other search hits only if they share a meaningful word with what was asked ("dune" -> Dune 1984,
        // but not "Rebel Moon - Part Two").
        val extra = if (guesses.size <= 2) {
            val words = guesses.flatMap { meaningful(it.name) }.toSet()
            resolved.flatMap { it.drop(1) }.filter { m -> meaningful(m.name).any { it in words } }
        } else emptyList()
        val titles = (refMetas + firsts + extra).distinctBy { it.id }.take(20)

        val youtube = buildList {
            if (intent.action == Action.YOUTUBE && q.isNotEmpty()) add(q)
            else if (q.isNotEmpty()) {
                add(q)
                firsts.firstOrNull()?.let { add("${it.name} trailer") }
            }
        }.distinct()

        val season = intent.season
        val episode = intent.episode
        var autoRun = false
        val top: TopHit? = when (intent.action) {
            Action.YOUTUBE -> { autoRun = q.isNotEmpty(); if (q.isNotEmpty()) TopHit.YouTube(q) else null }
            Action.OPEN_APP -> {
                val best = apps.firstOrNull()?.takeIf { appScore(intent.app ?: q, it.label) >= 0.75 }
                if (best != null) { autoRun = true; TopHit.App(best) }
                else firsts.firstOrNull()?.let { TopHit.Title(it, play = false) } // "start the matrix"
            }
            Action.RESUME, Action.NEXT_EPISODE -> {
                val pick = cwMatches.firstOrNull()
                    ?: refItems.firstOrNull()
                    ?: if (q.isEmpty()) snap.continueWatching.firstOrNull { it.meta.type == "series" || intent.action == Action.RESUME } else null
                when {
                    pick != null && snap.continueWatching.contains(pick) -> { autoRun = true; TopHit.Resume(pick) }
                    pick != null -> TopHit.Title(pick.meta, play = false)
                    else -> firsts.firstOrNull()?.let { TopHit.Title(it, play = false) }
                }
            }
            Action.PLAY -> {
                val cw = cwMatches.firstOrNull { c -> firsts.firstOrNull()?.id.let { it == null || it == c.meta.id } }
                when {
                    cw != null && season == null -> { autoRun = true; TopHit.Resume(cw) }
                    firsts.isNotEmpty() -> {
                        val m = firsts.first()
                        // Confident when the title we found matches what was asked for.
                        autoRun = intent.source == VoiceIntent.Source.LLM || score(q, m.name) >= 0.75
                        TopHit.Title(m, season, episode, play = true)
                    }
                    else -> null
                }
            }
            Action.SHOW_RESULTS -> when {
                refItems.isNotEmpty() && snap.continueWatching.contains(refItems.first()) -> TopHit.Resume(refItems.first())
                titles.isNotEmpty() -> TopHit.Title(titles.first(), season, episode, play = false)
                apps.isNotEmpty() && appScore(q, apps.first().label) >= 0.9 -> TopHit.App(apps.first())
                else -> null
            }
        }

        val reply = intent.reply ?: when (top) {
            is TopHit.Resume -> "Continuing ${top.item.meta.name}"
            is TopHit.Title -> if (top.play && autoRun) "Playing ${top.meta.name}" else "Results for “${q.ifEmpty { transcript }}”"
            is TopHit.App -> if (autoRun) "Opening ${top.app.label}" else top.app.label
            is TopHit.YouTube -> "Searching YouTube for “${top.query}”"
            null -> if (q.isEmpty() && transcript.isNotBlank()) "Sorry, I didn't catch that" else "No results for “${q.ifEmpty { transcript }}”"
        }

        VoiceResults(
            transcript = transcript,
            intent = intent,
            reply = reply,
            top = top,
            autoRun = autoRun,
            titles = titles,
            continueMatches = cwMatches,
            youtube = youtube,
            apps = apps.filter { appScore(intent.app ?: q, it.label) >= 0.6 }.take(6),
            notice = notice,
        )
    }

    private fun libraryAsItem(m: Meta) = ContinueItem(m, null, -1f, null, null, 0)

    /** Searches Cinemeta (and the user's search-capable add-ons) for one title and ranks the hits. */
    private suspend fun lookup(g: TitleGuess, catalogs: List<SearchCatalog>): List<Meta> = coroutineScope {
        val types = g.type?.let { listOf(it) } ?: listOf("movie", "series")
        val urls = types.map { "https://v3-cinemeta.strem.io/catalog/$it/top/search=${Uri.encode(g.name)}.json" } +
            catalogs.filter { it.type in types }.map { it.url(g.name) }
        val hits = urls.mapIndexed { source, url ->
            async(Dispatchers.IO) {
                runCatching { Cinemeta.parseCatalog(Http.get(url)) }
                    .onFailure { Log.w(TAG, "search failed $url: ${it.message}") }
                    .getOrDefault(emptyList())
                    .take(8)
                    .mapIndexed { rank, m -> Triple(m, rank, source) }
            }
        }.awaitAll().flatten()
        hits.filter { it.first.isImdb || it.first.id.contains(':') || it.first.id.isNotBlank() }
            .map { (m, rank, source) ->
                var s = score(g.name, m.name) * 10 - rank * 0.6 - source * 0.1
                val year = m.year?.take(4)?.toIntOrNull()
                if (g.year != null && year != null) s += if (year == g.year) 3.0 else if (kotlin.math.abs(year - g.year) <= 1) 1.0 else -2.0
                m to s
            }
            .sortedByDescending { it.second }
            .map { it.first }
            .distinctBy { it.id }
    }

    // ---- fuzzy matching ----

    private val stopWords = setOf(
        "the", "a", "an", "of", "and", "part", "chapter", "movie", "film", "show", "series", "season",
        "one", "two", "three", "ii", "iii", "iv", "1", "2", "3", "4", "in", "on", "to", "for", "my",
    )

    private fun meaningful(s: String): List<String> = norm(s).split(' ').filter { it.length > 1 && it !in stopWords }

    fun norm(s: String): String = RuleParser.normalize(s)
        .replace("&", " and ").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        .removePrefix("the ")

    /** 0..1 similarity between what was said and a title. */
    fun score(said: String, title: String): Double {
        val a = norm(said)
        val b = norm(title)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val bMain = b.substringBefore(" part ").trim()
        if (a == bMain) return 0.95
        if (b.startsWith("$a ") || a.startsWith("$b ")) return 0.85
        val ta = a.split(' ').toSet()
        val tb = b.split(' ').toSet()
        val jaccard = ta.intersect(tb).size.toDouble() / ta.union(tb).size
        val lev = 1.0 - levenshtein(a, b).toDouble() / maxOf(a.length, b.length)
        return maxOf(jaccard, lev) * 0.9
    }

    private val appAliases = mapOf(
        "youtube" to listOf("tizentube", "smarttube", "youtube"),
        "prime" to listOf("prime video", "amazon"),
        "amazon" to listOf("prime video"),
        "disney" to listOf("disney+", "disney plus"),
        "hbo" to listOf("max", "hbo max"),
        "max" to listOf("hbo max"),
        "plex" to listOf("plex"),
        "settings" to listOf("settings"),
        "nuvio" to listOf("nuvio"),
    )

    fun appScore(said: String, label: String): Double {
        val a = norm(said).removeSuffix(" app")
        val b = norm(label)
        if (a.isEmpty()) return 0.0
        if (a == b) return 1.0
        appAliases[a]?.let { aliases -> if (aliases.any { b.contains(norm(it)) }) return 0.95 }
        if (b.startsWith(a) || b.split(' ').contains(a)) return 0.9
        if (a.contains(b) && b.length >= 4) return 0.85
        return score(a, b)
    }

    private fun matchApps(said: String, apps: List<AppEntry>): List<AppEntry> =
        apps.map { it to appScore(said, it.label) }.filter { it.second >= 0.6 }.sortedByDescending { it.second }.map { it.first }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }
}
