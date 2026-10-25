@file:OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)

package com.vibe.tvlauncher.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.vibe.tvlauncher.HomeState
import com.vibe.tvlauncher.HomeViewModel
import com.vibe.tvlauncher.data.AppEntry
import com.vibe.tvlauncher.data.ContinueItem
import com.vibe.tvlauncher.data.Meta
import com.vibe.tvlauncher.data.Nuvio
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Tab(val label: String) { Home("Home"), Movies("Movies"), Shows("Shows"), Apps("Apps"), Library("Library") }

private val HeroHeight = 250.dp

/** One horizontal row on a browse page. */
sealed interface Section {
    val key: String

    data class Continue(val items: List<ContinueItem>) : Section { override val key = "cw" }
    data class NewEpisodes(val items: List<ContinueItem>) : Section { override val key = "newep" }
    data object SignIn : Section { override val key = "signin" }
    data class Apps(val apps: List<AppEntry>) : Section { override val key = "apps" }
    data class Picks(override val key: String, val title: String, val metas: List<Meta>) : Section
    data class Posters(override val key: String, val title: String, val metas: List<Meta>) : Section
    data class Trailers(val metas: List<Meta>) : Section { override val key = "trailers" }
}

private fun homeSections(s: HomeState): List<Section> = buildList {
    when {
        !s.nuvio -> add(Section.SignIn)
        s.continueWatching.isNotEmpty() -> add(Section.Continue(s.continueWatching))
    }
    if (s.newEpisodes.isNotEmpty()) add(Section.NewEpisodes(s.newEpisodes))
    add(Section.Picks("picks", "Top picks for you", s.topPicks))
    if (s.apps.isNotEmpty()) add(Section.Apps(s.apps.filter { !it.hidden }.take(14)))
    s.tasteRows.forEachIndexed { i, (title, metas) -> add(Section.Posters("taste$i", title, metas)) }
    add(Section.Posters("movies", "Trending movies", s.movies))
    add(Section.Posters("shows", "Trending shows", s.shows))
    s.addonRows.forEachIndexed { i, (title, metas) -> add(Section.Posters("addon$i", title, metas)) }
    add(Section.Trailers(s.trailers))
    add(Section.Posters("library", "Your library", s.library))
    add(Section.Posters("rated", "Top rated movies", s.topRated))
}

private fun browseSections(rows: List<Pair<String, List<Meta>>>, type: String): List<Section> =
    rows.mapIndexed { i, (title, metas) ->
        if (i == 1) Section.Picks("$type$i", title, metas) else Section.Posters("$type$i", title, metas)
    }

private fun librarySections(s: HomeState): List<Section> = buildList {
    if (!s.nuvio) add(Section.SignIn)
    if (s.continueWatching.isNotEmpty()) add(Section.Continue(s.continueWatching))
    add(Section.Posters("lib_movies", "Movies", s.library.filter { it.type == "movie" }))
    add(Section.Posters("lib_shows", "Shows", s.library.filter { it.type == "series" }))
    add(Section.Posters("lib_other", "Other", s.library.filter { it.type != "movie" && it.type != "series" }))
}

