package com.anilili.ui.home

import com.anilili.data.model.HomeSection
import com.anilili.data.model.MediaPage
import com.anilili.data.backgroundMetadataGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.coroutineContext

/** Main-thread owner of row cursors; retired requests cannot commit into a newer feed. */
internal class HomeRowLoader(
    private val scope: CoroutineScope,
    private val fetch: suspend (HomeSection, Int, Boolean) -> MediaPage,
    private val publish: (HomeSection, MediaPage, Boolean) -> Unit,
    private val loading: (Set<HomeSection>) -> Unit,
    private val failed: (HomeSection, Exception) -> Unit,
    private val now: () -> Long,
) {
    private class Cursor {
        var page = 0
        var hasMore = true
        var fetchedAt: Long? = null
        var job: Job? = null
    }
    private val cursors = HomeSection.entries.associateWith { Cursor() }
    private var allowed = emptySet<HomeSection>()

    fun allow(sections: Set<HomeSection>) {
        allowed = sections
        cursors.filterKeys { it !in sections }.values.forEach { it.job?.cancel(); it.job = null }
        reportLoading()
    }

    fun reset() {
        cursors.values.forEach { it.job?.cancel(); it.job = null; it.page = 0; it.hasMore = true; it.fetchedAt = null }
        reportLoading()
    }

    suspend fun awaitCurrent() = cursors.values.mapNotNull { it.job }.joinAll()

    fun request(section: HomeSection, append: Boolean = false, force: Boolean = false) {
        val cursor = cursors.getValue(section)
        if (section !in allowed || cursor.job != null || (append && !cursor.hasMore)) return
        if (!append && !force && cursor.fetchedAt?.let { now() - it < HOME_ROW_REVALIDATE_MS } == true) return
        // Revalidation must not replace appended pages or the focused card within them.
        if (!append && !force && cursor.page > 1) return
        val next = if (append) cursor.page + 1 else 1
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val page = backgroundMetadataGate.withPermit { fetch(section, next, force) }
                coroutineContext.ensureActive()
                cursor.page = next
                cursor.hasMore = page.hasNextPage
                cursor.fetchedAt = now()
                publish(section, page, next > 1)
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                failed(section, e)
            } finally {
                if (cursor.job === coroutineContext[Job]) { cursor.job = null; reportLoading() }
            }
        }
        cursor.job = job
        reportLoading()
        job.start()
    }

    private fun reportLoading() = loading(cursors.filterValues { it.job != null }.keys.toSet())
}

private const val HOME_ROW_REVALIDATE_MS = 2L * 60 * 60 * 1000

internal fun homeRowsNearViewport(drawn: List<HomeRow>, visibleKeys: Set<Any>): Set<HomeRow> {
    val visible = drawn.indices.filter { drawn[it].key in visibleKeys }
    val rows = if (visible.isEmpty()) drawn.take(2)
        else drawn.subList(visible.first(), (visible.last() + 2).coerceAtMost(drawn.size))
    return rows.toSet() + listOfNotNull(HomeRow.TRENDING.takeIf { "hero" in visibleKeys && it in drawn })
}
