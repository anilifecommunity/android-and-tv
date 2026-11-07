package com.anilili.ui.components

import com.anilili.R
import com.anilili.data.settings.ThemeAccent
import org.junit.Assert.assertEquals
import org.junit.Test

class LaunchIntroThemeTest {
    @Test
    fun `brand monochrome and neon use their supplied variants`() {
        assertEquals(R.raw.launch_intro, launchIntroResource(ThemeAccent.ANILIFE_GREEN, 0))
        assertEquals(R.raw.launch_intro_monochrome, launchIntroResource(ThemeAccent.MONOCHROME, 0))
        assertEquals(R.raw.launch_intro_neon_green, launchIntroResource(ThemeAccent.NEON_GREEN, 0))
    }

    @Test
    fun `custom colours choose the nearest supplied hue`() {
        assertEquals(R.raw.launch_intro_h350, launchIntroResource(ThemeAccent.CUSTOM, 0xFFD1102B.toInt()))
        assertEquals(R.raw.launch_intro_h224, launchIntroResource(ThemeAccent.CUSTOM, 0xFF3B82F6.toInt()))
        assertEquals(R.raw.launch_intro_monochrome, launchIntroResource(ThemeAccent.CUSTOM, 0xFF777777.toInt()))
    }
}
