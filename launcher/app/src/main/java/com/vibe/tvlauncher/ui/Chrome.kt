@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.vibe.tvlauncher.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.animation.core.Animatable
import android.graphics.drawable.BitmapDrawable
import coil.imageLoader
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.vibe.tvlauncher.data.Sky
import com.vibe.tvlauncher.data.Weather
import com.vibe.tvlauncher.data.sky
import kotlinx.coroutines.delay
import java.io.File
import java.util.Date

val TopBarHeight = 66.dp
private val PillBg = Color.White.copy(alpha = 0.14f)

/**
 * Full-screen artwork behind everything. Scrims are baked in by [ScrimTransformation] and changes
 * dip through dark instead of crossfading: this TV's GPU can't blend two 1080p bitmaps in a frame,
 * but one bitmap drawn with paint alpha is cheap.
 */
@Composable
fun Backdrop(url: () -> String?, dim: Boolean) {
    val context = LocalContext.current
    val target = url()
    // Debounce so fast D-pad scrolling doesn't thrash image loads.
    var shown by remember { mutableStateOf(target) }
    LaunchedEffect(target) {
        delay(250)
        shown = target
    }
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(shown, dim) {
        val src = shown ?: return@LaunchedEffect
        val result = context.imageLoader.execute(
            ImageRequest.Builder(context)
                .data(src)
                .memoryCacheKey("bd:$dim:$src")
                .size(1280, 720)
                .transformations(ScrimTransformation(dim))
                .build(),
        )
        val bmp = (result.drawable as? BitmapDrawable)?.bitmap?.asImageBitmap() ?: return@LaunchedEffect
        if (bitmap != null) alpha.animateTo(0.25f, tween(100))
        bitmap = bmp
        alpha.animateTo(1f, tween(200))
    }
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                val img = bitmap ?: return@drawBehind
                // ContentScale.Crop anchored top-end.
                val scale = maxOf(size.width / img.width, size.height / img.height)
                val srcW = size.width / scale
                val srcH = size.height / scale
                drawImage(
                    img,
                    srcOffset = IntOffset((img.width - srcW).toInt().coerceAtLeast(0), 0),
                    srcSize = IntSize(srcW.toInt().coerceAtMost(img.width), srcH.toInt().coerceAtMost(img.height)),
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    alpha = alpha.value,
                    filterQuality = FilterQuality.Low,
                )
            },
    )
}

@Composable
fun TopBar(
    tab: Tab,
    avatar: String?,
    avatarLetter: Char?,
    weather: Weather?,
    onTab: (Tab) -> Unit,
    onSearch: () -> Unit,
    onAccount: () -> Unit,
    onSettings: () -> Unit,
    /** Shown as an "Update" chip when a newer launcher release exists. */
    onUpdate: (() -> Unit)?,
    tabFocus: Map<Tab, FocusRequester>,
    onDown: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(TopBarHeight)
            .padding(start = PadH, end = PadH, top = 8.dp)
            // Coming up from the page always lands on the selected tab, and Down always goes back
            // into the page, instead of whatever happens to be geometrically closest.
            .focusProperties { enter = { tabFocus.getValue(tab) } }
            .focusGroup()
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionDown) {
                    onDown()
                    true
                } else {
                    false
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(avatar, avatarLetter, onAccount)
        Spacer(Modifier.width(12.dp))
        Pill {
            Tab.entries.forEach { t ->
                TabChip(t.label, selected = tab == t, onFocused = { onTab(t) }, modifier = Modifier.focusRequester(tabFocus.getValue(t)))
            }
        }
        Spacer(Modifier.width(10.dp))
        Pill {
            PillIcon(Icons.Filled.Search, "Search", onSearch)
            PillIcon(Icons.Filled.Settings, "Settings", onSettings)
        }
        if (onUpdate != null) {
            Spacer(Modifier.width(10.dp))
            Pill { UpdateChip(onUpdate) }
        }
        Spacer(Modifier.weight(1f))
        Clock()
        if (weather != null) {
            Box(
                Modifier
                    .padding(horizontal = 10.dp)
                    .width(1.5.dp)
                    .height(16.dp)
                    .background(Color.White.copy(alpha = 0.6f)),
            )
            WeatherIcon(weather, Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text("${weather.temp}°", color = Color.White.copy(alpha = 0.9f), fontSize = 17.sp)
        }
    }
}

@Composable
private fun UpdateChip(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.height(32.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Color.White.copy(alpha = 0.85f),
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFF202124),
        ),
    ) {
        Box(Modifier.fillMaxHeight().padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Text("Update", fontSize = 14.sp)
        }
    }
}

@Composable
private fun Pill(content: @Composable () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(PillBg).padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) { content() }
}

