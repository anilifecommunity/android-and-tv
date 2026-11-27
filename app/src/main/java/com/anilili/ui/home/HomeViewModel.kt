package com.anilili.ui.home

import android.os.SystemClock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anilili.data.AppGraph
import com.anilili.data.backgroundMetadataGate
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.data.library.LibraryStore
import com.anilili.data.library.UPCOMING_AIRING_DAYS
import com.anilili.data.library.UpcomingAiring
import com.anilili.data.library.hideFromContinueUntilAired
import com.anilili.data.library.returnsToContinueAt
import com.anilili.data.model.HomeCollections
import com.anilili.data.model.HomeSection
import com.anilili.data.model.Media
import com.anilili.data.recs.RecommendationRow
import com.anilili.data.recs.Recommendations
import com.anilili.data.recs.TasteStore
import com.anilili.data.settings.SettingsStore
import com.anilili.errors.AnililiError
import kotlinx.coroutines.flow.drop
import com.anilili.errors.toFailure
import com.anilili.ui.UiState
import com.anilili.ui.rethrowIfCancellation
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.coroutineContext
import java.time.LocalDate

/** Long enough for a pull-to-refresh spinner to register as an answer rather than a flicker. */
private const val MIN_REFRESH_INDICATOR_MS = 550L

/** Cached seed plus independently loaded catalogue rails. */
data class HomeData(
    val spotlight: List<Media> = emptyList(),
    val newest: List<Media> = emptyList(),
    val popular: List<Media> = emptyList(),
    val movies: List<Media> = emptyList(),
    val topRated: List<Media> = emptyList(),
    val upcoming: List<Media> = emptyList(),
) {
    fun section(section: HomeSection): List<Media> = when (section) {
        HomeSection.NEWEST -> newest
        HomeSection.POPULAR -> popular
        HomeSection.TRENDING -> spotlight
        HomeSection.TOP_RATED -> topRated
        HomeSection.MOVIES -> movies
        HomeSection.UPCOMING -> upcoming
    }

    fun withSection(section: HomeSection, items: List<Media>): HomeData = when (section) {
        HomeSection.NEWEST -> copy(newest = items)
        HomeSection.POPULAR -> copy(popular = items)
        HomeSection.TRENDING -> copy(spotlight = items)
        HomeSection.TOP_RATED -> copy(topRated = items)
        HomeSection.MOVIES -> copy(movies = items)
        HomeSection.UPCOMING -> copy(upcoming = items)
    }
}

/**
 * Home's two personal rows: "For you" from the whole list, and "Because you watched X" from the
 * latest favourite. Both empty for a viewer with nothing watched yet, and the rows then simply
 * aren't drawn.
 */
data class PersonalRows(
    val forYou: List<Media> = emptyList(),
    val because: RecommendationRow? = null,
)

/** Recomputing is cheap but asks AniList for cards; coming back to Home more often than this reuses the rows. */
private const val PERSONAL_REFRESH_AFTER_MS = 5L * 60 * 1000

class HomeViewModel : ViewModel() {
    private val repo = AppGraph.repository

    private val _state = MutableStateFlow<UiState<HomeData>>(UiState.Loading)
    val state = _state.asStateFlow()

    private val _personal = MutableStateFlow(PersonalRows())
    val personal = _personal.asStateFlow()
    private var personalJob: Job? = null
    private var personalRequestRows = emptySet<HomeRow>()
    private val personalAt = mutableMapOf<HomeRow, Long>()
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()
    private var loadJob: Job? = null
    private var loadGeneration = 0

    /** Sections with a page in flight, so a rail can show progress and a button can disable. */
    private val _loadingMore = MutableStateFlow<Set<HomeSection>>(emptySet())
    val loadingMore = _loadingMore.asStateFlow()

    /** Sections AniList says are exhausted, so the Load More control can retire itself. */
    private val _exhausted = MutableStateFlow<Set<HomeSection>>(emptySet())
    val exhausted = _exhausted.asStateFlow()

