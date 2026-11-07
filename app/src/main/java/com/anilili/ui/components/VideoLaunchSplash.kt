package com.anilili.ui.components

import android.os.SystemClock
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.annotation.RawRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.anilili.R
import com.anilili.data.settings.ThemeAccent
import com.anilili.diagnostics.DiagnosticsLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * How long the intro may wait for its first frame. A box whose decoder never renders one is let go
 * rather than kept on a black screen. Counted up to the first frame only: a cold start on a slow box
 * can spend four seconds composing the app behind before the clip shows, and a ceiling counted from
 * launch cut the clip off mid-way there.
 */
private const val VideoLaunchSplashFirstFrameCeilingMillis = 8_000L

/**
 * How far past the clip's own length the intro may run once it is playing, for a clip that stalls.
 */
private const val VideoLaunchSplashPlaybackSlackMillis = 2_000L

/**
 * How long the hand-over to the app takes. The splash starts leaving this far before the clip's
 * end, so the cross-fade happens over the last of the motion instead of on a frozen final frame,
 * and the picture fades into the app.
 */
const val VideoLaunchSplashExitMillis = 700

/**
 * The supplied Anilife startup animation, transcoded to an efficient bundled MP4.
 *
 * Plays once from the APK, then hands over on the clip's end, on an error, on the ceilings above, or
 * on a click. The player owns a hardware decoder for as long as it lives; on the single-decoder
 * SoCs many TV boxes carry, that is the same decoder the episode player needs — so it is released
 * the moment this leaves composition, never kept around for a replay.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoLaunchSplash(
    onFinished: () -> Unit,
    @RawRes introResource: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val latestOnFinished by rememberUpdatedState(onFinished)
    val startedAt = remember { SystemClock.elapsedRealtime() }
    // Set by whichever way out comes first; the rest are ignored.
    var finishing by remember { mutableStateOf(false) }
    val finish = {
        if (!finishing) {
            finishing = true
            latestOnFinished()
        }
    }
    val player = remember(introResource) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(
                MediaItem.fromUri("android.resource://${context.packageName}/$introResource"),
            )
            // Held on its first frame until that frame is on screen. Started straight away, the audio
            // clock ran on while a cold start was still laying out the surface, and the video joined
            // two seconds in with the whole opening dropped.
            playWhenReady = false
            prepare()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                DiagnosticsLog.event("Launch intro first frame after ${SystemClock.elapsedRealtime() - startedAt}ms")
                player.play()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    DiagnosticsLog.event("Launch intro ended after ${SystemClock.elapsedRealtime() - startedAt}ms")
                    finish()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                DiagnosticsLog.event("Launch intro failed: ${error.errorCodeName} ${error.message}")
                finish()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player) {
        val started = withTimeoutOrNull(VideoLaunchSplashFirstFrameCeilingMillis) {
            while (!player.isPlaying) delay(50)
        }
        if (started == null) {
            DiagnosticsLog.event("Launch intro showed no frame within ${VideoLaunchSplashFirstFrameCeilingMillis}ms")
            finish()
            return@LaunchedEffect
        }
        // Leave once only the exit's worth of the clip remains.
        val playingSince = SystemClock.elapsedRealtime()
        while (true) {
            val duration = player.duration
            if (duration > 0 && duration - player.currentPosition <= VideoLaunchSplashExitMillis) break
            if (player.playbackState == Player.STATE_ENDED) break
            if (duration > 0 &&
                SystemClock.elapsedRealtime() - playingSince > duration + VideoLaunchSplashPlaybackSlackMillis
            ) {
                DiagnosticsLog.event("Launch intro stalled; left ${player.currentPosition}ms into the clip")
                break
            }
            delay(30)
        }
        finish()
    }
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                finish()
            },
    ) {
        // A TextureView rather than PlayerView's SurfaceView: a SurfaceView is composited outside
        // the view hierarchy, so the exit's fade and zoom never reached the picture and it cut out.
        val videoSize = minOf(minOf(maxWidth, maxHeight) * 0.62f, 360.dp)
        val videoModifier = Modifier.size(videoSize).aspectRatio(1f)
        AndroidView(
            factory = { viewContext -> TextureView(viewContext).also(player::setVideoTextureView) },
            modifier = videoModifier.align(Alignment.Center),
        )
    }
}

internal fun launchIntroResource(accent: ThemeAccent, argb: Int): Int = when (accent) {
    ThemeAccent.ANILIFE_GREEN -> R.raw.launch_intro
    ThemeAccent.MONOCHROME -> R.raw.launch_intro_monochrome
    ThemeAccent.NEON_GREEN -> R.raw.launch_intro_neon_green
    else -> {
        val red = ((argb shr 16) and 0xFF) / 255f
        val green = ((argb shr 8) and 0xFF) / 255f
        val blue = (argb and 0xFF) / 255f
        val maximum = max(red, max(green, blue))
        val minimum = min(red, min(green, blue))
        val delta = maximum - minimum
        if (maximum == 0f || delta / maximum < 0.08f) {
            R.raw.launch_intro_monochrome
        } else {
            val hue = when (maximum) {
                red -> 60f * (((green - blue) / delta) % 6f)
                green -> 60f * ((blue - red) / delta + 2f)
                else -> 60f * ((red - green) / delta + 4f)
            }.let { if (it < 0f) it + 360f else it }
            launchIntroHues.minBy { (candidate, _) ->
                min(abs(hue - candidate), 360f - abs(hue - candidate))
            }.second
        }
    }
}

private val launchIntroHues = listOf(
    8f to R.raw.launch_intro_h008,
    26f to R.raw.launch_intro_h026,
    44f to R.raw.launch_intro_h044,
    63f to R.raw.launch_intro_h063,
    80f to R.raw.launch_intro_h080,
    98f to R.raw.launch_intro,
    116f to R.raw.launch_intro_h116,
    134f to R.raw.launch_intro_h134,
    151f to R.raw.launch_intro_h151,
    170f to R.raw.launch_intro_h170,
    189f to R.raw.launch_intro_h189,
    207f to R.raw.launch_intro_h207,
    224f to R.raw.launch_intro_h224,
    242f to R.raw.launch_intro_h242,
    260f to R.raw.launch_intro_h260,
    278f to R.raw.launch_intro_h278,
    296f to R.raw.launch_intro_h296,
    314f to R.raw.launch_intro_h314,
    332f to R.raw.launch_intro_h332,
    350f to R.raw.launch_intro_h350,
)
