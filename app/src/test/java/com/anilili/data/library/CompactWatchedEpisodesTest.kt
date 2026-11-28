package com.anilili.data.library

import com.anilili.ui.components.episodeMarkedWatched
import com.anilili.ui.components.episodeWatchFraction
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CompactWatchedEpisodesTest {
    private val json = Json { encodeDefaults = true }
    private fun row() = HistoryEntry(42, "Long series", null, 1_000_000_000.5, provider = "auto", category = "sub")

    @Test(timeout = 5000) fun `huge legacy inference remains bounded across serialization and consumers`() {
        val wire = json.encodeToJsonElement(row()).jsonObject.let { JsonObject(it - "watchedEpisodes") }
        val raw = JsonArray(listOf(wire)).toString()
        val entry = seedLegacyWatchedEpisodes(json.decodeFromString(raw), raw).single()
        assertTrue(entry.watchedEpisodes.isEmpty())
        assertEquals(1_000_000_000, entry.watchedEpisodeCount)
        assertEquals(1_000_000_000.0, entry.furthestWatchedEpisode()!!, 0.0)
        assertTrue(entry.isEpisodeWatched(1.0)); assertTrue(entry.isEpisodeWatched(1_000_000_000.0))
        assertFalse(entry.isEpisodeWatched(1.5)); assertFalse(entry.isEpisodeWatched(entry.episodeNumber))
        assertTrue(episodeMarkedWatched(entry, 999_999_999.0))
        assertEquals(1f, episodeWatchFraction(entry, 999_999_999.0))
        assertNull(entry.resumeFor(999_999_999.0))
        assertTrue(entry.hasWatchedEveryEpisodeThrough(1_000_000_000))
        assertFalse(entry.hasWatchedEveryEpisodeThrough(1_000_000_001))
        val encoded = json.encodeToString(entry)
        assertTrue(encoded.length < 1000)
        assertEquals(entry, json.decodeFromString<HistoryEntry>(encoded))
    }

    @Test fun `explicit corrections and fractional marks survive range updates and rewatch`() {
        val original = row().copy(inferredWatchedBefore = 1_000_000_001.0)
        val unmarked = historyAfterEpisodeToggled(original, 1_000_000_000.0, false)!!
        assertFalse(unmarked.isEpisodeWatched(1_000_000_000.0))
        assertEquals(999_999_999.0, unmarked.furthestWatchedEpisode()!!, 0.0)
        assertEquals(999_999_999, unmarked.watchedEpisodeCount)
        assertFalse(unmarked.hasWatchedEveryEpisodeThrough(1_000_000_000))
        assertFalse(episodeMarkedWatched(unmarked, 1_000_000_000.0))
        val resumed = unmarked.withEpisodeProgress(1_000_000_000.0, 500, 1000)
        assertEquals(EpisodeResume(500, 1000), resumed.resumeFor(1_000_000_000.0))
        val fractional = historyAfterEpisodeToggled(resumed, 2.5, true)!!
        assertTrue(fractional.isEpisodeWatched(2.5))
        assertEquals(1_000_000_000, fractional.watchedEpisodeCount)
        val marked = historyAfterEpisodeToggled(fractional, 1_000_000_000.0, true)!!
        assertTrue(marked.unwatchedEpisodes.isEmpty())
        assertTrue(marked.hasWatchedEveryEpisodeThrough(1_000_000_000))
        assertEquals(1_000_000_001, marked.watchedEpisodeCount)
        val backOnEpisode = marked.copy(episodeNumber = 3.0)
        assertFalse(shouldRecordWatchActivity(backOnEpisode, 3.0))
        val restored = json.decodeFromString<HistoryEntry>(json.encodeToString(unmarked))
        assertFalse(restored.isEpisodeWatched(1_000_000_000.0))
        assertTrue(restored.isEpisodeWatched(999_999_999.0))
    }

    @Test(timeout = 5000) fun `tracker count uses bounded representation without integer overflow`() {
        val original = row().copy(episodeNumber = 3.0)
        val updated = historyAfterRemoteProgress(original, Int.MAX_VALUE - 1, 100, null)!!
        assertEquals(Int.MAX_VALUE.toDouble(), updated.episodeNumber, 0.0)
        assertTrue(updated.watchedEpisodes.isEmpty())
        assertEquals(Int.MAX_VALUE - 1, updated.watchedEpisodeCount)
        assertTrue(updated.hasWatchedEveryEpisodeThrough(Int.MAX_VALUE - 1))
        val full = original.withWatchedThrough(Int.MAX_VALUE)
        assertEquals(Int.MAX_VALUE, full.watchedEpisodeCount)
        assertTrue(full.hasWatchedEveryEpisodeThrough(Int.MAX_VALUE))
        assertNull(remoteRowEpisode(Int.MAX_VALUE, null, null))
        assertNull(remoteRowEpisode(Int.MAX_VALUE, Int.MAX_VALUE, null))
        assertEquals(500, remoteRowEpisode(Int.MAX_VALUE, null, 500))
        val corrected = historyAfterEpisodeToggled(full, 8.0, false)!!
        assertFalse(corrected.isEpisodeWatched(8.0))
        assertTrue(corrected.withWatchedThrough(8).isEpisodeWatched(8.0))
        assertTrue(corrected.withWatchedThrough(7).unwatchedEpisodes.contains(8.0))
    }

    @Test fun `explicit marks override corrections and range queries count only whole episodes`() {
        val entry = row().copy(inferredWatchedBefore = 5.0, watchedEpisodes = setOf(2.0, 5.0, 5.5), unwatchedEpisodes = setOf(2.0, 4.0))
        assertTrue(entry.isEpisodeWatched(2.0)); assertFalse(entry.isEpisodeWatched(4.0))
        assertEquals(5, entry.watchedEpisodeCount)
        assertTrue(entry.hasWatchedEveryEpisodeThrough(3)); assertFalse(entry.hasWatchedEveryEpisodeThrough(5))
        assertEquals(5.5, entry.furthestWatchedEpisode()!!, 0.0)
        assertThrows(IllegalArgumentException::class.java) { entry.copy(inferredWatchedBefore = Double.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { entry.copy(inferredWatchedBefore = 1.0) }
        val huge = row().copy(inferredWatchedBefore = 1e20)
        val last = huge.furthestWatchedEpisode()!!
        assertTrue(last < 1e20 && huge.isEpisodeWatched(last))
        val corrected = historyAfterEpisodeToggled(huge, last, false)!!
        assertTrue(corrected.furthestWatchedEpisode()!! < last)
        assertEquals(Int.MAX_VALUE, corrected.watchedEpisodeCount)
    }
}
