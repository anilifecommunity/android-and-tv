package com.anilili.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLayoutModeTest {
    @Test
    fun storedValueDefaultsToCinemaWhilePreservingLegacy() {
        assertEquals(AppLayoutMode.CINEMA, AppLayoutMode.fromStored(null))
        assertEquals(AppLayoutMode.CINEMA, AppLayoutMode.fromStored("future-mode"))
        assertEquals(AppLayoutMode.CINEMA, AppLayoutMode.fromStored("cinema"))
        assertEquals(AppLayoutMode.LEGACY, AppLayoutMode.fromStored("legacy"))
    }

    @Test
    fun diagnosticDetailDefaultsToStandard() {
        assertEquals(DiagnosticDetail.STANDARD, DiagnosticDetail.fromStored(null))
        assertEquals(DiagnosticDetail.STANDARD, DiagnosticDetail.fromStored("future-mode"))
        assertEquals(DiagnosticDetail.ENHANCED, DiagnosticDetail.fromStored("enhanced"))
    }
}
