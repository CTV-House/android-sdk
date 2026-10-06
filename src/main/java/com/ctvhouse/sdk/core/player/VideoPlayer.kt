package com.ctvhouse.sdk.core.player

import android.content.Context
import android.view.TextureView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Linear creative player (video **or** audio). Host content player stays separate.
 * [textureView] may be null for audio-only creatives.
 */
internal interface VideoPlayer {
    fun play(
        context: Context,
        url: String,
        textureView: TextureView?,
        onEnded: () -> Unit,
        onError: (String) -> Unit,
        onMutedChanged: ((Boolean) -> Unit)? = null,
        onPlayingChanged: ((Boolean) -> Unit)? = null,
        onReady: (() -> Unit)? = null,
    )

    /** Creative playback position, or 0 before playback starts. Main thread. */
    fun positionMs(): Long

    fun isMuted(): Boolean
    fun isPlaying(): Boolean
    fun setMuted(muted: Boolean)
    fun setPlaying(playing: Boolean)
    fun setSoundEnabled(enabled: Boolean) {}

    fun release()
}

/**
 * Stands in when Media3 is not on the host classpath. Every show then fails with a reason the
 * integrator can act on, instead of a `NoClassDefFoundError` thrown out of a library constructor.
 */
internal object UnavailablePlayer : VideoPlayer {
    const val MESSAGE = "androidx.media3 (media3-exoplayer) is missing from the host app"

    override fun play(
        context: Context,
        url: String,
        textureView: TextureView?,
        onEnded: () -> Unit,
        onError: (String) -> Unit,
        onMutedChanged: ((Boolean) -> Unit)?,
        onPlayingChanged: ((Boolean) -> Unit)?,
        onReady: (() -> Unit)?,
    ) = onError(MESSAGE)

    override fun positionMs(): Long = 0L
    override fun isMuted(): Boolean = false
    override fun isPlaying(): Boolean = false
    override fun setMuted(muted: Boolean) = Unit
    override fun setPlaying(playing: Boolean) = Unit
    override fun release() = Unit
}

/**
 * @param soundEnabled whether the creative starts audible. False plays silently and leaves the
 *   audio focus of other apps alone — there would be nothing to hear in exchange for ducking
 *   them. Can be changed later through [setSoundEnabled].
 */
internal class ExoVideoPlayer(
    private var soundEnabled: Boolean = true,
    private val playerFactory: (Context) -> ExoPlayer = { context ->
        ExoPlayer.Builder(context).build()
    },
) : VideoPlayer {
    private var player: ExoPlayer? = null
    private var lastMuted: Boolean? = null
    private var lastPlaying: Boolean? = null
    private var onMutedChanged: ((Boolean) -> Unit)? = null
    private var onPlayingChanged: ((Boolean) -> Unit)? = null

    override fun play(
        context: Context,
        url: String,
        textureView: TextureView?,
        onEnded: () -> Unit,
        onError: (String) -> Unit,
        onMutedChanged: ((Boolean) -> Unit)?,
        onPlayingChanged: ((Boolean) -> Unit)?,
        onReady: (() -> Unit)?,
    ) {
        release()
        lastMuted = null
        lastPlaying = null
        this.onMutedChanged = onMutedChanged
        this.onPlayingChanged = onPlayingChanged
        var readySent = false
        val hasSurface = textureView != null
        fun notifyReady() {
            if (readySent) return
            readySent = true
            onReady?.invoke()
        }
        val exo = playerFactory(context.applicationContext).apply {
            // Ad audio must duck other apps like any other media playback.
            setAudioAttributes(mediaAudioAttributes(), /* handleAudioFocus = */ soundEnabled)
            // Before the listener is attached, so the starting state is not reported as a
            // mute event: the viewer did not do anything.
            if (!soundEnabled) volume = 0f
        }
        player = exo
        if (textureView != null) {
            exo.setVideoTextureView(textureView)
        }
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.addListener(object : PlayerCallbacks() {
            @Deprecated("Deprecated in Media3")
            override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
                onState(playbackState)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                onState(playbackState)
            }

            fun onState(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) onEnded()
                // Audio-only has no frame. Video waits for the first composed frame so
                // chrome does not sit on an empty surface.
                if (playbackState == Player.STATE_READY && !hasSurface) notifyReady()
            }

            override fun onRenderedFirstFrame() {
                notifyReady()
            }

            override fun onPlayerError(error: PlaybackException) {
                onError(error.errorCodeName)
            }

            override fun onVolumeChanged(volume: Float) {
                notifyMuted(volume <= 0f)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                notifyPlaying(playWhenReady)
            }
        })
        exo.prepare()
        exo.playWhenReady = true
        lastMuted = exo.volume <= 0f
    }

    override fun positionMs(): Long = player?.currentPosition?.coerceAtLeast(0L) ?: 0L

    override fun isMuted(): Boolean = (player?.volume ?: 1f) <= 0f

    override fun isPlaying(): Boolean = player?.playWhenReady == true

    override fun setMuted(muted: Boolean) {
        if (!soundEnabled && !muted) return
        player?.volume = if (muted) 0f else 1f
        notifyMuted(muted)
    }

    override fun setPlaying(playing: Boolean) {
        player?.playWhenReady = playing
        notifyPlaying(playing)
    }

    override fun setSoundEnabled(enabled: Boolean) {
        soundEnabled = enabled
        // Audio focus is a property of the running player, so a creative already on screen has
        // to be re-armed — otherwise turning sound back on would stay silent until the next ad.
        player?.setAudioAttributes(mediaAudioAttributes(), /* handleAudioFocus = */ enabled)
        player?.volume = if (enabled) 1f else 0f
        notifyMuted(!enabled)
    }

    override fun release() {
        player?.clearVideoSurface()
        player?.release()
        player = null
        lastMuted = null
        lastPlaying = null
        onMutedChanged = null
        onPlayingChanged = null
    }

    private fun mediaAudioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

    private fun notifyMuted(muted: Boolean) {
        if (lastMuted == muted) return
        lastMuted = muted
        onMutedChanged?.invoke(muted)
    }

    private fun notifyPlaying(playing: Boolean) {
        if (lastPlaying == playing) return
        lastPlaying = playing
        onPlayingChanged?.invoke(playing)
    }
}
