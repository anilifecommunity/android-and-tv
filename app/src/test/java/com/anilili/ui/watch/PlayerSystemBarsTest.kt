package com.anilili.ui.watch

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSystemBarsTest {
    @Test
    fun controlsBringTheStatusBarWithThem() {
        assertEquals(
            PlayerBars.STATUS_ONLY,
            playerBarsFor(
                immersive = true,
                controlsVisible = true,
                isTv = false,
                statusBarOverPlayer = true,
            ),
        )
        assertEquals(
            PlayerBars.NONE,
            playerBarsFor(
                immersive = true,
                controlsVisible = false,
                isTv = false,
                statusBarOverPlayer = true,
            ),
        )
    }

    @Test
    fun turningTheSettingOffLeavesThePictureAlone() {
        assertEquals(
            PlayerBars.NONE,
            playerBarsFor(
                immersive = true,
                controlsVisible = true,
                isTv = false,
                statusBarOverPlayer = false,
            ),
        )
    }

    @Test
    fun aTelevisionStaysImmersiveWhateverTheSettingSays() {
        assertEquals(
            PlayerBars.NONE,
            playerBarsFor(
                immersive = true,
                controlsVisible = true,
                isTv = true,
                statusBarOverPlayer = true,
            ),
        )
    }

    @Test
    fun leavingFullscreenHandsEveryBarBack() {
        assertEquals(
            PlayerBars.ALL,
            playerBarsFor(
                immersive = false,
                controlsVisible = false,
                isTv = false,
                statusBarOverPlayer = false,
            ),
        )
    }

    @Test
    fun tvSurfaceLeavesTheDisplayWhenTheActivityStops() {
        assertFalse(tvPlayerSurfaceAttached(Lifecycle.Event.ON_STOP)!!)
        assertTrue(tvPlayerSurfaceAttached(Lifecycle.Event.ON_START)!!)
        assertTrue(tvPlayerSurfaceAttached(Lifecycle.Event.ON_RESUME)!!)
        assertNull(tvPlayerSurfaceAttached(Lifecycle.Event.ON_PAUSE))
    }
}