@Composable
fun LauncherApp(vm: HomeViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    val listStates = remember { Tab.entries.associateWith { LazyListState() } }
    val listState = listStates.getValue(tab)
    val heroFocus = remember { FocusRequester() }
    var menu by remember { mutableStateOf<MenuTarget?>(null) }
    val openMenu: (MenuTarget) -> Unit = remember { { menu = it } }
    menu?.let { target -> LauncherMenu(target, vm, state) { menu = null } }

    val heroFocused = remember { mutableStateOf(false) }
    val tabFocus = remember { Tab.entries.associateWith { FocusRequester() } }
    val contentFocus = remember { FocusRequester() }

    /** The hero may not be laid out yet (lazy list, data still loading), so keep asking briefly. */
    suspend fun focusHero() {
        heroFocused.value = false // may be stale if the hero left composition while focused
        repeat(20) {
            if (heroFocused.value) return
            runCatching { heroFocus.requestFocus() }
            delay(100)
        }
    }
    // Read only inside Backdrop so focus moves don't recompose the whole screen.
    val backdrop = remember { mutableStateOf<String?>(null) }
    val setBackdrop: (String?) -> Unit = remember { { url -> if (url != null) backdrop.value = url } }

    fun goHome() {
        tab = Tab.Home
        scope.launch {
            delay(30)
            listStates.getValue(Tab.Home).scrollToItem(0)
            focusHero()
        }
    }

    LaunchedEffect(state.homeEvent) { if (state.homeEvent > 0) goHome() }
    LaunchedEffect(state.hero.isNotEmpty()) {
        if (state.hero.isNotEmpty()) {
            if (backdrop.value == null) backdrop.value = state.hero.first().backdrop("medium")
            focusHero()
        }
    }
    LaunchedEffect(tab) {
        when (tab) {
            Tab.Movies -> vm.loadBrowse("movie")
            Tab.Shows -> vm.loadBrowse("series")
            else -> Unit
        }
    }

    // Back never leaves the launcher; it just walks back to the top of Home.
    BackHandler { goHome() }

    val density = LocalDensity.current
    val atTop by remember(listState) {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset < with(density) { 40.dp.toPx() }
        }
    }
    val onAccount = { if (state.profiles.size > 1) menu = MenuTarget.Profiles else context.launch(Nuvio.mainIntent()) }

    CompositionLocalProvider(LocalSetBackdrop provides setBackdrop, LocalOpenMenu provides openMenu) {
        Box(Modifier.fillMaxSize()) {
            Backdrop({ backdrop.value }, dim = tab == Tab.Apps || !atTop)
            Box(
                Modifier
                    .fillMaxSize()
                    .focusRequester(contentFocus)
                    .focusProperties { enter = { if (tab == Tab.Home) heroFocus else FocusRequester.Default } }
                    .focusGroup(),
            ) {
            when (tab) {
                Tab.Home -> BrowseList(listState, state.hero, heroFocus, homeSections(state), onAccount, heroFocused = heroFocused)
                Tab.Movies -> BrowseList(
                    listState, state.movies.take(8), null,
                    browseSections(state.browse["movie"].orEmpty(), "movie"), onAccount,
                )
                Tab.Shows -> BrowseList(
                    listState, state.shows.take(8), null,
                    browseSections(state.browse["series"].orEmpty(), "series"), onAccount,
                )
                Tab.Apps -> AppsGrid(state.apps) { context.launch(it.intent) }
                Tab.Library -> BrowseList(listState, emptyList(), null, librarySections(state), onAccount, header = "Library")
            }
            }
            AnimatedVisibility(
                visible = tab == Tab.Apps || atTop,
                enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { -it / 2 },
                exit = fadeOut(tween(150)) + slideOutVertically(tween(150)) { -it / 2 },
            ) {
                TopBar(
                    tab = tab,
                    avatar = state.avatar ?: state.profile?.avatarUrl,
                    avatarLetter = state.profile?.name?.firstOrNull()?.uppercaseChar(),
                    weather = state.weather,
                    onTab = { tab = it },
                    onSearch = { context.launch(com.vibe.tvlauncher.voice.VoiceActivity.typeIntent(context)) },
                    onAccount = onAccount,
                    onSettings = { context.launch(Intent(context, com.vibe.tvlauncher.settings.SettingsActivity::class.java)) },
                    tabFocus = tabFocus,
                    onDown = {
                        if (tab == Tab.Home) runCatching { heroFocus.requestFocus() }
                        else runCatching { contentFocus.requestFocus() }
                    },
                )
            }
        }
    }
}

/** Keeps the focused row in the lower-middle of the screen, like Google TV. */
private val verticalPivot = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        offset - containerSize * 0.58f
}

/** Pins the focused card to the row's left edge, like Google TV. */
private fun horizontalPivot(startPx: Float): BringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        if (size >= containerSize) return 0f
        return offset - startPx
    }
}

@Composable
private fun BrowseList(
    listState: LazyListState,
    hero: List<Meta>,
    heroFocus: FocusRequester?,
    sections: List<Section>,
    onAccount: () -> Unit,
    header: String? = null,
    heroFocused: androidx.compose.runtime.MutableState<Boolean>? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val hSpec = remember(density) { horizontalPivot(with(density) { PadH.toPx() }) }

    val openMeta: (Meta) -> Unit = { context.launch(Nuvio.detailIntent(it)) }
    val trailer: (Meta) -> Unit = { m -> m.trailerYt?.let { context.openYouTube(it) } }

    val memories = remember(listState) { HashMap<String, RowMemory>() }
    CompositionLocalProvider(LocalBringIntoViewSpec provides verticalPivot, LocalRowMemories provides memories) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = TopBarHeight, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (hero.isNotEmpty() || heroFocus != null) {
                item(key = "hero") {
                    val openMenu = LocalOpenMenu.current
                    Hero(hero, heroFocus, onOpen = openMeta, onLong = { openMenu(MenuTarget.Title(it)) }, onFocus = { heroFocused?.value = it })
                }
            } else if (header != null) {
                item(key = "header") {
                    Text(
                        header,
                        modifier = Modifier.padding(start = PadH, top = 60.dp, bottom = 20.dp),
                        color = Color.White,
                        fontSize = 34.sp,
                    )
                }
            }
            sections.forEach { section ->
                section(section, hSpec, onAccount, openMeta, trailer, context)
            }
        }
    }
}