    private val _settledRows = MutableStateFlow<Set<HomeRow>>(emptySet())
    val settledRows = _settledRows.asStateFlow()
    private var active = false
    private var ready = false
    private var layout = HomeLayout.default(AppGraph.isTv)
    private var demandedRows = emptySet<HomeRow>()
    private val rowFailures = mutableMapOf<HomeSection, Exception>()
    private val rowLoader = HomeRowLoader(
        scope = viewModelScope,
        fetch = { section, page, force ->
            kotlinx.coroutines.withTimeout(15_000) { repo.homePage(section, page, force) }
        },
        publish = { section, page, append ->
            rowFailures.remove(section)
            val current = (_state.value as? UiState.Success)?.data ?: HomeData()
            val items = (if (append) current.section(section) + page.items else page.items).distinctBy { it.id }
            _state.value = UiState.Success(current.withSection(section, items))
            _settledRows.value += HomeRow.entries.filter { it.catalogSection == section }
            _exhausted.value = if (page.hasNextPage) _exhausted.value - section else _exhausted.value + section
        },
        loading = {
            _loadingMore.value = it
            if (it.isEmpty() && rowFailures.isNotEmpty()) reportEmptyHomeFailure()
        },
        failed = { section, error ->
            rowFailures[section] = error
            _settledRows.value += HomeRow.entries.filter { it.catalogSection == section }
            DiagnosticsLog.throwable("Home row failed section=${section.name}", error)
        },
        now = SystemClock::elapsedRealtime,
    )

    private fun reportEmptyHomeFailure() {
        viewModelScope.launch {
            yield() // Allow a viewport change to finish scheduling its replacement requests.
            if (!active || !ready || _loadingMore.value.isNotEmpty() || personalJob?.isActive == true) return@launch
            val current = (_state.value as? UiState.Success)?.data ?: return@launch
            if (layout.visible.any { it.hasContent(current, _personal.value, LibraryStore.history.value.isNotEmpty()) }) return@launch
            val failure = rowFailures.entries.firstOrNull { (section, _) ->
                layout.visible.any { it in demandedRows && it.catalogSection == section }
            }?.value ?: return@launch
            _state.value = UiState.Error(failure.toFailure(AnililiError.HOME_LOAD_FAILED))
        }
    }

    fun setLayout(layout: HomeLayout) {
        this.layout = layout
        requestVisibleRows()
    }

    fun setVisibleRows(rows: Set<HomeRow>) {
        demandedRows = rows
        requestVisibleRows()
    }

    fun setActive(active: Boolean) {
        this.active = active
        requestVisibleRows()
        if (active) refreshContinueWatching() else {
            upcomingJob?.cancel()
            upcomingJob = null
        }
    }

    private fun requestVisibleRows(force: Boolean = false) {
        val demanded = if (active && ready) demandedRows.intersect(layout.visible.toSet()) else emptySet()
        val sections = demanded.mapNotNull { it.catalogSection }.toSet()
        rowLoader.allow(sections)
        sections.forEach { rowLoader.request(it, force = force) }
        if (personalRequestRows.any { it !in demanded } || demanded.none { it == HomeRow.FOR_YOU || it == HomeRow.BECAUSE }) {
            personalJob?.cancel()
            personalJob = null
        }
        loadPersonal(force)
    }

    /**
     * The airing schedule from yesterday to a fortnight out, keyed by title: which episodes air, and
     * when. See [UpcomingAiring].
     *
     * Held in the state rather than in a field the predicate reads, so the row recomposes when a
     * read lands — and so the answer cannot be a stale closure over an earlier day's schedule.
     */
    private val upcoming = MutableStateFlow<UpcomingAiring>(emptyMap())

    /** The day [upcoming] was read on, so the window is moved on when the date has. */
    private var upcomingLoadedFor: LocalDate? = null

    /**
     * A read already on its way.
     *
     * Starting the app asks twice — once from `init` and once from the foreground observer, which
     * fires as the first Activity starts — and neither has finished by the time the other arrives,
     * so the day alone does not guard it. The second read is a cache hit rather than a second
     * request, but the repository still has to be reached to find that out.
     */
    private var upcomingJob: Job? = null

