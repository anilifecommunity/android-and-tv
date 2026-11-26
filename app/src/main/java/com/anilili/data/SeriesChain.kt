package com.anilili.data

import com.anilili.data.model.Media
import com.anilili.data.model.MediaTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable

internal const val MAX_SERIES_ENTRIES = 16
internal const val MAX_SERIES_DEPTH = 8
internal const val SERIES_CACHE_PREFIX = "series:v2:"

@Serializable
internal data class SeriesMembership(val ids: List<Int>, val complete: Boolean)

internal data class LoadedSeries(val membership: SeriesMembership, val members: List<Media>)

private suspend fun fetchSeriesMember(id: Int, fetch: suspend (Int) -> Media?): Media? = try {
    fetch(id)
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    null
}

internal suspend fun hydrateSeriesMembers(
    root: Media,
    ids: List<Int>,
    fetch: suspend (Int) -> Media?,
): List<Media> = coroutineScope {
    val cards = root.seasonNeighbors().associateBy(Media::id)
    (listOf(root.id) + ids).filter { it > 0 }.distinct().take(MAX_SERIES_ENTRIES).map { id ->
        async {
            if (id == root.id) root else fetchSeriesMember(id, fetch) ?: cards[id]
                ?: Media(id = id, title = MediaTitle(romaji = "Metadata unavailable"))
        }
    }.awaitAll().distinctBy(Media::id).sortedWith(SERIES_AIRING_ORDER)
}

internal suspend fun walkSeriesMembers(
    root: Media,
    seedIds: List<Int> = emptyList(),
    fetch: suspend (Int) -> Media?,
): LoadedSeries = coroutineScope {
    val allSeeds = (listOf(root.id) + seedIds).filter { it > 0 }.distinct()
    val found = linkedMapOf(root.id to root)
    val cards = root.seasonNeighbors().associateBy(Media::id)
    allSeeds.take(MAX_SERIES_ENTRIES).forEach { id ->
        if (id != root.id) found[id] = cards[id] ?: Media(id = id, title = MediaTitle(romaji = "Metadata unavailable"))
    }
    val expanded = mutableSetOf<Int>()
    var complete = allSeeds.size <= MAX_SERIES_ENTRIES
    var frontier = found.keys.toList()
    var depth = 0
    while (frontier.isNotEmpty() && depth < MAX_SERIES_DEPTH) {
        val batch = frontier.filter { expanded.add(it) }
        val detailed = batch.map { id ->
            async {
                id to if (id == root.id && root.relations.edges != null) root else fetchSeriesMember(id, fetch)
            }
        }.awaitAll()
        val next = mutableSetOf<Int>()
        detailed.forEach { (id, hydrated) ->
            val media = hydrated ?: found.getValue(id)
            found[id] = media
            if (media.relations.edges == null || media.id != id) complete = false
            media.seasonNeighbors().forEach neighbor@{ neighbor ->
                if (neighbor.id !in found) {
                    if (found.size >= MAX_SERIES_ENTRIES) {
                        complete = false
                        return@neighbor
                    }
                    found[neighbor.id] = neighbor
                }
                if (neighbor.id !in expanded) next += neighbor.id
            }
        }
        frontier = next.toList()
        depth++
    }
    if (frontier.isNotEmpty()) complete = false
    LoadedSeries(
        membership = SeriesMembership(found.keys.toList(), complete),
        members = found.values.distinctBy(Media::id).sortedWith(SERIES_AIRING_ORDER),
    )
}

internal fun seriesCacheEntries(rootId: Int, membership: SeriesMembership): Set<String> =
    (if (membership.complete) membership.ids else listOf(rootId))
        .mapTo(linkedSetOf()) { "$SERIES_CACHE_PREFIX$it" }

internal fun visibleSeriesMembers(rootId: Int, members: List<Media>, hideAdult: Boolean): List<Media> =
    members.filterNot { hideAdult && it.isAdult && it.id != rootId }