private fun LazyListScope.section(
    s: Section,
    hSpec: BringIntoViewSpec,
    onAccount: () -> Unit,
    openMeta: (Meta) -> Unit,
    trailer: (Meta) -> Unit,
    context: Context,
) {
    when (s) {
        Section.SignIn -> row(s.key, "Continue watching", hSpec) {
            item { SignInCard(onAccount) }
        }
        is Section.Continue -> row(s.key, "Continue watching", hSpec) {
            items(s.items, key = { "cw" + it.meta.id }) { c -> Remembered("cw" + c.meta.id) {
                val openMenu = LocalOpenMenu.current
                LandscapeCard(
                    image = c.thumbnail ?: c.meta.backdrop("small") ?: c.meta.posterUrl(),
                    backdrop = c.meta.backdrop("medium"),
                    title = c.meta.name,
                    width = 172.dp,
                    progress = c.progress,
                    logo = c.meta.logoUrl(),
                    badge = c.subtitle,
                    onClick = { context.launch(Nuvio.resumeIntent(c)) },
                    onLongClick = { openMenu(MenuTarget.Title(c.meta, c)) },
                )
            } }
        }
        is Section.NewEpisodes -> row(s.key, "New episodes", hSpec) {
            items(s.items, key = { "ne" + it.meta.id }) { c -> Remembered("ne" + c.meta.id) {
                val openMenu = LocalOpenMenu.current
                LandscapeCard(
                    image = c.thumbnail ?: c.meta.backdrop("small"),
                    backdrop = c.meta.backdrop("medium"),
                    title = c.meta.name,
                    width = 172.dp,
                    logo = c.meta.logoUrl(),
                    badge = "S${c.season} E${c.episode} · New",
                    caption = c.episodeTitle,
                    onClick = { context.launch(Nuvio.resumeIntent(c)) },
                    onLongClick = { openMenu(MenuTarget.Title(c.meta)) },
                )
            } }
        }
        is Section.Apps -> row(s.key, "Your apps", hSpec, spacing = 10.dp) {
            items(s.apps, key = { "app" + it.key }) { app -> Remembered("app" + app.key) {
                val openMenu = LocalOpenMenu.current
                AppCircle(app, onClick = { context.launch(app.intent) }, onLongClick = { openMenu(MenuTarget.App(app)) })
            } }
        }
        is Section.Picks -> if (s.metas.isNotEmpty()) row(s.key, s.title, hSpec) {
            items(s.metas, key = { s.key + it.id }) { m -> Remembered(s.key + m.id) {
                val openMenu = LocalOpenMenu.current
                LandscapeCard(
                    image = m.backdrop("small"),
                    backdrop = m.backdrop("medium"),
                    title = m.name,
                    logo = m.logoUrl(),
                    onClick = { openMeta(m) },
                    onLongClick = { openMenu(MenuTarget.Title(m)) },
                )
            } }
        }
        is Section.Posters -> if (s.metas.isNotEmpty()) row(s.key, s.title, hSpec, spacing = 12.dp) {
            items(s.metas, key = { s.key + it.id }) { m -> Remembered(s.key + m.id) {
                val openMenu = LocalOpenMenu.current
                PosterCard(
                    image = m.posterUrl(),
                    title = m.name,
                    backdrop = m.backdrop("medium"),
                    onClick = { openMeta(m) },
                    onLongClick = { openMenu(MenuTarget.Title(m)) },
                )
            } }
        }
        is Section.Trailers -> if (s.metas.isNotEmpty()) row(s.key, "Trailers on YouTube", hSpec) {
            items(s.metas, key = { "yt" + it.id }) { m -> Remembered("yt" + m.id) {
                LandscapeCard(
                    image = "https://i.ytimg.com/vi/${m.trailerYt}/mqdefault.jpg",
                    backdrop = m.backdrop("medium"),
                    title = m.name,
                    badge = "▶ YouTube",
                    caption = m.name,
                    onClick = { trailer(m) },
                )
            } }
        }
    }
}

