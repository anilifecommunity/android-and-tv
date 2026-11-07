package com.anilili.playback

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackMilestoneTrackerTest {
    @Test
    fun `initial buffering is join time and later buffering reports a rebuffer`() {
        var now = 100L
        val tracker = PlaybackMilestoneTracker { now }

        assertNull(tracker.onPlaybackStateChanged(Player.STATE_BUFFERING))
        assertNull(tracker.onPlaybackStateChanged(Player.STATE_READY))
        now = 250L
        assertNull(tracker.onPlaybackStateChanged(Player.STATE_BUFFERING))
        now = 430L
        assertEquals(180L, tracker.onPlaybackStateChanged(Player.STATE_READY))
    }
}
