@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.anilili.ui.home

import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import com.anilili.data.model.CoverImage
import com.anilili.data.model.Media
import com.anilili.ui.adaptive.TvFocusTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TvHomeFocusPolicyTest {
    @Test
    fun savedIdentitySurvivesAppendAndReorderAndMissingItemsFallBackSafely() {
        assertEquals(1, rememberedTvRailIndex(listOf(11, 22, 33, 44), 22))
        assertEquals(2, rememberedTvRailIndex(listOf(44, 33, 22, 11), 22))
        assertEquals(0, rememberedTvRailIndex(listOf(11, 33), 22))
        assertEquals(0, rememberedTvRailIndex(emptyList(), 22))
        assertEquals(0, rememberedTvRailIndex(listOf(11), null))
    }

    @Test
    fun retainedTargetsFollowIdentityAndReleaseRemovedCards() {
        val cache = mutableMapOf<Int, TvFocusTarget>()
        val first = reconcileTvRailTargets(listOf(11, 22), cache)
        first[1].onAttach()
        val appended = reconcileTvRailTargets(listOf(11, 22, 33), cache)
        assertSame(first[1], appended[1])
        val reordered = reconcileTvRailTargets(listOf(33, 22), cache)
        assertSame(first[1], reordered[1])
        assertEquals(setOf(22, 33), cache.keys)
        assertSame(first[1].requester, rememberedTvRailRequester(reordered, 1))
        reconcileTvRailTargets(emptyList(), cache)
        assertEquals(0, cache.size)
    }

    @Test
    fun catalogRailsUsePortraitCoverArtwork() {
        val media = Media(
            id = 1,
            coverImage = CoverImage(large = "poster"),
            bannerImage = "banner",
        )

        assertEquals("poster", tvMediaCardArtwork(media))
        assertEquals(2f / 3f, TV_MEDIA_CARD_ASPECT_RATIO)
    }

    private val tvDefault = HomeLayout.default(isTv = true)

    @Test
    fun upcomingIsTheBottomBoundaryWhenEveryRailExists() {
        assertEquals(HomeRow.UPCOMING, tvDefault.present { true }.lastOrNull())
    }

    @Test
    fun boundaryFallsBackToTheLastRailWithContent() {
        val drawn = setOf(HomeRow.CONTINUE, HomeRow.TRENDING, HomeRow.POPULAR)
        assertEquals(HomeRow.POPULAR, tvDefault.present { it in drawn }.lastOrNull())
    }

    @Test
    fun historyCanBeTheOnlyBottomBoundary() {
        assertEquals(HomeRow.CONTINUE, tvDefault.present { it == HomeRow.CONTINUE }.lastOrNull())
    }

    @Test
    fun noRowsMeansNoBoundaries() {
        assertNull(tvDefault.present { false }.lastOrNull())
        assertNull(tvDefault.present { false }.firstOrNull())
    }

    @Test
    fun boundariesFollowTheViewersOrderAndSkipHiddenRows() {
        val custom = tvDefault.move(HomeRow.MOVIES, 0).withHidden(HomeRow.UPCOMING, true)
        val drawn = custom.present { true }
        assertEquals(HomeRow.MOVIES, drawn.first())
        assertEquals(HomeRow.TOP_RATED, drawn.last())
    }

    @Test
    fun recycledRememberedCardFallsBackToSpatialFocus() {
        val recycled = TvFocusTarget()

        assertSame(
            FocusRequester.Default,
            rememberedTvRailRequester(listOf(recycled), index = 0),
        )
    }

    @Test
    fun attachedRememberedCardCanBeRestored() {
        val attached = TvFocusTarget().also(TvFocusTarget::onAttach)

        assertSame(
            attached.requester,
            rememberedTvRailRequester(listOf(attached), index = 0),
        )
    }

    @Test
    fun verticalPressLandsOnTheCardTheViewerIsLookingAt() {
        val attached = TvFocusTarget().also(TvFocusTarget::onAttach)

        assertSame(
            FocusRequester.Default,
            tvRailEnterRequester(FocusDirection.Up, listOf(attached), index = 0),
        )
        assertSame(
            FocusRequester.Default,
            tvRailEnterRequester(FocusDirection.Down, listOf(attached), index = 0),
        )
    }

    @Test
    fun arrivalWithNoDirectionRestoresTheRememberedCard() {
        val attached = TvFocusTarget().also(TvFocusTarget::onAttach)

        assertSame(
            attached.requester,
            tvRailEnterRequester(FocusDirection.Enter, listOf(attached), index = 0),
        )
        assertSame(
            attached.requester,
            tvRailEnterRequester(FocusDirection.Right, listOf(attached), index = 0),
        )
    }
}
