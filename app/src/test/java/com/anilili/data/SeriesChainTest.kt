package com.anilili.data

import com.anilili.data.model.Media
import com.anilili.data.model.MediaRelationConnection
import com.anilili.data.model.MediaRelationEdge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class SeriesChainTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun records(): Map<Int, Media> {
        val bytes = javaClass.getResourceAsStream("/anilife-2.0.0/franchise-capture.json")!!.use { it.readBytes() }
        return json.parseToJsonElement(bytes.decodeToString()).jsonObject.getValue("records").jsonArray
            .map { json.decodeFromJsonElement<Media>(it) }.associateBy(Media::id)
    }

    @Test fun `both reported stage entries expose each other and all AoT entries expose both finals`() = runBlocking {
        val records = records()
        for ((roots, required) in listOf(
            listOf(190327, 210482) to setOf(190327, 210482),
            listOf(16498, 146984, 162314) to setOf(16498, 146984, 162314),
        )) {
            for (id in roots) {
                val result = walkSeriesMembers(records.getValue(id), fetch = records::get)
                assertTrue("root=$id", result.members.map(Media::id).containsAll(required))
            }
        }
    }

    @Test fun `failed hydration retains a known member and never becomes complete`() = runBlocking {
        val root = connected(1, Media(id = 2, format = "SPECIAL"))
        val result = walkSeriesMembers(root) { null }
        assertEquals(setOf(1, 2), result.membership.ids.toSet())
        assertEquals(setOf(1, 2), result.members.map(Media::id).toSet())
        assertFalse(result.membership.complete)
        assertEquals(setOf("series:v2:1"), seriesCacheEntries(1, result.membership))
    }

    @Test fun `complete membership survives missing hydration and is independent of display filtering`() = runBlocking {
        val root = connected(1)
        val membership = SeriesMembership(listOf(1, 2, 3), complete = true)
        val members = hydrateSeriesMembers(root, membership.ids) { id ->
            if (id == 2) connected(2).copy(isAdult = true) else null
        }
        assertEquals(setOf(1, 2, 3), members.map(Media::id).toSet())
        assertEquals(setOf("series:v2:1", "series:v2:2", "series:v2:3"), seriesCacheEntries(1, membership))
        assertEquals(setOf(1, 3), visibleSeriesMembers(root.id, members, hideAdult = true).map(Media::id).toSet())
        assertEquals(setOf(1, 2, 3), visibleSeriesMembers(root.id, members, hideAdult = false).map(Media::id).toSet())
    }

    @Test fun `missing relations are different from an explicitly empty answer`() = runBlocking {
        val missing = walkSeriesMembers(Media(id = 1)) { null }
        val empty = walkSeriesMembers(connected(1)) { error("Known empty root needs no fetch") }
        assertFalse(missing.membership.complete)
        assertTrue(empty.membership.complete)
    }

    @Test fun `cycles and duplicate resolved media never duplicate lazy list keys`() = runBlocking {
        val root = connected(1, Media(id = 2), Media(id = 3))
        val result = walkSeriesMembers(root) { connected(2, root) }
        assertEquals(listOf(1, 2), result.members.map(Media::id))
        assertFalse(result.membership.complete)
    }

    @Test fun `seeded inverse membership is reachable even without an outbound edge`() = runBlocking {
        val records = mapOf(1 to connected(1), 2 to connected(2, Media(id = 1)))
        val fromFirst = walkSeriesMembers(records.getValue(1), listOf(1, 2), records::get)
        val fromSecond = walkSeriesMembers(records.getValue(2), listOf(1, 2), records::get)
        assertEquals(fromFirst.membership.ids.toSet(), fromSecond.membership.ids.toSet())
        assertTrue(fromFirst.membership.complete)
    }

    @Test fun `entry and depth bounds produce explicitly partial answers`() = runBlocking {
        val crowded = connected(1, *(2..30).map { Media(id = it) }.toTypedArray())
        val entries = walkSeriesMembers(crowded) { connected(it) }
        assertEquals(MAX_SERIES_ENTRIES, entries.membership.ids.size)
        assertFalse(entries.membership.complete)
        val deep = walkSeriesMembers(connected(1, Media(id = 2))) { connected(it, Media(id = it + 1)) }
        assertTrue(deep.membership.ids.size <= MAX_SERIES_DEPTH + 1)
        assertFalse(deep.membership.complete)
    }

    @Test(expected = CancellationException::class)
    fun `cancelled hydration is not swallowed as a metadata failure`() = runBlocking {
        hydrateSeriesMembers(connected(1), listOf(1, 2)) { throw CancellationException("cancelled") }
        Unit
    }

    private fun connected(id: Int, vararg neighbors: Media) = Media(
        id = id, relations = MediaRelationConnection(neighbors.map { MediaRelationEdge("SEQUEL", it) }),
    )
}
