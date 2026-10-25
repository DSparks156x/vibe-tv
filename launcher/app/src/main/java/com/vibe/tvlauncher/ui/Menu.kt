package com.vibe.tvlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.vibe.tvlauncher.data.AppEntry
import com.vibe.tvlauncher.data.ContinueItem
import com.vibe.tvlauncher.data.Meta

/** What a long-press menu is about. */
sealed interface MenuTarget {
    data class Title(val meta: Meta, val cw: ContinueItem? = null) : MenuTarget
    data class App(val app: AppEntry) : MenuTarget
    data object Profiles : MenuTarget
}

data class MenuAction(val label: String, val run: () -> Unit)

/** Long-press on any card or app opens a menu through this. */
val LocalOpenMenu = staticCompositionLocalOf<(MenuTarget) -> Unit> { {} }

/** Google TV style option sheet: dark rounded panel with pill actions, first one focused. */
@Composable
fun ActionMenu(title: String, subtitle: String?, actions: List<MenuAction>, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    // The menu usually opens while OK is still held (long-press). Swallow that hold's
    // auto-repeats and its release so they don't instantly pick the first option;
    // arm on the release, or on a fresh press if the menu was opened some other way.
    var armed by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .onPreviewKeyEvent { ev ->
                    val select = ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.NumPadEnter
                    if (!select || armed) return@onPreviewKeyEvent false
                    if (ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.repeatCount == 0) {
                        armed = true
                        false
                    } else {
                        if (ev.type == KeyEventType.KeyUp) armed = true
                        true
                    }
                }
                .width(360.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF202124))
                .padding(20.dp),
        ) {
            Text(
                title,
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = TextSecondary, fontSize = 12.sp, maxLines = 1)
            }
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                actions.forEachIndexed { i, a ->
                    Surface(
                        onClick = {
                            onDismiss()
                            a.run()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.White.copy(alpha = 0.06f),
                            contentColor = TextPrimary,
                            focusedContainerColor = Color.White,
                            focusedContentColor = Color(0xFF202124),
                        ),
                    ) {
                        Text(a.label, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp), fontSize = 14.sp)
                    }
                }
            }
        }
    }
    LaunchedEffect(actions.size) { runCatching { first.requestFocus() } }
}

/**
 * Remembers the last focused card per row, so coming back to a row lands where you left it
 * (Google TV behavior). Hand-rolled because Compose 1.7's focusRestorer broke D-pad Down here.
 */
class RowMemory {
    var last: String? = null
    val requesters = HashMap<String, FocusRequester>()
    fun enter(): FocusRequester = last?.let { requesters[it] } ?: FocusRequester.Default
}

val LocalRowMemory = staticCompositionLocalOf<RowMemory?> { null }

/** Wrap each row item with this so the row can restore focus to it. */
@Composable
fun Remembered(key: String, content: @Composable () -> Unit) {
    val mem = LocalRowMemory.current
    if (mem == null) {
        content()
        return
    }
    val req = remember(key) { FocusRequester() }
    DisposableEffect(key) {
        mem.requesters[key] = req
        onDispose { if (mem.requesters[key] === req) mem.requesters.remove(key) }
    }
    Box(Modifier.focusRequester(req).onFocusChanged { if (it.hasFocus) mem.last = key }) {
        content()
    }
}
