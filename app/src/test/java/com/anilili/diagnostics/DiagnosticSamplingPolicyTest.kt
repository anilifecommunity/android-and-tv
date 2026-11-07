package com.anilili.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticSamplingPolicyTest {
    @Test
    fun `standard samples repetitive successes but preserves first twentieth and failures`() {
        assertTrue(diagnosticEventShouldPersist("standard", "INFO", "network", "http.call.complete", "success", 1))
        assertFalse(diagnosticEventShouldPersist("standard", "INFO", "network", "http.call.complete", "success", 2))
        assertTrue(diagnosticEventShouldPersist("standard", "INFO", "network", "http.call.complete", "success", 20))
        assertTrue(diagnosticEventShouldPersist("standard", "INFO", "network", "http.call.failed", "failure", 2))
        assertTrue(diagnosticEventShouldPersist("standard", "ERROR", "lifecycle", "activity.failed", null, 2))
    }

    @Test
    fun `enhanced keeps repetitive events`() {
        assertTrue(diagnosticEventShouldPersist("enhanced", "INFO", "network", "http.call.complete", "success", 2))
    }
}