    /**
     * The time the filter last judged against, in epoch seconds.
     *
     * Whether an episode is out is a question about *now*, and nothing else about a hidden row changes
     * at the moment its episode becomes watchable — so without this the row would stay away until
     * some unrelated change happened to re-run the filter. It is moved on by the wake-up below at
     * exactly that moment, and on every return to the app.
     */
    private val clock = MutableStateFlow(nowSec())

    /**
     * Continue Watching as Home draws it: the device's own rows, less the ones with nothing left to
     * play until their next episode is out.
     *
     * Only Home. My List and the account sheet keep drawing the title, which is a record of what the
     * viewer is watching rather than a row of things to press play on.
     */
    val continueWatching = combine(
        LibraryStore.history,
        upcoming,
        SettingsStore.hideAiringTomorrowFromContinue,
        clock,
    ) { history, airing, hideUntilOut, now ->
        if (!hideUntilOut) {
            history
        } else {
            history.filterNot { hideFromContinueUntilAired(it, airing, now) }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Read the upcoming schedule, once a day.
     *
     * Cheap to call on every return to Home: the day it already holds short-circuits, and the read
     * underneath is one or two published month files, shared with the Schedule tab's cache.
     */
    fun refreshContinueWatching(force: Boolean = false) {
        if (!active || HomeRow.CONTINUE !in layout.visible || LibraryStore.history.value.isEmpty() ||
            !SettingsStore.hideAiringTomorrowFromContinue.value) return
        clock.value = nowSec()
        val today = LocalDate.now()
        // A viewer who has just pulled to refresh is asking for a live answer, so a read already on
        // its way is abandoned for theirs rather than left to answer with what it cached.
        if (force) upcomingJob?.cancel()
        if (!force && upcomingLoadedFor == today) return
        if (upcomingJob?.isActive == true) return
        upcomingJob = viewModelScope.launch {
            // From yesterday: an episode that aired late last night may not be watchable yet.
            val from = today.minusDays(1)
            val entries = runCatching { repo.upcomingAirings(from, UPCOMING_AIRING_DAYS, force = force) }
                .onFailure {
                    it.rethrowIfCancellation()
                    DiagnosticsLog.throwable("Continue Watching schedule read failed from=$from", it)
                }
                .getOrNull() ?: return@launch
            coroutineContext.ensureActive()
            // Nothing is recorded until the read has landed, so a failure leaves the row judged
            // against whatever is already known rather than against an empty window that hides nothing.
            val byTitle = entries
                .mapNotNull { airing ->
                    val id = airing.media?.id ?: return@mapNotNull null
                    id to (airing.episode to airing.airingAt)
                }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, pairs) -> pairs.toMap() }
            upcomingLoadedFor = today
            clock.value = nowSec()
            upcoming.value = byTitle
            DiagnosticsLog.event(
                "Continue Watching read upcoming from=$from days=$UPCOMING_AIRING_DAYS titles=${byTitle.size} " +
                    "hidden=${LibraryStore.history.value.count { hideFromContinueUntilAired(it, byTitle) }}",
            )
        }
    }

