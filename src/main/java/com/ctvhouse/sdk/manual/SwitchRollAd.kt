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
 * Tag-URL launcher for an ad opened by something the viewer did in the app — picking a card,
 * switching a channel, opening a section — rather than by a pause in playback.
 *
 * The difference from [PauseRollAd] is what the host reports and what it has to restore
 * afterwards: there is usually no content playing behind the overlay, so [show] takes the action
 * that opened it and nothing has to be resumed in `onClose`. The creative, the chrome and the
 * VAST reporting are the ones [TriggerRoll] always does.
 *
 * One instance serves one screen: after [detach] create a new one.
 */
class SwitchRollAd private constructor(
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

    fun setTagUrl(url: String): SwitchRollAd {
        tagUrl = url.trim()
        return this
    }

    /**
     * Advertising ID for `${IFA}`. Overrides the ID the device offers, for a host that manages
     * consent itself; an empty string goes back to the platform ID.
     */
    fun setIfa(value: String): SwitchRollAd {
        format.setIfa(value)
        return this
    }

    /**
     * Client IP for `${IP}`. Empty unless the host knows it — the public address is not visible
     * from inside the app.
     */
    fun setIp(value: String): SwitchRollAd {
        format.setIp(value)
        return this
    }

    fun setRequestTimeoutMs(timeoutMs: Int): SwitchRollAd {
        format.setRequestTimeoutMs(timeoutMs)
        return this
    }

    fun setMarkingTemplate(template: String): SwitchRollAd {
        format.setMarkingTemplate(template)
        return this
    }

    fun setSkipCountdownTemplate(template: String): SwitchRollAd {
        format.setSkipCountdownTemplate(template)
        return this
    }

    fun setSkipTemplate(template: String): SwitchRollAd {
        format.setSkipTemplate(template)
        return this
    }

    /** Label of the chip that opens the ClickThrough. */
    fun setLandingTemplate(template: String): SwitchRollAd {
        format.setLandingTemplate(template)
        return this
    }

    fun setSkipOffsetSeconds(seconds: Int): SwitchRollAd {
        format.setSkipOffsetSeconds(seconds)
        return this
    }

    /**
     * Whether the overlay takes itself down when the creative ends. On by default; `false` keeps
     * the last frame or the still on screen until the viewer skips or the host calls [dismiss].
     */
    fun setDismissOnCreativeEnd(enabled: Boolean): SwitchRollAd {
        format.setDismissOnCreativeEnd(enabled)
        return this
    }

    /**
     * How long a still counts as playing when the response carries no duration of its own.
     * `0`, the default, leaves such a still waiting for the viewer.
     */
    fun setBannerDurationSeconds(seconds: Int): SwitchRollAd {
        format.setBannerDurationSeconds(seconds)
        return this
    }

    /**
     * Whether creatives play with sound. Default is on.
     * `false` plays at zero volume and does not request audio focus.
     */
    fun setSoundEnabled(enabled: Boolean): SwitchRollAd {
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
    ): SwitchRollAd {
        format.setControlsPosition(horizontal, vertical)
        return this
    }

    /** Corner for the ad-marking chip. Default is left × top. */
    fun setMarkingPosition(
        horizontal: Overlay.ControlsHorizontal,
        vertical: Overlay.ControlsVertical,
    ): SwitchRollAd {
        format.setMarkingPosition(horizontal, vertical)
        return this
    }

    /** Corner for the skip chip. Default is right × top. */
    fun setSkipPosition(
        horizontal: Overlay.ControlsHorizontal,
        vertical: Overlay.ControlsVertical,
    ): SwitchRollAd {
        format.setSkipPosition(horizontal, vertical)
        return this
    }

    /** Brand mark inside the ad-marking chip. On by default; pass false to hide the icon only. */
    fun setLogoVisible(visible: Boolean): SwitchRollAd {
        format.setLogoVisible(visible)
        return this
    }

    /**
     * The black fill the creative sits on. Off by default, so a creative with transparent pixels,
     * and the letterbox around one that does not fill the screen, show the content.
     */
    fun setBackdropVisible(visible: Boolean): SwitchRollAd {
        format.setBackdropVisible(visible)
        return this
    }

    /** Info control (QR of the ClickThrough). On by default; still hidden without a landing URL. */
    fun setInfoVisible(visible: Boolean): SwitchRollAd {
        format.setInfoVisible(visible)
        return this
    }

    /**
     * Chip next to skip that opens the ClickThrough in the system browser. On by default;
     * still hidden without a landing URL.
     */
    fun setLandingVisible(visible: Boolean): SwitchRollAd {
        format.setLandingVisible(visible)
        return this
    }

    /** Pause / resume of the linear creative. On by default; still hidden for a banner. */
    fun setPauseVisible(visible: Boolean): SwitchRollAd {
        format.setPauseVisible(visible)
        return this
    }

    /** Mute of the linear creative. On by default; still hidden for a banner or when sound is off. */
    fun setMuteVisible(visible: Boolean): SwitchRollAd {
        format.setMuteVisible(visible)
        return this
    }

    /** Diagnostic logging. Process-wide: only enable it on test builds. */
    fun setDebugLogging(enabled: Boolean): SwitchRollAd {
        format.setDebugLogging(enabled)
        return this
    }

    fun setListener(listener: TriggerRoll.Listener?): SwitchRollAd {
        format.setListener(listener)
        return this
    }

    /**
     * The viewer did something the host wants to monetise. [action] identifies it for the logs and
     * for the `reason` a [TriggerRoll.Controller] would see — "card", "channel_switch", whatever
     * the host calls it.
     *
     * Ignored while an overlay from an earlier action is still on screen, so a viewer clicking
     * through a rail gets one ad, not a queue of them.
     */
    @JvmOverloads
    @MainThread
    fun show(action: String = "switch"): SwitchRollAd {
        format.trigger(action)
        return this
    }

    /**
     * Takes the overlay down: the host is leaving the screen or moving on without the viewer
     * skipping. Drops an in-flight request too.
     */
    @MainThread
    fun dismiss(): SwitchRollAd {
        format.close()
        return this
    }

    /** Prepares the overlay. A call after [detach] is ignored. */
    @MainThread
    fun attach(): SwitchRollAd {
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

    private companion object {
        const val TAG = "SwitchRollAd"
    }
}
