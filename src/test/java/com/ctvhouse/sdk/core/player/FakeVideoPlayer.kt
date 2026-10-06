package com.ctvhouse.sdk.core.player

import android.content.Context
import android.view.TextureView

/** Drives creative playback callbacks and position from the test instead of a real ExoPlayer. */
internal class FakeVideoPlayer : VideoPlayer {

    var playedUrl: String? = null
        private set
    var releases = 0
        private set
    var positionMs = 0L
    var muted = false
        private set
    var playing = false
        private set
    var soundEnabled = true
        private set
    /** When false, tests must call [signalReady] to stand in for the first frame. */
    var emitReadyOnPlay = true

    private var onEnded: (() -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    private var onMutedChanged: ((Boolean) -> Unit)? = null
    private var onPlayingChanged: ((Boolean) -> Unit)? = null
    private var onReady: (() -> Unit)? = null

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
        playedUrl = url
        positionMs = 0L
        muted = false
        playing = true
        this.onEnded = onEnded
        this.onError = onError
        this.onMutedChanged = onMutedChanged
        this.onPlayingChanged = onPlayingChanged
        this.onReady = onReady
        onPlayingChanged?.invoke(true)
        if (emitReadyOnPlay) onReady?.invoke()
    }

    override fun positionMs(): Long = positionMs

    override fun isMuted(): Boolean = muted

    override fun isPlaying(): Boolean = playing

    override fun setMuted(muted: Boolean) {
        this.muted = muted
        onMutedChanged?.invoke(muted)
    }

    override fun setPlaying(playing: Boolean) {
        this.playing = playing
        onPlayingChanged?.invoke(playing)
    }

    override fun setSoundEnabled(enabled: Boolean) {
        soundEnabled = enabled
        if (!enabled && !muted) {
            muted = true
            onMutedChanged?.invoke(true)
        }
    }

    override fun release() {
        releases++
        playedUrl = null
        playing = false
        onEnded = null
        onError = null
        onMutedChanged = null
        onPlayingChanged = null
        onReady = null
    }

    fun finish() = onEnded?.invoke()

    fun fail(message: String) = onError?.invoke(message)

    fun signalReady() = onReady?.invoke()
}