/** Per-page row focus memory, kept outside the lazy list so it survives rows scrolling away. */
private val LocalRowMemories = androidx.compose.runtime.staticCompositionLocalOf<MutableMap<String, RowMemory>> { HashMap() }

private fun LazyListScope.row(
    key: String,
    title: String,
    hSpec: BringIntoViewSpec,
    spacing: Dp = 16.dp,
    content: LazyListScope.() -> Unit,
) {
    item(key = key) {
        val mem = LocalRowMemories.current.getOrPut(key) { RowMemory() }
        Column {
            RowTitle(title)
            CompositionLocalProvider(LocalBringIntoViewSpec provides hSpec, LocalRowMemory provides mem) {
                LazyRow(
                    modifier = Modifier
                        .focusProperties { enter = { mem.enter() } }
                        .focusGroup(),
                    contentPadding = PaddingValues(horizontal = PadH, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                    content = content,
                )
            }
        }
    }
}

private fun metaLine(m: Meta): String = buildList {
    add(if (m.type == "series") "Show" else "Movie")
    m.year?.let(::add)
    if (m.genres.isNotEmpty()) add(m.genres.take(2).joinToString(", "))
    m.runtime?.let(::add)
    m.rating?.let { add("IMDb $it") }
}.joinToString(" · ")

@Composable
private fun SignInCard(onClick: () -> Unit) {
    FocusCard(onClick = onClick, modifier = Modifier.width(240.dp).height(76.dp)) {
        Box(
            Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF4B3A8C), Color(0xFF26407A)))),
            contentAlignment = Alignment.CenterStart,
        ) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("Get Nuvio", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text("Install the Vibe Nuvio build to see Continue watching", color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp)
            }
        }
    }
}

/**
 * The Google TV "spotlight": the artwork is the full-screen backdrop, this is just the text over it.
 * Left/right cycles slides while it is focused.
 */
@Composable
private fun Hero(
    items: List<Meta>,
    focusRequester: FocusRequester?,
    onOpen: (Meta) -> Unit,
    onLong: (Meta) -> Unit,
    onFocus: (Boolean) -> Unit = {},
) {
    val setBackdrop = LocalSetBackdrop.current
    var index by rememberSaveable { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    val n = items.size
    if (n > 0 && index >= n) index = 0

    LaunchedEffect(n, index, focused) {
        if (n == 0) return@LaunchedEffect
        if (focused) setBackdrop(items[index].backdrop("medium"))
        if (n > 1) {
            delay(if (focused) 9_000 else 7_000)
            if (focused) index = (index + 1) % n
        }
    }

    Surface(
        onClick = { items.getOrNull(index)?.let(onOpen) },
        onLongClick = { items.getOrNull(index)?.let(onLong) },
        modifier = Modifier
            .fillMaxWidth()
            .height(HeroHeight)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                onFocus(it.isFocused)
            }
            .onPreviewKeyEvent { ev ->
                if (n < 2 || ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.DirectionRight -> { index = (index + 1) % n; true }
                    Key.DirectionLeft -> { index = (index - 1 + n) % n; true }
                    else -> false
                }
            },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(0.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent,
            pressedContainerColor = Color.Transparent,
        ),
    ) {
        if (n == 0) return@Surface
        AnimatedContent(
            targetState = items[index],
            transitionSpec = { fadeIn(tween(500, delayMillis = 150)) togetherWith fadeOut(tween(250)) },
            label = "hero",
            modifier = Modifier.align(Alignment.BottomStart),
        ) { m ->
            Column(
                Modifier
                    .padding(start = PadH, bottom = 12.dp)
                    .widthIn(max = 430.dp),
            ) {
                Text(
                    if (m.type == "series") "Trending show" else "Trending movie",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(8.dp))
                LogoOrTitle(
                    logo = m.logoUrl(),
                    title = m.name,
                    modifier = Modifier.widthIn(max = 300.dp).heightIn(max = 72.dp),
                    fontSize = 34.sp,
                    wrap = true,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    metaLine(m),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                m.description?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        it,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 16.sp,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (focused) Color.White else Color.White.copy(alpha = 0.16f))
                        .padding(start = 10.dp, end = 14.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val fg = if (focused) Color(0xFF202124) else Color.White
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Watch on Nuvio", color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomEnd).padding(end = PadH, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(n) { i ->
                Box(
                    Modifier
                        .size(if (i == index) 8.dp else 6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (i == index) Color.White else Color.White.copy(alpha = 0.4f)),
                )
            }
        }
    }
}

@Composable
private fun AppsGrid(apps: List<AppEntry>, onOpen: (AppEntry) -> Unit) {
    val openMenu = LocalOpenMenu.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(6),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = PadH, end = PadH, top = TopBarHeight + 24.dp, bottom = 60.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        val (hidden, shown) = apps.partition { it.hidden }
        items(shown, key = { it.key }) { app -> AppGridItem(app, onOpen, openMenu) }
        if (hidden.isNotEmpty()) {
            item(key = "hidden_header", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Text("Hidden apps", modifier = Modifier.padding(top = 18.dp), color = TextSecondary, fontSize = 13.sp)
            }
            items(hidden, key = { it.key }) { app ->
                Box(Modifier.alpha(0.55f)) { AppGridItem(app, onOpen, openMenu) }
            }
        }
    }
}

