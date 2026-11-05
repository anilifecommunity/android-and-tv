package com.anilili.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.anilili.ui.adaptive.UnscaledUi

const val LaunchSplashFadeMillis = 250

/** Low-cost water-fill fallback when the supplied startup animation is disabled or cannot start. */
@Composable
fun AppLaunchSplash(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestOnFinished = rememberUpdatedState(onFinished)
    Box(
        modifier = modifier.fillMaxSize().background(Color(0xFF0B0B0F)),
        contentAlignment = Alignment.Center,
    ) {
        UnscaledUi {
            WaterFillLogoIndicator(
                size = 184.dp,
                playOnce = true,
                onFilled = { latestOnFinished.value() },
            )
        }
    }
}
