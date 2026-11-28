package com.anilili.ui.detail

import com.anilili.data.model.Media
import com.anilili.data.model.MediaTitle
import org.junit.Assert.assertEquals
import org.junit.Test

class ContinuationLabelsTest {
    @Test fun `specials and films never become numbered seasons`() {
        val members = listOf(media(1, "TV"), media(2, "SPECIAL"), media(3, "MOVIE"), media(4, "TV"), media(5, "SPECIAL"))
        val labels = continuationLabels(members) { null }
        assertEquals(listOf("Season 1", "Special 1", "Film 1", "Season 2", "Special 2"), members.map { labels[it.id] })
        assertEquals(listOf(listOf(1, 4), listOf(2, 3, 5)), continuationPickerGroups(members).map { group -> group.map(Media::id) })
    }

    @Test fun `both metadata spellings preserve the stages rather than inventing new season numbers`() {
        for (secondTitle in listOf("2nd & 3rd STAGE", "2nd - 3rd STAGE")) {
            val members = listOf(media(190327, "ONA", "1st STAGE"), media(210482, "ONA", secondTitle))
            val labels = continuationLabels(members) { null }
            assertEquals("Stage 1", labels[190327])
            assertEquals("Stage 2–3", labels[210482])
        }
    }

    private fun media(id: Int, format: String, title: String = "Show") =
        Media(id = id, format = format, title = MediaTitle(english = title))
}
