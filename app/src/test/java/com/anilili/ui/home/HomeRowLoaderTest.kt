package com.anilili.ui.home

import com.anilili.data.backgroundMetadataGate
import com.anilili.data.model.HomeSection
import com.anilili.data.model.Media
import com.anilili.data.model.MediaPage
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.withPermit
import org.junit.Assert.*
import org.junit.Test

class HomeRowLoaderTest {
    private class Feed(fetch: suspend (HomeSection, Int, Boolean) -> MediaPage) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val published = mutableListOf<Triple<HomeSection, MediaPage, Boolean>>()
        val failures = mutableListOf<HomeSection>()
        var loading = emptySet<HomeSection>()
        var now = 0L
        val loader = HomeRowLoader(scope, fetch,
            { section, page, append -> published += Triple(section, page, append) },
            { loading = it }, { section, _ -> failures += section }, { now })
        override fun close() = scope.cancel()
    }
    private fun page(number: Int = 1, more: Boolean = true) = MediaPage(listOf(Media(id = number)), more, number)

    @Test fun `hidden cached rows cannot disguise a completely empty visible feed`() {
        val data = HomeData(popular = listOf(Media(id = 1)))
        val layout = HomeLayout(listOf(HomeRow.NEWEST, HomeRow.POPULAR), hidden = setOf(HomeRow.POPULAR))
        assertFalse(layout.visible.any { it.hasContent(data, PersonalRows(), hasHistory = false) })
        assertTrue(HomeRow.POPULAR.hasContent(data, PersonalRows(), hasHistory = false))
        assertTrue(HomeRow.CONTINUE.hasContent(HomeData(), PersonalRows(), hasHistory = true))
    }

    @Test fun `a failed first page preserves seeded content and stays retryable`() = runBlocking {
        var attempts = 0
        Feed { _, number, _ -> if (++attempts == 1) error("offline") else page(number) }.use { feed ->
            feed.loader.allow(setOf(HomeSection.TRENDING))
            feed.loader.request(HomeSection.TRENDING)
            assertEquals(listOf(HomeSection.TRENDING), feed.failures)
            assertTrue(feed.published.isEmpty())
            assertTrue(feed.loading.isEmpty())
            feed.loader.request(HomeSection.TRENDING)
            assertEquals(1, feed.published.single().second.page)
        }
    }

    @Test fun `viewport loads visible rows and only one next row in custom order`() {
        val drawn = listOf(HomeRow.MOVIES, HomeRow.CONTINUE, HomeRow.TRENDING, HomeRow.NEWEST, HomeRow.UPCOMING)
        assertEquals(setOf(HomeRow.MOVIES, HomeRow.CONTINUE, HomeRow.TRENDING), homeRowsNearViewport(drawn, setOf("hero")))
        assertEquals(setOf(HomeRow.MOVIES, HomeRow.CONTINUE), homeRowsNearViewport(drawn.filterNot { it == HomeRow.TRENDING }, setOf("hero")))
        assertEquals(setOf(HomeRow.CONTINUE, HomeRow.TRENDING, HomeRow.NEWEST), homeRowsNearViewport(drawn, setOf("continue", "trending")))
        assertEquals(setOf(HomeRow.UPCOMING), homeRowsNearViewport(drawn, setOf("upcoming")))
        assertTrue(homeRowsNearViewport(emptyList(), emptySet()).isEmpty())
    }

    @Test fun `hidden rows make no request and an inactive screen cancels queued work`() = runBlocking {
        val started = mutableListOf<HomeSection>()
        Feed { section, _, _ -> started += section; awaitCancellation() }.use { feed ->
            feed.loader.allow(setOf(HomeSection.MOVIES))
            HomeSection.entries.forEach { feed.loader.request(it) }
            assertEquals(listOf(HomeSection.MOVIES), started)
            feed.loader.allow(emptySet())
            assertTrue(feed.loading.isEmpty())
            feed.loader.request(HomeSection.MOVIES)
            assertEquals(1, started.size)
            assertTrue(feed.published.isEmpty())
        }
    }

    @Test fun `recomposition cannot duplicate a page and first append still asks for page one`() = runBlocking {
        val answer = CompletableDeferred<MediaPage>()
        var count = 0
        Feed { _, number, _ -> count++; assertEquals(1, number); answer.await() }.use { feed ->
            feed.loader.allow(setOf(HomeSection.MOVIES))
            repeat(30) { feed.loader.request(HomeSection.MOVIES, append = true) }
            assertEquals(1, count)
            answer.complete(page(more = false))
            feed.loader.awaitCurrent()
            repeat(30) { feed.loader.request(HomeSection.MOVIES); feed.loader.request(HomeSection.MOVIES, append = true) }
            assertEquals(1, count)
            assertFalse(feed.published.single().third)
        }
    }

    @Test fun `rows and optional enrichment share the same two work slots`() = runBlocking {
        var running = 0
        var peak = 0
        val releases = Channel<Unit>(Channel.UNLIMITED)
        Feed { _, number, _ ->
            running++; peak = maxOf(peak, running)
            try { releases.receive(); page(number) } finally { running-- }
        }.use { feed ->
            val hero = feed.scope.launch {
                backgroundMetadataGate.withPermit {
                    running++; peak = maxOf(peak, running)
                    try { releases.receive() } finally { running-- }
                }
            }
            feed.loader.allow(HomeSection.entries.toSet())
            HomeSection.entries.forEach { feed.loader.request(it) }
            assertEquals(2, running)
            repeat(7) { releases.send(Unit) }
            feed.loader.awaitCurrent()
            hero.join()
            assertEquals(2, peak)
            assertEquals(6, feed.published.size)
            assertEquals(0, running)
        }
    }

    @Test fun `an obsolete noncooperative response cannot overwrite a refreshed row`() = runBlocking {
        val old = CompletableDeferred<Unit>()
        var count = 0
        Feed { _, _, _ ->
            if (++count == 1) withContext(NonCancellable) { old.await(); page(99) } else page(1)
        }.use { feed ->
            feed.loader.allow(setOf(HomeSection.TRENDING))
            feed.loader.request(HomeSection.TRENDING)
            feed.loader.reset()
            feed.loader.request(HomeSection.TRENDING)
            old.complete(Unit)
            feed.loader.awaitCurrent()
            assertEquals(listOf(1), feed.published.map { it.second.items.single().id })
            assertTrue(feed.loading.isEmpty())
        }
    }

    @Test fun `failed append retries the same cursor and later revalidation preserves appended pages`() = runBlocking {
        val numbers = mutableListOf<Int>()
        var fail = true
        Feed { _, number, _ ->
            numbers += number
            if (number == 2 && fail) { fail = false; error("offline") }
            page(number)
        }.use { feed ->
            feed.loader.allow(setOf(HomeSection.POPULAR))
            feed.loader.request(HomeSection.POPULAR)
            feed.loader.request(HomeSection.POPULAR, append = true)
            feed.loader.request(HomeSection.POPULAR, append = true)
            feed.now = 3 * 60 * 60 * 1000L
            feed.loader.request(HomeSection.POPULAR)
            assertEquals(listOf(1, 2, 2), numbers)
            assertEquals(listOf(false, true), feed.published.map { it.third })
            assertEquals(listOf(HomeSection.POPULAR), feed.failures)
        }
    }

    @Test fun `healthy rows survive route return and revalidate only when old`() = runBlocking {
        var count = 0
        Feed { _, number, _ -> count++; page(number) }.use { feed ->
            feed.loader.allow(setOf(HomeSection.NEWEST))
            feed.loader.request(HomeSection.NEWEST)
            feed.loader.allow(emptySet())
            feed.loader.allow(setOf(HomeSection.NEWEST))
            feed.loader.request(HomeSection.NEWEST)
            assertEquals(1, count)
            feed.now = 3 * 60 * 60 * 1000L
            feed.loader.request(HomeSection.NEWEST)
            assertEquals(2, count)
        }
    }
}