@Composable
private fun Avatar(url: String?, letter: Char?, onClick: () -> Unit) {
    val context = LocalContext.current
    Surface(
        onClick = onClick,
        modifier = Modifier.size(38.dp),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.5.dp, Color.White.copy(alpha = 0.25f)), shape = CircleShape),
            focusedBorder = Border(BorderStroke(2.5.dp, Color.White), shape = CircleShape),
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(listOf(Color(0xFF8E7CF0), Color(0xFF4FB4E8)))),
            contentAlignment = Alignment.Center,
        ) {
            if (letter != null) {
                Text(letter.toString(), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            } else {
                Icon(Icons.Filled.Person, contentDescription = "Account", tint = Color.White, modifier = Modifier.size(22.dp))
            }
            if (url != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(if (url.startsWith("/")) File(url) else url)
                        .memoryCacheKey(url + (if (url.startsWith("/")) File(url).lastModified() else ""))
                        .build(),
                    contentDescription = "Profile",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun TabChip(label: String, selected: Boolean, onFocused: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onFocused,
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocused() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        colors = ClickableSurfaceDefaults.colors(
            // Selected-but-not-focused is a faint pill; focused is solid white, so the two never look alike.
            containerColor = if (selected) Color.White.copy(alpha = 0.2f) else Color.Transparent,
            contentColor = if (selected) Color.White else Color.White.copy(alpha = 0.7f),
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFF202124),
        ),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 6.dp),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun PillIcon(icon: ImageVector, desc: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(32.dp),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Color.White.copy(alpha = 0.85f),
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFF202124),
        ),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = desc, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun Clock() {
    val context = LocalContext.current
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(5_000)
        }
    }
    // "11:45" without AM/PM, like Google TV.
    val fmt = remember {
        val pattern = if (android.text.format.DateFormat.is24HourFormat(context)) "H:mm" else "h:mm"
        java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault())
    }
    Text(fmt.format(now), color = Color.White.copy(alpha = 0.92f), fontSize = 19.sp)
}

/** Small hand-drawn weather glyphs so we don't pull in the 30MB extended icon set. */
@Composable
fun WeatherIcon(w: Weather, modifier: Modifier = Modifier) {
    val sky = w.sky()
    Canvas(modifier) {
        val s = size.minDimension
        when (sky) {
            Sky.Clear -> if (w.isDay) sun(Offset(s / 2, s / 2), s * 0.22f) else moon(s)
            Sky.PartlyCloudy -> {
                if (w.isDay) sun(Offset(s * 0.36f, s * 0.36f), s * 0.17f) else moon(s * 0.7f)
                cloud(s, Offset(s * 0.12f, s * 0.2f), Color(0xFFE8EAED))
            }
            Sky.Cloudy -> cloud(s, Offset(0f, 0f), Color(0xFFDADCE0))
            Sky.Fog -> for (i in 0..2) {
                val y = s * (0.35f + i * 0.17f)
                drawLine(Color(0xFFBDC1C6), Offset(s * 0.1f, y), Offset(s * 0.9f, y), s * 0.08f, StrokeCap.Round)
            }
            Sky.Rain -> {
                cloud(s, Offset(0f, -s * 0.12f), Color(0xFFDADCE0))
                for (i in 0..2) {
                    val x = s * (0.3f + i * 0.2f)
                    drawLine(Color(0xFF8AB4F8), Offset(x, s * 0.72f), Offset(x - s * 0.06f, s * 0.92f), s * 0.07f, StrokeCap.Round)
                }
            }
            Sky.Snow -> {
                cloud(s, Offset(0f, -s * 0.12f), Color(0xFFDADCE0))
                for (i in 0..2) drawCircle(Color.White, s * 0.05f, Offset(s * (0.3f + i * 0.2f), s * 0.82f))
            }
            Sky.Storm -> {
                cloud(s, Offset(0f, -s * 0.12f), Color(0xFFBDC1C6))
                val bolt = Path().apply {
                    moveTo(s * 0.52f, s * 0.6f); lineTo(s * 0.38f, s * 0.8f); lineTo(s * 0.5f, s * 0.8f)
                    lineTo(s * 0.42f, s * 0.98f); lineTo(s * 0.64f, s * 0.74f); lineTo(s * 0.52f, s * 0.74f); close()
                }
                drawPath(bolt, Color(0xFFFDD663))
            }
        }
    }
}

private fun DrawScope.sun(c: Offset, r: Float) {
    val color = Color(0xFFFDD663)
    drawCircle(color, r, c)
    for (i in 0 until 8) {
        val a = Math.toRadians(i * 45.0)
        val dx = Math.cos(a).toFloat()
        val dy = Math.sin(a).toFloat()
        drawLine(color, Offset(c.x + dx * r * 1.45f, c.y + dy * r * 1.45f), Offset(c.x + dx * r * 1.95f, c.y + dy * r * 1.95f), r * 0.32f, StrokeCap.Round)
    }
}

private fun DrawScope.moon(s: Float) {
    val c = Offset(s * 0.45f, s * 0.5f)
    val full = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, s * 0.32f)) }
    val bite = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(c.x + s * 0.17f, c.y - s * 0.12f), s * 0.27f)) }
    val crescent = Path().apply { op(full, bite, androidx.compose.ui.graphics.PathOperation.Difference) }
    drawPath(crescent, Color(0xFFE8EAED))
}

private fun DrawScope.cloud(s: Float, o: Offset, color: Color) {
    val base = s * 0.62f + o.y
    drawRoundRect(color, Offset(s * 0.1f + o.x * 0.3f, base - s * 0.2f), Size(s * 0.8f - o.x * 0.3f, s * 0.24f), androidx.compose.ui.geometry.CornerRadius(s * 0.12f))
    drawCircle(color, s * 0.2f, Offset(s * 0.4f + o.x * 0.5f, base - s * 0.2f))
    drawCircle(color, s * 0.15f, Offset(s * 0.64f + o.x * 0.3f, base - s * 0.14f))
}
