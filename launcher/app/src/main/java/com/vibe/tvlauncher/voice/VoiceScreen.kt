package com.vibe.tvlauncher.voice

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.vibe.tvlauncher.data.ContinueItem
import com.vibe.tvlauncher.data.voice.LlmInterpreter
import com.vibe.tvlauncher.data.voice.TopHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.vibe.tvlauncher.data.voice.VoicePrefs
import com.vibe.tvlauncher.data.voice.VoiceResults
import com.vibe.tvlauncher.ui.AppCircle
import com.vibe.tvlauncher.ui.CardShape
import com.vibe.tvlauncher.ui.FocusCard
import com.vibe.tvlauncher.ui.GoogleSans
import com.vibe.tvlauncher.ui.LandscapeCard
import com.vibe.tvlauncher.ui.LogoOrTitle
import com.vibe.tvlauncher.ui.PadH
import com.vibe.tvlauncher.ui.PosterCard
import com.vibe.tvlauncher.ui.RowTitle
import com.vibe.tvlauncher.ui.TextSecondary

private val Scrim = Color(0xFA0E0F12)
private val GoogleColors = listOf(Color(0xFF4285F4), Color(0xFFEA4335), Color(0xFFFBBC04), Color(0xFF34A853))

@Composable
fun VoiceScreen(
    vm: VoiceViewModel,
    startTyping: Boolean,
    onMic: () -> Unit,
    onRun: (TopHit) -> Unit,
    onClose: () -> Unit,
) {
    var typing by remember { mutableStateOf(startTyping) }
    var settings by remember { mutableStateOf(false) }
    // Talking again leaves the keyboard.
    LaunchedEffect(vm.phase) { if (vm.phase == Phase.LISTENING) typing = false }

    // The single full-screen layer: a dark scrim over whatever is behind (no blur; the GPU is weak).
    Box(Modifier.fillMaxSize().background(Scrim)) {
        Column(Modifier.fillMaxSize().padding(top = 28.dp)) {
            TopBar(
                vm = vm,
                typing = typing,
                onMic = { settings = false; onMic() },
                onType = { settings = false; typing = true; vm.cancelListening() },
                onSettings = { settings = !settings; vm.cancelCountdown() },
            )
            Spacer(Modifier.height(18.dp))
            when {
                settings -> SettingsPanel(onDone = { settings = false })
                else -> Body(vm, onRun)
            }
        }
    }
}

@Composable
private fun TopBar(vm: VoiceViewModel, typing: Boolean, onMic: () -> Unit, onType: () -> Unit, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = PadH),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MicOrb(listening = vm.phase == Phase.LISTENING, level = vm.level, onClick = onMic)
        Spacer(Modifier.width(14.dp))
        Box(
            Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(24.dp)).background(Color.White.copy(alpha = 0.08f))
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (typing) {
                QueryField(initial = if (vm.phase == Phase.RESULTS) vm.transcript else "", onSubmit = vm::submit)
            } else {
                val text = when {
                    vm.transcript.isNotEmpty() -> vm.transcript
                    vm.phase == Phase.LISTENING -> "Listening…"
                    else -> "Press the mic and say what you want to watch"
                }
                Text(
                    text,
                    color = if (vm.transcript.isNotEmpty()) Color.White else TextSecondary,
                    fontSize = 20.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        if (!typing) RoundIcon(Icons.Filled.Search, "Type", onType)
        Spacer(Modifier.width(6.dp))
        RoundIcon(Icons.Filled.Settings, "Assistant settings", onSettings)
    }
}

@Composable
private fun QueryField(initial: String, onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    BasicTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 20.sp, fontFamily = GoogleSans),
        cursorBrush = SolidColor(Color.White),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); onSubmit(text.text) }, onDone = { keyboard?.hide(); onSubmit(text.text) }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus).dpadExit(text, focusManager),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (text.text.isEmpty()) Text("Search movies, shows, YouTube and apps", color = TextSecondary, fontSize = 20.sp)
                inner()
            }
        },
    )
}

