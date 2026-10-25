package com.nuvio.tv.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.nuvio.tv.ui.theme.NuvioMotion
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import com.nuvio.tv.ui.theme.NuvioComponents

val LocalLandscapePosterMode = staticCompositionLocalOf { false }
val LocalAlwaysBackdropWithLogo = staticCompositionLocalOf { false }

@Immutable
data class PosterCardStyle(
    val width: Dp = NuvioComponents.tokens.posterCard.width,
    val height: Dp = NuvioComponents.tokens.posterCard.height,
    val cornerRadius: Dp = NuvioComponents.tokens.posterCard.cornerRadius,
    val focusedBorderWidth: Dp = NuvioComponents.tokens.posterCard.focusedBorderWidth,
    val focusedScale: Float = NuvioComponents.tokens.posterCard.focusedScale
) {
    val aspectRatio: Float
        get() = width.value / height.value
}

object PosterCardDefaults {
    val Style = PosterCardStyle()
}

/**
 * Vibe fork: focus zoom drawn with our own layer. tv-material's Card scale was not visibly applied
 * to the home poster cards, while the same graphicsLayer approach works on Continue Watching.
 * The animated value is only read inside the layer block, so it never recomposes the card.
 */
@Composable
fun Modifier.nuvioFocusScale(focused: Boolean, focusedScale: Float): Modifier {
    if (focusedScale == 1f) return this
    val scale = animateFloatAsState(
        targetValue = if (focused) focusedScale else 1f,
        animationSpec = NuvioMotion.focusTween(),
        label = "nuvioFocusScale"
    )
    return this.graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}