@Composable
private fun AppGridItem(app: AppEntry, onOpen: (AppEntry) -> Unit, openMenu: (MenuTarget) -> Unit) {
    Column {
        AppBanner(app, onClick = { onOpen(app) }, onLongClick = { openMenu(MenuTarget.App(app)) })
        Spacer(Modifier.height(6.dp))
        Text(app.label, color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Builds the long-press menu for a title, an app, or the profile switcher. */
@Composable
private fun LauncherMenu(target: MenuTarget, vm: HomeViewModel, state: HomeState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    when (target) {
        is MenuTarget.Title -> {
            val m = target.meta
            // Library membership comes from Nuvio; null = this Nuvio build can't tell us.
            var inLibrary by remember(m.id) { mutableStateOf<Boolean?>(null) }
            LaunchedEffect(m.id) { inLibrary = vm.libraryContains(m) }
            val actions = buildList {
                val cw = target.cw
                if (cw != null) add(MenuAction(if (cw.nextUp) "Play next episode" else "Resume") { context.launch(Nuvio.resumeIntent(cw)) })
                add(MenuAction("Open in Nuvio") { context.launch(Nuvio.detailIntent(m)) })
                m.trailerYt?.let { yt -> add(MenuAction("Watch trailer") { context.openYouTube(yt) }) }
                inLibrary?.let { lib ->
                    add(MenuAction(if (lib) "Remove from library" else "Add to library") { vm.toggleLibrary(m) })
                }
                if (cw != null) {
                    add(MenuAction("Mark as watched") { vm.markWatched(cw) })
                    add(MenuAction("Remove from Continue watching") { vm.removeProgress(cw) })
                }
            }
            val sub = listOfNotNull(if (m.type == "series") "Show" else "Movie", m.year, target.cw?.subtitle).joinToString(" · ")
            ActionMenu(m.name, sub, actions, onDismiss)
        }
        is MenuTarget.App -> {
            val app = target.app
            val favs = state.apps.filter { it.favorite }.map { it.key }
            val i = favs.indexOf(app.key)
            val actions = buildList {
                add(MenuAction("Open") { context.launch(app.intent) })
                if (app.hidden) {
                    add(MenuAction("Show app again") { vm.setAppHidden(app.key, false) })
                } else if (app.favorite) {
                    if (i > 0) add(MenuAction("Move left") { vm.moveFavorite(app.key, -1) })
                    if (i in 0 until favs.size - 1) add(MenuAction("Move right") { vm.moveFavorite(app.key, +1) })
                    add(MenuAction("Remove from Your apps") { vm.setFavorite(app.key, false) })
                } else {
                    add(MenuAction("Add to Your apps") { vm.setFavorite(app.key, true) })
                }
                if (!app.hidden) add(MenuAction("Hide app") { vm.setAppHidden(app.key, true) })
                add(MenuAction("App info") {
                    context.launch(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${app.key}")),
                    )
                })
            }
            ActionMenu(app.label, null, actions, onDismiss)
        }
        MenuTarget.Profiles -> {
            val actions = state.profiles.map { p ->
                MenuAction(if (p.active) "${p.name} (current)" else p.name) { p.id?.let { vm.switchProfile(it) } }
            } + MenuAction("Open Nuvio") { context.launch(Nuvio.mainIntent()) }
            ActionMenu("Who's watching?", null, actions, onDismiss)
        }
    }
}