/** Google-colored mic button that breathes with the voice level while listening. */
@Composable
private fun MicOrb(listening: Boolean, level: Float, onClick: () -> Unit) {
    val pulse by animateFloatAsState(if (listening) 1f + level * 0.35f else 1f, tween(90), label = "pulse")
    val t = rememberInfiniteTransition(label = "dots")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
        if (listening) {
            Box(Modifier.size(56.dp).scale(pulse).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)))
        }
        Surface(
            onClick = onClick,
            modifier = Modifier.size(44.dp),
            shape = ClickableSurfaceDefaults.shape(CircleShape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = if (listening) Color.White else Color.White.copy(alpha = 0.14f),
                focusedContainerColor = Color.White,
            ),
        ) {
            // Four bouncing dots while listening, a mic glyph otherwise.
            Box(
                Modifier.fillMaxSize().drawBehind {
                    if (listening) {
                        val w = size.width
                        val dot = w * 0.11f
                        val gap = w * 0.06f
                        val total = dot * 4 + gap * 3
                        GoogleColors.forEachIndexed { i, c ->
                            val wave = kotlin.math.sin((phase + i * 0.22f) * 2 * Math.PI).toFloat()
                            val h = dot * (1f + (0.4f + level * 2.2f) * (0.5f + 0.5f * wave))
                            val x = (w - total) / 2 + i * (dot + gap)
                            drawRoundRect(c, Offset(x, (size.height - h) / 2), Size(dot, h), CornerRadius(dot / 2))
                        }
                    } else {
                        val c = Color(0xFF202124)
                        val mw = size.width * 0.2f
                        val mh = size.height * 0.36f
                        drawRoundRect(c, Offset((size.width - mw) / 2, size.height * 0.2f), Size(mw, mh), CornerRadius(mw / 2))
                        drawArc(c, 0f, 180f, false, Offset(size.width * 0.3f, size.height * 0.3f), Size(size.width * 0.4f, size.height * 0.36f),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = size.width * 0.06f))
                        drawRect(c, Offset(size.width * 0.47f, size.height * 0.66f), Size(size.width * 0.06f, size.height * 0.12f))
                    }
                },
            )
        }
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, desc: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.08f),
            contentColor = Color.White.copy(alpha = 0.85f),
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFF202124),
        ),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = desc, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun Body(vm: VoiceViewModel, onRun: (TopHit) -> Unit) {
    when (vm.phase) {
        Phase.IDLE, Phase.LISTENING -> Hints(listening = vm.phase == Phase.LISTENING)
        Phase.THINKING -> Status("Thinking…")
        Phase.ERROR -> Status(vm.error ?: "Something went wrong")
        Phase.RESULTS -> vm.results?.let { Results(it, vm.countdown, onRun) }
    }
}

@Composable
private fun Status(text: String) {
    Text(text, modifier = Modifier.padding(horizontal = PadH + 70.dp), color = TextSecondary, fontSize = 18.sp)
}

