package com.anilili.ui.detail

import com.anilili.data.model.Media
import com.anilili.data.model.MediaTitle
import com.anilili.data.model.Category
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The television page describes one season at a time, and the chips pick which. Everything that
 * follows the chips — the title, the synopsis, the watch action, the library entry — reads
 * [DetailData.selectedSeason], so what it answers is what the page says.
 */
class DetailSelectedSeasonTest {
    @Test
    fun `an unchanged page describes the anime it was opened on`() {
        assertEquals(season(1).id, data().selectedSeason.id)
    }

    @Test
    fun `choosing a season describes that season instead`() {
        val chosen = data(selectedSeasonId = season(2).id).selectedSeason

        assertEquals(season(2).id, chosen.id)
        assertEquals("Season 2", chosen.title.preferred)
    }

    @Test
    fun `a season the chain no longer holds falls back to the page's own anime`() {
        assertEquals(season(1).id, data(selectedSeasonId = 999).selectedSeason.id)
    }

    @Test
    fun `a continuation film keeps its own selected identity`() {
        val movie = Media(id = 40, title = MediaTitle(english = "The Movie"), format = "MOVIE")
        val page = data(series = listOf(season(1), movie, season(2)))

        assertEquals(listOf(season(1).id, season(2).id, movie.id), page.seasons.map(Media::id))
        assertEquals(movie.id, page.copy(selectedSeasonId = movie.id).selectedSeason.id)
        assertEquals("Film 1", page.seasonLabels[movie.id])
    }

    @Test
    fun `two final specials retain separate selected episode one identities from every entry`() {
        val first = Media(id = 146984, format = "SPECIAL", episodes = 1)
        val second = Media(id = 162314, format = "SPECIAL", episodes = 1)
        val series = listOf(season(1), first, second)
        for (root in series) {
            val page = data(series = series).copy(info = root)
            assertEquals(series.map(Media::id).toSet(), page.seasons.map(Media::id).toSet())
            assertEquals(first.id, page.copy(selectedSeasonId = first.id).selectedSeason.id)
            assertEquals(second.id, page.copy(selectedSeasonId = second.id).selectedSeason.id)
            assertEquals("Special 1", page.seasonLabels[first.id])
            assertEquals("Special 2", page.seasonLabels[second.id])
            assertEquals(first.id, page.copy(selectedSeasonId = first.id).watchOrder.single { it.isCurrent }.media.id)
        }
    }

    private fun season(number: Int) = Media(
        id = 10 * number,
        title = MediaTitle(english = "Season $number"),
        format = "TV",
        episodes = 12,
    )

    @Test
    fun `partial franchise refresh preserves the selected special and its media identity`() {
        val special = Media(id = 146984, format = "SPECIAL", episodes = 1)
        val before = data(series = listOf(season(1), special), selectedSeasonId = special.id)
        val refreshed = before.withSeries(listOf(season(1), season(2)))
        assertEquals(special.id, refreshed.selectedSeasonId)
        assertEquals(special, refreshed.selectedSeason)
        assertEquals(setOf(10, 20, special.id), refreshed.seasons.map(Media::id).toSet())
    }

    private fun data(
        selectedSeasonId: Int = season(1).id,
        series: List<Media> = listOf(season(1), season(2)),
    ) = DetailData(
        info = season(1),
        episodes = emptyList(),
        preferredCategory = Category.SUB,
        series = series,
        selectedSeasonId = selectedSeasonId,
    )
}