    /** Coming back to the app is "opening" it as far as the viewer can tell. */
    private val foregroundObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            revalidate()
            // A day spent in the background is a day the schedule was read for the wrong date, and
            // the row has to be judged against the one the viewer came back to.
            refreshContinueWatching()
        }
    }

    init {
        load()
        ProcessLifecycleOwner.get().lifecycle.addObserver(foregroundObserver)
        // Wake when the first hidden row's episode becomes watchable, and judge again then. Restarted
        // whenever anything it depends on changes, including its own wake-up, which arms the next.
        viewModelScope.launch {
            combine(
                LibraryStore.history,
                upcoming,
                SettingsStore.hideAiringTomorrowFromContinue,
                clock,
            ) { history, airing, hideUntilOut, now ->
                if (hideUntilOut) history.mapNotNull { returnsToContinueAt(it, airing, now) }.minOrNull() else null
            }.collectLatest { returnsAt ->
                returnsAt ?: return@collectLatest
                delay(((returnsAt - nowSec()) * 1000).coerceAtLeast(1_000L))
                clock.value = nowSec()
            }
        }
        // The one thing coming back to the app cannot cover: a device left open across midnight,
        // which is when the fortnight the schedule covers has to move on by a day. Waiting for the
        // boundary rather than polling for it is one wake-up a day.
        viewModelScope.launch {
            while (true) {
                delay(millisUntilNextDay())
                refreshContinueWatching()
            }
        }
        // The account's list lands after Home on a fresh sign-in, and changes as the viewer rates
        // things elsewhere; either way the rows were computed from the old list.
        viewModelScope.launch {
            TasteStore.ratings.drop(1).collect {
                personalJob?.cancel()
                personalJob = null
                personalAt.clear()
                if (_state.value is UiState.Success) loadPersonal(force = true)
            }
        }
        viewModelScope.launch {
            combine(LibraryStore.history, SettingsStore.hideAiringTomorrowFromContinue) { history, hide ->
                history.map { it.anilistId } to hide
            }.collect { refreshContinueWatching() }
        }
        viewModelScope.launch {
            SettingsStore.hideAdultContent.drop(1).collect {
                _state.value = UiState.Loading
                _personal.value = PersonalRows()
                personalAt.clear()
                load()
            }
        }
    }

    override fun onCleared() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(foregroundObserver)
    }

    fun load(force: Boolean = false) {
        val generation = ++loadGeneration
        loadJob?.cancel()
        personalJob?.cancel()
        personalJob = null
        if (force) personalAt.clear()
        rowFailures.clear()
        ready = false
        rowLoader.allow(emptySet())
        rowLoader.reset()
        _settledRows.value = emptySet()
        loadJob = viewModelScope.launch {
            val operationId = DiagnosticsLog.newOperationId("home")
            DiagnosticsLog.event(
                "home",
                "load.started",
                mapOf("forced" to force),
                outcome = "started",
                operationId = operationId,
            )
            val startedAtMs = SystemClock.elapsedRealtime()
            if (force && _state.value is UiState.Success) _isRefreshing.value = true else _state.value = UiState.Loading
            try {
                val data = (_state.value as? UiState.Success)?.data ?: runCatching {
                    (repo.cachedHomeCollections() ?: repo.offlineHomeCollections())?.toHomeData()
                }.onFailure { it.rethrowIfCancellation() }.getOrNull() ?: HomeData()
                if (generation != loadGeneration) return@launch
                _exhausted.value = emptySet()
                _loadingMore.value = emptySet()
                DiagnosticsLog.event(
                    "home",
                    "load.completed",
                    mapOf(
                        "spotlightCount" to data.spotlight.size,
                        "newestCount" to data.newest.size,
                        "popularCount" to data.popular.size,
                        "movieCount" to data.movies.size,
                        "topRatedCount" to data.topRated.size,
                        "upcomingCount" to data.upcoming.size,
                        "elapsedMs" to (SystemClock.elapsedRealtime() - startedAtMs),
                    ),
                    outcome = "success",
                    operationId = operationId,
                )
                _state.value = UiState.Success(data)
                ready = true
                requestVisibleRows(force)
                rowLoader.awaitCurrent()
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                // No separate DiagnosticsLog call: the report is written by toFailure, under the
                // code the viewer is looking at, which is the only way the two cannot disagree.
                _state.value = UiState.Error(
                    e.toFailure(
                        AnililiError.HOME_LOAD_FAILED,
                        attributes = mapOf("forced" to force),
                        operationId = operationId,
                    ),
                )
            } finally {
                if (generation == loadGeneration) {
                    // A cached or fast refresh can finish in a couple of hundred milliseconds, and
                    // the spinner appears and vanishes inside one blink — which reads as "nothing
                    // happened" rather than "already up to date". Hold it long enough to be seen.
                    val shown = SystemClock.elapsedRealtime() - startedAtMs
                    if (_isRefreshing.value && shown < MIN_REFRESH_INDICATOR_MS) {
                        withContext(NonCancellable) { delay(MIN_REFRESH_INDICATOR_MS - shown) }
                    }
                    if (generation == loadGeneration) _isRefreshing.value = false
                }
                // Settled either way. Optional startup work waits on this rather than a fixed
                // delay, so it cannot land on top of the first screen still fetching.
                com.anilili.ui.FirstContent.mark()
            }
        }
    }

    /**
     * Fetch the next page of one row and append it.
     *
     * Safe to call on every scroll frame: an in-flight page and a row AniList has already declared
     * finished both return immediately. Television relies on that — its rails ask for more from a
     * scroll listener rather than from a button, so the guard is what keeps one long press on Right
     * from queuing a page per frame.
     */
    fun loadMore(section: HomeSection) {
        if (active && ready) rowLoader.request(section, append = true)
    }

    /**
     * A pull-to-refresh is the viewer asking for everything on this screen to be current, and the
     * Continue Watching row is judged against a schedule that moves: a broadcaster who reschedules
     * an episode tonight should not need the app restarted to be believed.
     */
    fun refresh() {
        load(force = true)
        refreshContinueWatching(force = true)
    }

    /**
     * Swap the rows on screen for live ones, silently: no spinner, and a failure keeps what is
     * shown. Rows the viewer has paged further into are left alone — replacing them would throw
     * away those pages, and on a television the focused card with them.
     */
    fun revalidate() {
        if (loadJob?.isActive == true) return // it revalidates when it lands
        requestVisibleRows()
    }

    /**
     * Recompute the personal rows. Failure leaves whatever rows are showing: they are a bonus on
     * top of Home, and an AniList hiccup should not make them blink out.
     */
    fun loadPersonal(force: Boolean = false) {
        val requested = demandedRows.intersect(layout.visible.toSet()).intersect(setOf(HomeRow.FOR_YOU, HomeRow.BECAUSE))
            .filter { row -> force || personalAt[row]?.let { SystemClock.elapsedRealtime() - it < PERSONAL_REFRESH_AFTER_MS } != true }.toSet()
        if (!active || requested.isEmpty()) return
        if (personalJob?.isActive == true) return
        personalRequestRows = requested
        personalJob = viewModelScope.launch {
            try {
                val rows = runCatching {
                    backgroundMetadataGate.withPermit {
                        val because = if (HomeRow.BECAUSE in requested) Recommendations.becauseYouWatched() else _personal.value.because
                        val inBecause = because?.items?.mapTo(HashSet()) { it.id }.orEmpty()
                        PersonalRows(
                            forYou = if (HomeRow.FOR_YOU in requested) Recommendations.forYou()?.items.orEmpty()
                                .filterNot { it.id in inBecause } else _personal.value.forYou,
                            because = because,
                        )
                    }
                }.onFailure {
                    it.rethrowIfCancellation()
                    DiagnosticsLog.throwable("Home personal rows failed", it)
                }.getOrNull().also {
                    coroutineContext.ensureActive()
                    _settledRows.value += requested
                    requested.forEach { row -> personalAt[row] = SystemClock.elapsedRealtime() }
                } ?: return@launch
                DiagnosticsLog.event(
                    "Home personal rows forYou=${rows.forYou.size} because=${rows.because?.seed?.id}:${rows.because?.items?.size ?: 0}",
                )
                _personal.value = rows
            } finally {
                if (personalJob === coroutineContext[Job]) {
                    personalJob = null
                    if (coroutineContext[Job]?.isActive == true) loadPersonal()
                    if (rowFailures.isNotEmpty()) reportEmptyHomeFailure()
                }
            }
        }
    }

}

private fun HomeCollections.toHomeData() = HomeData(
    spotlight = spotlight,
    newest = newest,
    popular = popular,
    movies = movies,
    topRated = topRated,
    upcoming = upcoming,
)

/**
 * How long until the device's next midnight.
 *
 * Resolved through the calendar rather than as "24 hours minus the time", because a day is not
 * always 24 hours: a clock change would otherwise wake the schedule an hour early or an hour late,
 * and the answer here decides which day the schedule window starts from. Never zero, so a device whose clock is
 * already a day ahead cannot spin.
 */
private fun millisUntilNextDay(now: Long = System.currentTimeMillis()): Long {
    val zone = java.time.ZoneId.systemDefault()
    val midnight = java.time.Instant.ofEpochMilli(now)
        .atZone(zone)
        .toLocalDate()
        .plusDays(1)
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()
    return (midnight - now).coerceAtLeast(1_000L)
}

private fun nowSec(): Long = System.currentTimeMillis() / 1000