@Composable
private fun Hints(listening: Boolean) {
    val context = LocalContext.current
    val smart = remember { VoicePrefs.configured(context) }
    Column(Modifier.padding(horizontal = PadH + 70.dp)) {
        Text(if (listening) "Try saying" else "You can say", color = TextSecondary, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        val examples = buildList {
            add("“Play Inception”")
            add("“Continue my show”")
            add("“Lofi beats on YouTube”")
            add("“Open Spotify”")
            if (smart) {
                add("“Something funny from the 90s”")
                add("“That show with the meth teacher”")
                add("“What was I watching yesterday?”")
            }
        }
        examples.forEach { Text(it, color = Color.White.copy(alpha = 0.85f), fontSize = 18.sp, modifier = Modifier.padding(vertical = 3.dp)) }
        if (!smart) {
            Spacer(Modifier.height(14.dp))
            Text("For looser requests, connect a language model with the gear icon (Groq, Gemini, or your own server).", color = TextSecondary, fontSize = 13.sp)
        }
    }
}

@Composable
private fun Results(r: VoiceResults, countdown: Float?, onRun: (TopHit) -> Unit) {
    val topFocus = remember(r) { FocusRequester() }
    LaunchedEffect(r) { runCatching { topFocus.requestFocus() } }
    LazyColumn(
        contentPadding = PaddingValues(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = PadH)) {
                Text(r.reply, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                r.notice?.let { Text(it, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
            }
        }
        r.top?.let { top ->
            item { TopCard(top, countdown, Modifier.focusRequester(topFocus)) { onRun(top) } }
        }
        val titles = r.titles.filter { (r.top as? TopHit.Title)?.meta?.id != it.id }
        if (titles.isNotEmpty()) item {
            Column {
                RowTitle("Movies & shows")
                LazyRow(contentPadding = PaddingValues(horizontal = PadH), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(titles, key = { it.id }) { m ->
                        PosterCard(image = m.posterUrl(), title = m.name, onClick = { onRun(TopHit.Title(m, play = false)) })
                    }
                }
            }
        }
        val cw = r.continueMatches.filter { (r.top as? TopHit.Resume)?.item?.meta?.id != it.meta.id }
        if (cw.isNotEmpty()) item {
            Column {
                RowTitle("Continue watching")
                LazyRow(contentPadding = PaddingValues(horizontal = PadH), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(cw, key = { it.meta.id }) { c -> ContinueCard(c) { onRun(TopHit.Resume(c)) } }
                }
            }
        }
        val yt = r.youtube.filter { (r.top as? TopHit.YouTube)?.query != it }
        if (yt.isNotEmpty()) item {
            Column {
                RowTitle("On YouTube")
                LazyRow(contentPadding = PaddingValues(horizontal = PadH), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(yt) { q -> YouTubeChip(q) { onRun(TopHit.YouTube(q)) } }
                }
            }
        }
        val apps = r.apps.filter { (r.top as? TopHit.App)?.app?.key != it.key }
        if (apps.isNotEmpty()) item {
            Column {
                RowTitle("Apps")
                LazyRow(contentPadding = PaddingValues(horizontal = PadH), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    items(apps, key = { it.key }) { a -> AppCircle(a, onClick = { onRun(TopHit.App(a)) }) }
                }
            }
        }
    }
}

@Composable
private fun ContinueCard(c: ContinueItem, onClick: () -> Unit) {
    LandscapeCard(
        image = c.thumbnail ?: c.meta.backdrop(),
        title = c.meta.name,
        onClick = onClick,
        progress = c.progress.takeIf { it > 0f && !c.nextUp },
        logo = c.meta.logoUrl(),
        caption = c.subtitle,
    )
}

/** The one big "this is what I think you meant" card, with the auto-run countdown under it. */
@Composable
private fun TopCard(top: TopHit, countdown: Float?, modifier: Modifier, onClick: () -> Unit) {
    val (image, logo, title, sub, verb) = when (top) {
        is TopHit.Title -> Quint(top.meta.backdrop(), top.meta.logoUrl(), top.meta.name,
            listOfNotNull(top.meta.year, top.meta.type.replaceFirstChar { it.uppercase() }.replace("Series", "Show"),
                top.season?.let { s -> "S$s" + (top.episode?.let { " E$it" } ?: "") }).joinToString(" · "),
            if (top.play) "Play" else "Open")
        is TopHit.Resume -> Quint(top.item.thumbnail ?: top.item.meta.backdrop(), top.item.meta.logoUrl(), top.item.meta.name,
            top.item.subtitle ?: "Continue watching", "Resume")
        is TopHit.App -> Quint(null, null, top.app.label, "App", "Open")
        is TopHit.YouTube -> Quint(null, null, top.query, "Search on YouTube", "Search")
    }
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = PadH)) {
        FocusCard(onClick = onClick, modifier = modifier.width(420.dp).height(200.dp), focusedScale = 1.03f, onFocus = { focused = it }) {
            Box(Modifier.fillMaxSize().clip(CardShape).background(Color(0xFF26282D))) {
                if (image != null) AsyncImage(model = image, contentDescription = null, modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                if (top is TopHit.App) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { AppCircle(top.app, onClick = onClick, size = 90.dp) }
                }
                if (top is TopHit.YouTube) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF1F1F1F)), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(width = 84.dp, height = 58.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFFFF0000)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(40.dp))
                        }
                    }
                }
                // Readability gradient baked into one small band at the bottom.
                Box(Modifier.fillMaxWidth().height(110.dp).align(Alignment.BottomStart)
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))))
                Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                    if (top is TopHit.Title || top is TopHit.Resume) {
                        LogoOrTitle(logo = logo, title = title, modifier = Modifier.width(240.dp).height(54.dp), fontSize = 24.sp)
                    } else {
                        Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(sub, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, maxLines = 1)
                }
                Box(
                    Modifier.align(Alignment.TopEnd).padding(12.dp).clip(RoundedCornerShape(16.dp))
                        .background(if (focused) Color.White else Color.Black.copy(alpha = 0.6f)).padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(verb, color = if (focused) Color(0xFF202124) else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        if (countdown != null) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.width(420.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.15f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(countdown).background(Color.White))
            }
            Text("Starting… press any key to stay here", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

private data class Quint(val a: String?, val b: String?, val c: String, val d: String, val e: String)

@Composable
private fun YouTubeChip(query: String, onClick: () -> Unit) {
    FocusCard(onClick = onClick, modifier = Modifier.height(64.dp), shape = RoundedCornerShape(32.dp), focusedScale = 1.06f) {
        Row(
            Modifier.clip(RoundedCornerShape(32.dp)).background(Color.White.copy(alpha = 0.08f)).padding(horizontal = 18.dp).fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(width = 30.dp, height = 21.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFFFF0000)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(query, color = Color.White, fontSize = 16.sp, maxLines = 1)
        }
    }
}

@Composable
private fun SettingsPanel(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var provider by remember { mutableStateOf(VoicePrefs.provider(context)) }
    var key by remember(provider) { mutableStateOf(VoicePrefs.apiKey(context, provider)) }
    var model by remember(provider) { mutableStateOf(VoicePrefs.model(context, provider)) }
    var url by remember { mutableStateOf(VoicePrefs.customUrl(context)) }
    var status by remember(provider) { mutableStateOf<String?>(null) }
    var models by remember(provider) { mutableStateOf<List<String>>(emptyList()) }

    // Check the connection and list the models whenever the setup changes.
    fun refresh() {
        val base = VoicePrefs.baseUrl(context, provider) ?: return
        if (provider.needsKey && key == null) return
        status = "Checking…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { LlmInterpreter.models(base, key) } }
            r.onSuccess { list ->
                models = list
                if (model == null || model !in list) {
                    model = LlmInterpreter.pickDefault(list, provider.preferredModels)
                    VoicePrefs.setModel(context, provider, model)
                }
                status = "Connected · ${list.size} models"
            }.onFailure { status = it.message ?: "Couldn't connect" }
        }
    }
    LaunchedEffect(provider) { refresh() }

    Column(Modifier.padding(horizontal = PadH + 70.dp).width(600.dp)) {
        Text("Assistant settings", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Plain commands always work offline. To understand looser requests, connect a language model " +
                "with your own key. It's stored only on this TV and only sent to the provider you pick.",
            color = TextSecondary, fontSize = 13.sp,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Model provider: " + provider.label) {
                val all = VoicePrefs.PROVIDERS
                provider = all[(all.indexOf(provider) + 1) % all.size]
                VoicePrefs.setProvider(context, provider.id)
            }
            if (provider.id != "off") PillButton("Done", onDone)
        }
        if (provider.id == "custom") {
            Spacer(Modifier.height(12.dp))
            Text("Server URL: " + (url ?: "Not set"), color = Color.White.copy(alpha = 0.9f), fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            SecretField(hint = provider.keyHint, secret = false) {
                VoicePrefs.setCustomUrl(context, it); url = VoicePrefs.customUrl(context); refresh()
            }
        }
        if (provider.id != "off") {
            Spacer(Modifier.height(12.dp))
            Text(
                "API key: " + VoicePrefs.masked(key) + if (provider.keyHint.isNotEmpty() && provider.needsKey) "  (get one at ${provider.keyHint})" else "",
                color = Color.White.copy(alpha = 0.9f), fontSize = 15.sp,
            )
            Spacer(Modifier.height(6.dp))
            SecretField(hint = "Type or paste the key, then Done", secret = true) {
                VoicePrefs.setApiKey(context, provider, it); key = VoicePrefs.apiKey(context, provider); refresh()
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton("Model: " + (model ?: "auto")) {
                    if (models.isNotEmpty()) {
                        model = models[(models.indexOf(model) + 1) % models.size]
                        VoicePrefs.setModel(context, provider, model)
                    }
                }
                if (key != null) PillButton("Remove key") { VoicePrefs.setApiKey(context, provider, null); key = null; status = null }
            }
            status?.let { Text(it, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
        } else {
            Spacer(Modifier.height(12.dp))
            PillButton("Done", onDone)
        }
    }
}

@Composable
private fun SecretField(hint: String, secret: Boolean, onSave: (String) -> Unit) {
    var draft by remember { mutableStateOf(TextFieldValue("")) }
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    BasicTextField(
        value = draft,
        onValueChange = { draft = it.copy(text = it.text.trim()) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        textStyle = TextStyle(color = Color.White, fontSize = 16.sp, fontFamily = GoogleSans),
        cursorBrush = SolidColor(Color.White),
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            keyboard?.hide()
            if (draft.text.isNotBlank()) { onSave(draft.text); draft = TextFieldValue("") }
        }),
        modifier = Modifier.fillMaxWidth().height(44.dp).onFocusChanged { focused = it.isFocused }.dpadExit(draft, focusManager)
            .clip(RoundedCornerShape(22.dp)).background(Color.White.copy(alpha = 0.08f))
            .border(2.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(22.dp))
            .padding(horizontal = 18.dp, vertical = 12.dp),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (draft.text.isEmpty()) Text(hint, color = TextSecondary, fontSize = 15.sp)
                inner()
            }
        },
    )
}

@Composable
private fun PillButton(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.1f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFF202124),
        ),
    ) {
        Text(text, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp))
    }
}

/** TV text fields swallow the D-pad; let arrows leave the field at its edges so other buttons stay reachable. */
private fun Modifier.dpadExit(value: TextFieldValue, focusManager: FocusManager): Modifier = onPreviewKeyEvent { e ->
    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    val sel = value.selection
    when (e.key) {
        Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
        Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
        Key.DirectionRight -> sel.collapsed && sel.end >= value.text.length && focusManager.moveFocus(FocusDirection.Right)
        Key.DirectionLeft -> sel.collapsed && sel.start == 0 && focusManager.moveFocus(FocusDirection.Left)
        else -> false
    }
}
