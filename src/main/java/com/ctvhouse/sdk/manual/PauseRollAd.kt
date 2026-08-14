package com.ctvhouse.sdk.manual

import android.content.Context
import android.view.ViewGroup
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import com.ctvhouse.sdk.core.identity.Identity
import com.ctvhouse.sdk.core.media.BitmapLoader
import com.ctvhouse.sdk.core.net.Client
import com.ctvhouse.sdk.core.player.VideoPlayer
import com.ctvhouse.sdk.core.runtime.Clock
import com.ctvhouse.sdk.core.runtime.MainPoster
import com.ctvhouse.sdk.core.ui.Ui
import com.ctvhouse.sdk.format.Overlay
import com.ctvhouse.sdk.format.TriggerRoll
import java.util.concurrent.Executor

/**
 * Tag-URL launcher for [TriggerRoll].
 *
 * One way to run the format: every opportunity fetches the configured URL and hands
 * the markup to [TriggerRoll]. A host [TriggerRoll.Controller] is the other.
 * The host decides when that is ([trigger] / [close]).
 * One instance serves one screen: after [detach] create a new one.
 */
class PauseRollAd private constructor(
    private val format: TriggerRoll,
) {
    private var tagUrl: String? = null

    /**
     * @param overlayContainer view group for the overlay (usually the root FrameLayout)
     * @param soundEnabled false plays every creative silently and does not touch the audio
     *   focus of other apps. [setSoundEnabled] changes it later.
     */
    @JvmOverloads
    @MainThread
    constructor(
        overlayContainer: ViewGroup,
        soundEnabled: Boolean = true,
    ) : this(
        TriggerRoll(overlayContainer, soundEnabled),
    )

    internal constructor(
        appContext: Context,
        ui: Ui,
        client: Client,
        bitmapLoader: BitmapLoader,
        videoPlayer: VideoPlayer,
        clock: Clock,
        mainPoster: MainPoster,
        io: Executor,
        track: Executor = io,
        identity: Identity = Identity(appContext),
    ) : this(
        TriggerRoll(
            appContext = appContext,
            ui = ui,
            client = client,
            bitmapLoader = bitmapLoader,
            videoPlayer = videoPlayer,
            clock = clock,
            mainPoster = mainPoster,
            io = io,
            track = track,
            identity = identity,
        ),
    )

    init {
        format.setController(TagFetcher(format, TAG) { tagUrl })
    }

    fun setTagUrl(url: String): PauseRollAd {
        tagUrl = url.trim()
        return this
    }

    /**
     * Advertising ID for `${IFA}`. Overrides the ID the device offers, for a host that manages
     * consent itself; an empty string goes back to the platform ID.
     */
    fun setIfa(value: String): PauseRollAd {
        format.setIfa(value)
        return this
    }

    /**
     * Client IP for `${IP}`. Empty unless the host knows it — the public address is not visible
     * from inside the app.
     */
    fun setIp(value: String): PauseRollAd {
        format.setIp(value)
        return this
    }

    fun setRequestTimeoutMs(timeoutMs: Int): PauseRollAd {
        format.setRequestTimeoutMs(timeoutMs)
        return this
    }

    fun setMarkingTemplate(template: String): PauseRollAd {
        format.setMarkingTemplate(template)
        return this
    }

    fun setSkipCountdownTemplate(template: String): PauseRollAd {
        format.setSkipCountdownTemplate(template)
        return this
    }

    fun setSkipTemplate(template: String): PauseRollAd {
        format.setSkipTemplate(template)
        return this
    }

    fun setSkipOffsetSeconds(seconds: Int): PauseRollAd {
        format.setSkipOffsetSeconds(seconds)
        return this
    }

    /**
     * Whether the overlay takes itself down when the creative ends. On by default; `false` keeps
     * the last frame or the still on screen until the viewer skips or the host calls [close].
     */
    fun setDismissOnCreativeEnd(enabled: Boolean): PauseRollAd {
        format.setDismissOnCreativeEnd(enabled)
        return this
    }

    /**
     * How long a still counts as playing when the response carries no duration of its own.
     * `0`, the default, leaves such a still waiting for the viewer.
     */
    fun setBannerDurationSeconds(seconds: Int): PauseRollAd {
        format.setBannerDurationSeconds(seconds)
        return this
    }

    /**
     * Whether creatives play with sound. Default is on.
     * `false` plays at zero volume and does not request audio focus.
     */
    fun setSoundEnabled(enabled: Boolean): PauseRollAd {
        format.setSoundEnabled(enabled)
        return this
    }

    /**
     * Corner for the playback group (info, mute, pause).
     * Default is left × bottom. Mute and pause only appear for a linear creative.
     * Info appears when the creative has an http(s) ClickThrough.
     */
    fun setControlsPosition(
        horizontal: Overlay.ControlsHorizontal,
        vertical: Overlay.ControlsVertical,
    ): PauseRollAd {
        format.setControlsPosition(horizontal, vertical)
        return this
    }

    /** Corner for the ad-marking chip. Default is left × top. */
    fun setMarkingPosition(
        horizontal: Overlay.ControlsHorizontal,
        vertical: Overlay.ControlsVertical,
    ): PauseRollAd {
        format.setMarkingPosition(horizontal, vertical)
        return this
    }

    /** Corner for the skip chip. Default is right × top. */
    fun setSkipPosition(
        horizontal: Overlay.ControlsHorizontal,
        vertical: Overlay.ControlsVertical,
    ): PauseRollAd {
        format.setSkipPosition(horizontal, vertical)
        return this
    }

    /** Brand mark inside the ad-marking chip. On by default; pass false to hide the icon only. */
    fun setLogoVisible(visible: Boolean): PauseRollAd {
        format.setLogoVisible(visible)
        return this
    }

    /**
     * The black fill the creative sits on. Off by default, so a creative with transparent pixels,
     * and the letterbox around one that does not fill the screen, show the content.
     */
    fun setBackdropVisible(visible: Boolean): PauseRollAd {
        format.setBackdropVisible(visible)
        return this
    }

    /** Info control (QR of the ClickThrough). On by default; still hidden without a landing URL. */
    fun setInfoVisible(visible: Boolean): PauseRollAd {
        format.setInfoVisible(visible)
        return this
    }

    /** Pause / resume of the linear creative. On by default; still hidden for a banner. */
    fun setPauseVisible(visible: Boolean): PauseRollAd {
        format.setPauseVisible(visible)
        return this
    }

    /** Mute of the linear creative. On by default; still hidden for a banner or when sound is off. */
    fun setMuteVisible(visible: Boolean): PauseRollAd {
        format.setMuteVisible(visible)
        return this
    }

    /** Diagnostic logging. Process-wide: only enable it on test builds. */
    fun setDebugLogging(enabled: Boolean): PauseRollAd {
        format.setDebugLogging(enabled)
        return this
    }

    fun setListener(listener: TriggerRoll.Listener?): PauseRollAd {
        format.setListener(listener)
        return this
    }

    /**
     * Host fires an ad opportunity — a content pause, for example.
     * The SDK does not watch the content player.
     */
    @JvmOverloads
    @MainThread
    fun trigger(reason: String = "host"): PauseRollAd {
        format.trigger(reason)
        return this
    }

    /** Host ends the opportunity. Drops an in-flight request and closes a showing overlay. */
    @MainThread
    fun close(): PauseRollAd {
        format.close()
        return this
    }

    /** Prepares the overlay. A call after [detach] is ignored. */
    @MainThread
    fun attach(): PauseRollAd {
        format.attach()
        return this
    }

    /** Releases threads, views and host callbacks. The instance is terminal afterwards. */
    @MainThread
    fun detach() {
        format.detach()
    }

    @VisibleForTesting
    internal fun onSkipFromUi() = format.onSkipFromUi()

    @VisibleForTesting
    internal fun onInfoFromUi() = format.onInfoFromUi()

    companion object {
        private const val TAG = "PauseRollAd"
    }
}
