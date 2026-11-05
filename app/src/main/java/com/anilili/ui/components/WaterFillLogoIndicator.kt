package com.anilili.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.anilili.R
import kotlin.math.roundToInt

private const val WaterFillDurationMillis = 1_600

/** Low-cost reveal of the supplied logo for full-screen loading and the static startup fallback. */
@Composable
fun WaterFillLogoIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    playOnce: Boolean = false,
    onFilled: () -> Unit = {},
) {
    val progress = remember { Animatable(0f) }
    val latestOnFilled = rememberUpdatedState(onFilled)
    LaunchedEffect(playOnce) {
        if (playOnce) {
            progress.animateTo(1f, tween(WaterFillDurationMillis, easing = LinearEasing))
            latestOnFilled.value()
        } else {
            while (true) {
                progress.animateTo(1f, tween(WaterFillDurationMillis, easing = LinearEasing))
                progress.animateTo(0f, tween(WaterFillDurationMillis, easing = LinearEasing))
            }
        }
    }
    val logo = ImageBitmap.imageResource(R.drawable.anilili_launcher)
    androidx.compose.foundation.layout.Spacer(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = "Loading" }
            .drawWithCache {
                val target = IntSize(this.size.width.roundToInt(), this.size.height.roundToInt())
                onDrawBehind {
                    drawImage(logo, dstSize = target, alpha = 0.18f)
                    clipRect(top = this.size.height * (1f - progress.value)) {
                        drawImage(logo, dstSize = target)
                    }
                }
            },
    )
}
