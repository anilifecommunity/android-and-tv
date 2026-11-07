package com.anilili.playback

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.anilili.diagnostics.DiagnosticsLog
import java.io.IOException

/** Native Media3 diagnostics with compact session summaries and actionable decoder/load events. */
@OptIn(UnstableApi::class)
object PlaybackDiagnostics {
    fun attach(player: ExoPlayer) {
        val operationId = DiagnosticsLog.newOperationId("playback")
        val attachedAtMs = SystemClock.elapsedRealtime()
        val milestones = PlaybackMilestoneTracker(SystemClock::elapsedRealtime)
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                DiagnosticsLog.event(
                    "playback",
                    "video.first_frame",
                    mapOf("elapsedFromAttachMs" to (SystemClock.elapsedRealtime() - attachedAtMs)),
                    outcome = "success",
                    operationId = operationId,
                )
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                milestones.onPlaybackStateChanged(playbackState)?.let { durationMs ->
                    DiagnosticsLog.event(
                        "playback",
                        "rebuffer.completed",
                        mapOf("durationMs" to durationMs),
                        outcome = "success",
                        operationId = operationId,
                    )
                }
            }
        })
        player.addAnalyticsListener(
            PlaybackStatsListener(false) { _, stats ->
                DiagnosticsLog.event(
                    category = "playback",
                    name = "session.summary",
                    attributes = mapOf(
                        "playMs" to stats.totalPlayTimeMs,
                        "waitMs" to stats.totalWaitTimeMs,
                        "joinMs" to stats.totalJoinTimeMs,
                        "rebufferCount" to stats.totalRebufferCount,
                        "rebufferMs" to stats.totalRebufferTimeMs,
                        "seekCount" to stats.totalSeekCount,
                        "droppedFrames" to stats.totalDroppedFrames,
                        "audioUnderruns" to stats.totalAudioUnderruns,
                        "networkBytes" to stats.totalBandwidthBytes,
                        "networkTimeMs" to stats.totalBandwidthTimeMs,
                        "meanVideoHeight" to stats.meanVideoFormatHeight,
                        "meanVideoBitrate" to stats.meanVideoFormatBitrate,
                        "fatalErrors" to stats.fatalErrorCount,
                        "nonFatalErrors" to stats.nonFatalErrorCount,
                    ),
                    outcome = if (stats.fatalErrorCount > 0) "failure" else "success",
                    operationId = operationId,
                )
            },
        )
        player.addAnalyticsListener(DetailListener(operationId))
        DiagnosticsLog.event(
            "playback",
            "analytics.attached",
            outcome = "started",
            operationId = operationId,
        )
    }

    private class DetailListener(private val operationId: String) : AnalyticsListener {
        private var lastVideoFormat: String? = null
        private var lastAudioFormat: String? = null

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            DiagnosticsLog.event(
                "playback",
                "video.decoder_initialized",
                mapOf("decoder" to decoderName, "durationMs" to initializationDurationMs),
                operationId = operationId,
            )
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            DiagnosticsLog.event(
                "playback",
                "audio.decoder_initialized",
                mapOf("decoder" to decoderName, "durationMs" to initializationDurationMs),
                operationId = operationId,
            )
        }

        override fun onDownstreamFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            mediaLoadData: MediaLoadData,
        ) {
            val format = mediaLoadData.trackFormat ?: return
            val fingerprint = formatFingerprint(format)
            when (mediaLoadData.trackType) {
                C.TRACK_TYPE_VIDEO -> if (lastVideoFormat != fingerprint) {
                    lastVideoFormat = fingerprint
                    logFormat("video.format_changed", format)
                }
                C.TRACK_TYPE_AUDIO -> if (lastAudioFormat != fingerprint) {
                    lastAudioFormat = fingerprint
                    logFormat("audio.format_changed", format)
                }
            }
        }

        override fun onDroppedVideoFrames(
            eventTime: AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long,
        ) {
            if (droppedFrames <= 0) return
            DiagnosticsLog.event(
                "playback",
                "video.frames_dropped",
                mapOf("count" to droppedFrames, "windowMs" to elapsedMs),
                operationId = operationId,
            )
        }

        override fun onVideoSizeChanged(eventTime: AnalyticsListener.EventTime, videoSize: VideoSize) {
            DiagnosticsLog.event(
                "playback",
                "video.size_changed",
                mapOf(
                    "width" to videoSize.width,
                    "height" to videoSize.height,
                    "pixelRatio" to videoSize.pixelWidthHeightRatio,
                ),
                operationId = operationId,
            )
        }

        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: IOException,
            wasCanceled: Boolean,
        ) {
            DiagnosticsLog.event(
                category = "playback",
                name = "media.load_error",
                attributes = mapOf(
                    "host" to (loadEventInfo.uri.host ?: "unknown"),
                    "dataType" to mediaLoadData.dataType,
                    "trackType" to mediaLoadData.trackType,
                    "bytesLoaded" to loadEventInfo.bytesLoaded,
                    "loadDurationMs" to loadEventInfo.loadDurationMs,
                    "canceled" to wasCanceled,
                    "failureType" to error.javaClass.simpleName,
                    "failureMessage" to (error.message ?: "none"),
                ),
                outcome = if (wasCanceled) "canceled" else "failure",
                operationId = operationId,
            )
        }

        private fun logFormat(name: String, format: Format) {
            DiagnosticsLog.event(
                category = "playback",
                name = name,
                attributes = mapOf(
                    "sampleMimeType" to (format.sampleMimeType ?: "unknown"),
                    "codecs" to (format.codecs ?: "unknown"),
                    "bitrate" to format.bitrate,
                    "width" to format.width,
                    "height" to format.height,
                    "frameRate" to format.frameRate,
                    "channels" to format.channelCount,
                    "sampleRate" to format.sampleRate,
                    "language" to (format.language ?: "unknown"),
                ),
                operationId = operationId,
            )
        }

        private fun formatFingerprint(format: Format): String = listOf(
            format.sampleMimeType,
            format.codecs,
            format.bitrate,
            format.width,
            format.height,
            format.channelCount,
            format.sampleRate,
            format.language,
        ).joinToString("|")
    }
}

internal class PlaybackMilestoneTracker(private val nowMs: () -> Long) {
    private var reachedReady = false
    private var rebufferStartedAtMs: Long? = null

    fun onPlaybackStateChanged(state: Int): Long? = when (state) {
        Player.STATE_BUFFERING -> {
            if (reachedReady && rebufferStartedAtMs == null) rebufferStartedAtMs = nowMs()
            null
        }
        Player.STATE_READY -> {
            reachedReady = true
            rebufferStartedAtMs?.let { started ->
                rebufferStartedAtMs = null
                (nowMs() - started).coerceAtLeast(0L)
            }
        }
        else -> null
    }
}
