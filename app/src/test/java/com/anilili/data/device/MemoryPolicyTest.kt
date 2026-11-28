package com.anilili.data.device

import org.junit.Assert.*
import org.junit.Test

class MemoryPolicyTest {
    private val gib = 1024L * 1024 * 1024

    @Test fun `physical memory heap and low ram signals independently constrain the budget`() {
        assertTrue(useConservativeMemoryBudgets(true, 512, 8 * gib))
        assertTrue(useConservativeMemoryBudgets(false, 256, 8 * gib))
        assertTrue(useConservativeMemoryBudgets(false, 512, 2 * gib))
        assertTrue(useConservativeMemoryBudgets(false, null, 8 * gib))
        assertTrue(useConservativeMemoryBudgets(false, 512, null))
        assertTrue(useConservativeMemoryBudgets(false, 512, 0))
        assertFalse(useConservativeMemoryBudgets(false, 512, 4 * gib))
    }

    @Test fun `conservative image cache never exceeds sixteen megabytes or the smaller heap fraction`() {
        for (heap in listOf(32L, 128L, 512L, 4096L).map { it * 1024 * 1024 }) {
            val bytes = imageMemoryCacheBytes(heap, isTv = true, conservative = true)
            assertTrue(bytes <= 16 * 1024 * 1024)
            assertTrue(bytes <= (heap * 0.06).toLong())
            assertTrue(bytes < imageMemoryCacheBytes(heap, isTv = true, conservative = false))
        }
        assertEquals(1, imageMemoryCacheBytes(0, isTv = true, conservative = true))
    }

    @Test fun `low memory television defaults to unblurred while preserving explicit choices`() {
        assertFalse(effectiveBackdropBlur(null, isTv = true, conservative = true))
        assertTrue(effectiveBackdropBlur(null, isTv = true, conservative = false))
        assertTrue(effectiveBackdropBlur(null, isTv = false, conservative = true))
        assertTrue(effectiveBackdropBlur(true, isTv = true, conservative = true))
        assertFalse(effectiveBackdropBlur(false, isTv = false, conservative = false))
    }
}
