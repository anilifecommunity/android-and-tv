package com.anilili.diagnostics

import androidx.work.ExistingWorkPolicy
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsUploadPolicyTest {
    @Test
    fun `only the fixed production origin is accepted`() {
        assertNotNull(diagnosticsUploadOrigin("https://diagnostics.anilifecommunity.org"))
        assertNull(diagnosticsUploadOrigin("http://diagnostics.anilifecommunity.org"))
        assertNull(diagnosticsUploadOrigin("https://diagnostics.anilifecommunity.org/health"))
        assertNull(diagnosticsUploadOrigin("https://diagnostics.anilifecommunity.org?next=evil.example"))
        assertNull(diagnosticsUploadOrigin("https://diagnostics.anilifecommunity.org.evil.example"))
    }

    @Test
    fun `daily delay follows 23 and 25 hour local days`() {
        val london = ZoneId.of("Europe/London")
        val spring = ZonedDateTime.of(2026, 3, 29, 0, 0, 0, 0, london)
        val autumn = ZonedDateTime.of(2026, 10, 25, 0, 0, 0, 0, london)

        assertEquals(Duration.ofHours(23).toMillis(), nextLocalMidnightDelayMillis(spring))
        assertEquals(Duration.ofHours(25).toMillis(), nextLocalMidnightDelayMillis(autumn))
    }

    @Test
    fun `only transient HTTP statuses retry`() {
        assertTrue(diagnosticsUploadIsRetryable(408))
        assertTrue(diagnosticsUploadIsRetryable(425))
        assertTrue(diagnosticsUploadIsRetryable(429))
        assertTrue(diagnosticsUploadIsRetryable(503))
        assertFalse(diagnosticsUploadIsRetryable(400))
        assertFalse(diagnosticsUploadIsRetryable(409))
    }

    @Test
    fun `missed local day catches up without treating malformed state as due`() {
        val today = LocalDate.of(2026, 11, 6)
        assertTrue(diagnosticsUploadShouldCatchUp("2026-11-05", today))
        assertFalse(diagnosticsUploadShouldCatchUp("2026-11-06", today))
        assertFalse(diagnosticsUploadShouldCatchUp(null, today))
        assertFalse(diagnosticsUploadShouldCatchUp("broken", today))
    }

    @Test
    fun `ambiguous success retries while permanent rejection drops the staged report`() {
        assertEquals(DiagnosticsReceiptDecision.ACCEPT, diagnosticsReceiptDecision(202, true))
        assertEquals(DiagnosticsReceiptDecision.RETRY, diagnosticsReceiptDecision(202, false))
        assertEquals(DiagnosticsReceiptDecision.RETRY, diagnosticsReceiptDecision(503, false))
        assertEquals(DiagnosticsReceiptDecision.DROP, diagnosticsReceiptDecision(400, false))
    }

    @Test
    fun `revocation stops automatic or auto staged work but not an independent manual report`() {
        assertFalse(diagnosticsUploadShouldProceed(true, false, false))
        assertFalse(diagnosticsUploadShouldProceed(false, true, false))
        assertTrue(diagnosticsUploadShouldProceed(false, false, false))
        assertTrue(diagnosticsUploadShouldProceed(true, true, true))
        assertFalse(diagnosticsTriggerIsAutomatic(DiagnosticTrigger.MANUAL))
        assertTrue(diagnosticsTriggerIsAutomatic(DiagnosticTrigger.DAILY))
    }

    @Test
    fun `duplicate schedules replace one logical daily work item`() {
        assertEquals(ExistingWorkPolicy.REPLACE, diagnosticsExistingWorkPolicy)
    }

    @Test
    fun `manual and automatic uploads cannot overlap`() = runBlocking {
        val active = AtomicInteger(0)
        val maximum = AtomicInteger(0)
        (1..12).map {
            async(Dispatchers.Default) {
                withDiagnosticsUploadLock {
                    maximum.updateAndGet { current -> maxOf(current, active.incrementAndGet()) }
                    delay(2)
                    active.decrementAndGet()
                }
            }
        }.awaitAll()

        assertEquals(1, maximum.get())
    }
}
