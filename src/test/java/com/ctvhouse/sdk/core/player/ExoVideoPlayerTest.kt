package com.ctvhouse.sdk.core.player

import android.content.Context
import android.view.SurfaceView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** How the sound policy given to the constructor reaches the ad player. */
class ExoVideoPlayerTest {

    private val exo = mock<ExoPlayer>()
    private val context = mock<Context>().also { whenever(it.applicationContext).thenReturn(it) }

    @Test
    fun soundEnabled_keepsVolumeAndRequestsAudioFocus() {
        play(soundEnabled = true)

        verify(exo).setAudioAttributes(any(), eq(true))
        verify(exo, never()).setVolume(any())
    }

    @Test
    fun soundDisabled_mutesAndLeavesOtherAppsAlone() {
        play(soundEnabled = false)

        verify(exo).setAudioAttributes(any(), eq(false))
        verify(exo).setVolume(0f)
    }

    @Test
    fun mutingHappensBeforeTheListenerSoTheStartingStateIsNotAMuteEvent() {
        val muteEvents = mutableListOf<Boolean>()
        play(soundEnabled = false, onMutedChanged = { muteEvents.add(it) })

        inOrder(exo) {
            verify(exo).setVolume(0f)
            verify(exo).addListener(any())
        }
        assertEquals(emptyList<Boolean>(), muteEvents)
    }

    @Test
    fun positionIsNeverNegative() {
        whenever(exo.currentPosition).thenReturn(-1L)
        val player = play(soundEnabled = true)

        assertEquals(0L, player.positionMs())
    }

    @Test
    fun setMuted_writesVolume() {
        val player = play(soundEnabled = true)

        player.setMuted(true)
        verify(exo).setVolume(0f)

        player.setMuted(false)
        verify(exo).setVolume(1f)
    }

    @Test
    fun setMuted_notifiesEvenIfExoDoesNotEmitVolume() {
        whenever(exo.volume).thenReturn(1f)
        val events = mutableListOf<Boolean>()
        val player = play(soundEnabled = true, onMutedChanged = { events.add(it) })

        player.setMuted(true)
        player.setMuted(true)
        player.setMuted(false)

        assertEquals(listOf(true, false), events)
    }

    @Test
    fun setPlaying_writesPlayWhenReady() {
        val player = play(soundEnabled = true)

        player.setPlaying(false)
        verify(exo).setPlayWhenReady(false)

        player.setPlaying(true)
        verify(exo, times(2)).setPlayWhenReady(true)
    }

    @Test
    fun firstFrame_notifiesReadyOnce() {
        val ready = mutableListOf<Unit>()
        val listener = playAndCapture(surfaceView = mock(), onReady = { ready.add(Unit) })

        listener.onPlaybackStateChanged(Player.STATE_READY)
        assertEquals("video must wait for the first composed frame", 0, ready.size)

        listener.onRenderedFirstFrame()
        listener.onRenderedFirstFrame()
        assertEquals(1, ready.size)
    }

    @Test
    fun audioReady_notifiesWithoutASurface() {
        val ready = mutableListOf<Unit>()
        val listener = playAndCapture(surfaceView = null, onReady = { ready.add(Unit) })

        listener.onPlaybackStateChanged(Player.STATE_READY)
        listener.onPlaybackStateChanged(Player.STATE_READY)
        assertEquals(1, ready.size)
    }

    @Test
    fun aSilentSlotIgnoresAnUnmuteFromTheChrome() {
        val player = play(soundEnabled = false)

        player.setMuted(false)

        verify(exo, never()).setVolume(1f)
    }

    @Test
    fun turningSoundBackOnReachesTheCreativeAlreadyOnScreen() {
        val player = play(soundEnabled = false)

        player.setSoundEnabled(true)

        verify(exo).setVolume(1f)
        verify(exo).setAudioAttributes(any(), eq(true))
    }

    @Test
    fun releaseDropsTheSurfaceAndThePlayer() {
        val player = play(soundEnabled = true)

        player.release()

        verify(exo).clearVideoSurface()
        verify(exo).release()
        assertEquals(0L, player.positionMs())
    }

    private fun play(
        soundEnabled: Boolean,
        onMutedChanged: ((Boolean) -> Unit)? = null,
        surfaceView: SurfaceView? = null,
        onReady: (() -> Unit)? = null,
    ): ExoVideoPlayer {
        val player = ExoVideoPlayer(soundEnabled = soundEnabled, playerFactory = { exo })
        player.play(
            context = context,
            url = "https://example.com/ad.mp4",
            surfaceView = surfaceView,
            onEnded = {},
            onError = {},
            onMutedChanged = onMutedChanged,
            onReady = onReady,
        )
        return player
    }

    private fun playAndCapture(
        surfaceView: SurfaceView?,
        onReady: () -> Unit,
    ): Player.Listener {
        play(soundEnabled = true, surfaceView = surfaceView, onReady = onReady)
        val listener = argumentCaptor<Player.Listener>()
        verify(exo).addListener(listener.capture())
        return listener.firstValue
    }
}
