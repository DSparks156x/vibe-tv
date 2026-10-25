package com.vibe.tvlauncher.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import com.vibe.tvlauncher.data.AppEntry

val CardShape = RoundedCornerShape(10.dp)

/** Focused cards report their artwork here so the full-screen backdrop can follow focus. */
val LocalSetBackdrop = staticCompositionLocalOf<(String?) -> Unit> { {} }

/** Google TV style focusable tile: white outline + gentle zoom on focus. */
@Composable
fun FocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    shape: Shape = CardShape,
    focusedScale: Float = 1.1f,
    onFocus: (Boolean) -> Unit = {},
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.onFocusChanged { onFocus(it.isFocused) },
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusedScale),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent,
            pressedContainerColor = Color.Transparent,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.5.dp, Color.White), inset = 0.dp, shape = shape),
        ),
    ) {
        content()
    }
}

@Composable
fun RowTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(start = PadH, bottom = 8.dp),
        color = Color.White.copy(alpha = 0.9f),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
    )
}

/** 16:9 artwork card with an optional logo, progress bar and badge. */
@Composable
fun LandscapeCard(
    image: String?,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 152.dp,
    progress: Float? = null,
    logo: String? = null,
    backdrop: String? = image,
    onLongClick: (() -> Unit)? = null,
    badge: String? = null,
    caption: String? = null,
) {
    val setBackdrop = LocalSetBackdrop.current
    var focused by remember { mutableStateOf(false) }
    Column(modifier.width(width)) {
        FocusCard(
            onClick = onClick,
            onLongClick = onLongClick,
            onFocus = {
                focused = it
                if (it) setBackdrop(backdrop)
            },
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        ) {
            Box(Modifier.fillMaxSize()) {
                Text(
                    title,
                    modifier = Modifier.align(Alignment.Center).padding(8.dp),
                    color = TextSecondary,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                )
                AsyncImage(
                    model = image,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (logo != null) {
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f)),
                        ),
                    )
                    LogoOrTitle(
                        logo = logo,
                        title = title,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 8.dp, end = 8.dp, bottom = if (progress != null) 14.dp else 8.dp)
                            .fillMaxWidth(0.62f)
                            .height(26.dp),
                        fontSize = 12.sp,
                    )
                }
                if (badge != null) {
                    Text(
                        badge,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.7f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                if (progress != null && progress > 0f) {
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.35f)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progress.coerceIn(0.03f, 1f))
                                .background(Color(0xFFE53935)),
                        )
                    }
                }
            }
        }
        if (caption != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                caption,
                modifier = Modifier.alpha(if (focused) 1f else 0.7f),
                color = Color.White,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun PosterCard(
    image: String?,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 92.dp,
    backdrop: String? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val setBackdrop = LocalSetBackdrop.current
    FocusCard(
        onClick = onClick,
        onLongClick = onLongClick,
        onFocus = { if (it) setBackdrop(backdrop) },
        modifier = modifier.width(width).aspectRatio(2f / 3f),
        focusedScale = 1.12f,
    ) {
        Box(Modifier.fillMaxSize()) {
            Text(
                title,
                modifier = Modifier.align(Alignment.Center).padding(6.dp),
                color = TextSecondary,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
            )
            AsyncImage(
                model = image,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Round app icon like the Google TV "Your apps" row; the label appears under the focused one. */
@Composable
fun AppCircle(
    app: AppEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 66.dp,
    onLongClick: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val labelAlpha by animateFloatAsState(if (focused) 1f else 0f, label = "label")
    Column(modifier.width(size + 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        FocusCard(
            onClick = onClick,
            onLongClick = onLongClick,
            onFocus = { focused = it },
            shape = CircleShape,
            focusedScale = 1.12f,
            modifier = Modifier.size(size),
        ) {
            AppIconContent(app)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            app.label,
            modifier = Modifier.alpha(labelAlpha),
            color = Color.White,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AppIconContent(app: AppEntry) {
    when {
        app.round != null -> Image(
            app.round,
            contentDescription = app.label,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        app.icon != null -> Box(Modifier.fillMaxSize().background(Color(0xFFF1F3F4)), contentAlignment = Alignment.Center) {
            Image(app.icon, contentDescription = app.label, modifier = Modifier.fillMaxSize(0.68f))
        }
        app.banner != null -> Image(
            app.banner,
            contentDescription = app.label,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        else -> Box(Modifier.fillMaxSize().background(Color(0xFF5F6368)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Settings, contentDescription = app.label, tint = Color.White, modifier = Modifier.fillMaxSize(0.5f))
        }
    }
}

/** 16:9 banner tile for the Apps tab grid. */
@Composable
fun AppBanner(app: AppEntry, onClick: () -> Unit, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null) {
    FocusCard(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f),
        shape = RoundedCornerShape(10.dp),
    ) {
        if (app.banner != null) {
            Image(
                bitmap = app.banner,
                contentDescription = app.label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize().background(Color(0xFF3C4043)), contentAlignment = Alignment.Center) {
                Box(Modifier.size(44.dp).clip(CircleShape)) { AppIconContent(app) }
            }
        }
    }
}

/** Shows a title logo image, or the title in text if the logo is missing. */
@Composable
fun LogoOrTitle(
    logo: String?,
    title: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 34.sp,
    alignment: Alignment = Alignment.BottomStart,
    /** Size to the logo itself (within [modifier]'s max bounds) instead of filling the box. */
    wrap: Boolean = false,
) {
    var failed by remember(logo) { mutableStateOf(logo == null) }
    Box(modifier, contentAlignment = alignment) {
        if (failed) {
            Text(
                title,
                color = Color.White,
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = fontSize * 1.1f,
            )
        } else {
            AsyncImage(
                model = logo,
                contentDescription = title,
                contentScale = ContentScale.Fit,
                alignment = alignment,
                onState = { if (it is AsyncImagePainter.State.Error) failed = true },
                modifier = if (wrap) Modifier else Modifier.fillMaxSize(),
            )
        }
    }
}
