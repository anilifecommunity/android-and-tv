package com.anilili

import com.anilili.data.settings.MenuLanguage
import com.anilili.ui.nav.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CinemaNavigationTest {
    @Test
    fun legacyOrderStaysUnchanged() {
        assertEquals(
            listOf(Routes.HOME, Routes.SEARCH, Routes.DOWNLOADS, Routes.MORE, Routes.SETTINGS),
            phoneTabs.map(Tab::route),
        )
    }

    @Test
    fun cinemaUsesFiveClearDestinations() {
        assertEquals(
            listOf(Routes.HOME, Routes.MORE, Routes.SEARCH, Routes.SCHEDULE, Routes.SETTINGS),
            cinemaTabs.map(Tab::route),
        )
        assertEquals(
            listOf("Home", "My List", "Browse", "Schedule", "Profile"),
            cinemaTabs.map { it.navigationLabel(MenuLanguage.ENGLISH, cinema = true) },
        )
        assertTrue(cinemaTabs.all { it.navigationLabel(MenuLanguage.ENGLISH, cinema = true).isNotBlank() })
    }
}
