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
import com.ctvhouse.sdk.core.runtime.Log
import com.ctvhouse.sdk.core.runtime.MainPoster
import com.ctvhouse.sdk.core.ui.Ui
import com.ctvhouse.sdk.format.Overlay
import com.ctvhouse.sdk.format.TriggerRoll
import java.util.concurrent.Executor

/**
 * Tag-URL launcher for the ad an app opens with: the launch screen is ready, nothing is playing
 * behind the overlay, and the viewer has not asked for anything yet.
 *
 * The show itself is what [SwitchRollAd] does — creative, chrome and VAST reporting come from
 * [TriggerRoll]. The difference is that a launch happens once: [show] opens one opportunity per
 * instance, so an `onStart` after the app came back from the background, or a screen rebuilt by the
 * system, does not pay the viewer a second ad. What ended that opportunity — fill, no fill or a
 * failure — does not bring it back.
 *
 * One instance serves one screen: after [detach] create a new one.
 */
class StartRollAd private constructor(
    private val format: TriggerRoll,
) {
    private var tagUrl: String? = null
    private var used = false

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
        val fetcher = TagFetcher(format, TAG) { tagUrl }
        // The turn is spent when the format actually takes the opportunity, not when the host asks
        // for it: a `show` before `attach` is a host mistake, and the launch ad still has its turn.
        format.setController { reason, sink ->
            used = true
            fetcher.onOpportunity(reason, sink)
        }
    }

    fun setTagUrl(url: String): StartRollAd {
        tagUrl = url.trim()
        return this
    }

    /**
     * Advertising ID for `${IFA}`. Overrides the ID the device offers, for a host that manages
     * consent itself; an empty string goes back to the platform ID.
     */
    fun setIfa(value: String): StartRollAd {
        format.setIfa(value)
        return this
    }

    /**
     * Client IP for `${IP}`. Empty unless the host knows it — the public address is not visible
     * from inside the app.
     */
    fun setIp(value: String): StartRollAd {
        format.setIp(value)
        return this
    }

    fun setRequestTimeoutMs(timeoutMs: Int): StartRollAd {
        format.setRequestTimeoutMs(timeoutMs)
        return this
    }

    fun setMarkingTemplate(template: String): StartRollAd {
        format.setMarkingTemplate(template)
        return this
    }

    fun setSkipCountdownTemplate(template: String): StartRollAd {
        format.setSkipCountdownTemplate(template)
        return this
    }

    fun setSkipTemplate(template: String): StartRollAd {
        format.setSkipTemplate(template)
        return this
    }

    fun setSkipOffsetSeconds(seconds: Int): StartRollAd {
        format.setSkipOffsetSeconds(seconds)
        return this
    }

    /**
     * Whether the overlay takes itself down when the creative ends. On by default, which is what a
     * launch ad usually wants: the creative plays out and the app goes on to its first screen.
     * `false` keeps the last frame or the still up until the viewer skips or the host calls
     * [dismiss].
     */
    fun setDismissOnCreativeEnd(enabled: Boolean): StartRollAd {
        format.setDismissOnCreativeEnd(enabled)
        return this
    }

    /**
     * How long a still counts as playing when the response carries no duration of its own.
     * `0`, the default, leaves such a still waiting for the viewer.
     */
    fun setBannerDurationSeconds(seconds: Int): StartRollAd {
        format.setBannerDurationSeconds(seconds)
        return this
    }

    /**
     * Whether creatives play with sound. Default is on.
     * `false` plays at zero volume and does not request audio focus.
     */
    fun setSoundEnabled(enabled: Boolean): StartRollAd {
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
    ): StartRollAd {
        format.setControlsPosition(horizontal, vertical)
        return this
    }

    /** Corner for the ad-marking chip. Default is left × top. */
    fun setMarkingPosition(
        horizontal: Overlay.ControlsHorizontal,
        vertical: Overlay.ControlsVertical,
    ): StartRollAd {
        format.setMarkingPosition(horizontal, vertical)
        return this
    }

    /** Corner for the skip chip. Default is right × top. */
    fun setSkipPosition(
        horizontal: Overlay.ControlsHorizontal,
        vertical: Overlay.ControlsVertical,
    ): StartRollAd {
        format.setSkipPosition(horizontal, vertical)
        return this
    }

    /** Brand mark inside the ad-marking chip. On by default; pass false to hide the icon only. */
    fun setLogoVisible(visible: Boolean): StartRollAd {
        format.setLogoVisible(visible)
        return this
    }

    /**
     * The black fill the creative sits on. Off by default, so a creative with transparent pixels,
     * and the letterbox around one that does not fill the screen, show what is behind it. On a
     * launch screen that is usually the app's own splash, so `true` is worth a look here.
     */
    fun setBackdropVisible(visible: Boolean): StartRollAd {
        format.setBackdropVisible(visible)
        return this
    }

    /** Info control (QR of the ClickThrough). On by default; still hidden without a landing URL. */
    fun setInfoVisible(visible: Boolean): StartRollAd {
        format.setInfoVisible(visible)
        return this
    }

    /** Pause / resume of the linear creative. On by default; still hidden for a banner. */
    fun setPauseVisible(visible: Boolean): StartRollAd {
        format.setPauseVisible(visible)
        return this
    }

    /** Mute of the linear creative. On by default; still hidden for a banner or when sound is off. */
    fun setMuteVisible(visible: Boolean): StartRollAd {
        format.setMuteVisible(visible)
        return this
    }

    /** Diagnostic logging. Process-wide: only enable it on test builds. */
    fun setDebugLogging(enabled: Boolean): StartRollAd {
        format.setDebugLogging(enabled)
        return this
    }

    fun setListener(listener: TriggerRoll.Listener?): StartRollAd {
        format.setListener(listener)
        return this
    }

    /**
     * The app is open and ready to show an ad. [reason] names the launch for the logs — `cold`,
     * `deeplink`, whatever the host tells apart.
     *
     * Only the first call does anything: the launch ad has one turn per instance. Call it after
     * [attach], and after the container is laid out — the overlay covers whatever the host has on
     * screen at that moment.
     */
    @JvmOverloads
    @MainThread
    fun show(reason: String = "app_start"): StartRollAd {
        if (used) {
            Log.i(TAG, "show($reason) ignored: the launch ad already had its turn")
            return this
        }
        format.trigger(reason)
        return this
    }

    /**
     * Takes the overlay down: the host is moving on to its first screen without the viewer
     * skipping, or the screen is going away. Drops an in-flight request too.
     */
    @MainThread
    fun dismiss(): StartRollAd {
        format.close()
        return this
    }

    /** Prepares the overlay. A call after [detach] is ignored. */
    @MainThread
    fun attach(): StartRollAd {
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
        const val TAG = "StartRollAd"
    }
}
