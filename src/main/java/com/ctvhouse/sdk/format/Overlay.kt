package com.ctvhouse.sdk.format

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import android.view.TextureView
import androidx.annotation.MainThread
import com.ctvhouse.sdk.SdkVersion
import com.ctvhouse.sdk.core.identity.Identity
import com.ctvhouse.sdk.core.net.Client
import com.ctvhouse.sdk.core.net.UrlConnectionClient
import com.ctvhouse.sdk.core.player.ExoVideoPlayer
import com.ctvhouse.sdk.core.player.UnavailablePlayer
import com.ctvhouse.sdk.core.player.VideoPlayer
import com.ctvhouse.sdk.core.runtime.Clock
import com.ctvhouse.sdk.core.runtime.HandlerMainPoster
import com.ctvhouse.sdk.core.runtime.Log
import com.ctvhouse.sdk.core.runtime.MainPoster
import com.ctvhouse.sdk.core.runtime.Safe
import com.ctvhouse.sdk.core.runtime.SystemClock
import com.ctvhouse.sdk.core.ui.Chrome
import com.ctvhouse.sdk.core.ui.Placement
import com.ctvhouse.sdk.core.ui.Templates
import com.ctvhouse.sdk.core.ui.Ui
import com.ctvhouse.sdk.core.vast.Macros
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

/**
 * Slot engine for overlay formats: trigger, chrome, linear/image show.
 *
 * Not a host-facing format — hosts use [TriggerRoll], or a launcher such as
 * `com.ctvhouse.sdk.manual.PauseRollAd`. The constructor is internal.
 */
abstract class Overlay<T : Overlay<T, L>, L : Overlay.Listener> internal constructor(
    internal val appContext: Context,
    private val ui: Ui,
    internal val client: Client,
    private val videoPlayer: VideoPlayer,
    internal val clock: Clock,
    internal val mainPoster: MainPoster,
    /** VAST tag / creative bytes (single-thread). */
    internal val io: Executor,
    /** Tracker pings (bounded parallel pool). */
    internal val track: Executor,
    /** Device and app the library reports with; feeds every macro. */
    internal val identity: Identity,
    /** When true, [io]/[track] are shut down in [detach]. */
    private val ownsExecutors: Boolean = false,
) {
    /**
     * Slot window. Always on the main thread. Defaults are no-ops.
     * Advertising events live on the format listener under VAST names
     * (`onSkip(urls)`, `onClose(urls)`, …) — those are not this interface.
     */
    interface Listener {
        /**
         * Overlay is on screen. Pair with [onClose]. Not called when there is no fill.
         * Host typically hides content-player chrome here.
         */
        fun onOpen() {}

        /**
         * Overlay is gone after a matching [onOpen]. No-arg on purpose: this is the slot,
         * not VAST `close` (that lives on the format listener as `onClose(urls)`).
         * Host typically restores chrome, focus, and content playback here.
         */
        fun onClose() {}

        fun onNoAd() {}
        fun onError(message: String) {}
    }

    /** Horizontal edge for a chrome piece (playback group, marking, or skip). */
    enum class ControlsHorizontal { LEFT, RIGHT }

    /** Vertical edge for a chrome piece (playback group, marking, or skip). */
    enum class ControlsVertical { TOP, BOTTOM }

    protected var skipOffsetOverrideMs: Long? = null
    internal var requestTimeoutMs: Int = DEFAULT_REQUEST_TIMEOUT_MS
    private var dismissAtCreativeEnd: Boolean = true
    private var bannerDurationOverrideMs: Long = 0L
    // Private on purpose: a protected `var x` also publishes `setX`, which then reads as a second
    // candidate next to the builder `setX` and makes the call ambiguous for a Java host.
    private var markingTemplate: String = DEFAULT_MARKING_TEMPLATE
    private var skipCountdownTemplate: String = DEFAULT_SKIP_COUNTDOWN_TEMPLATE
    private var skipTemplate: String = DEFAULT_SKIP_TEMPLATE
    private var landingTemplate: String = DEFAULT_LANDING_TEMPLATE

    /**
     * Host callbacks. Set by the concrete format rather than here: a setter taking the type
     * variable `L` reads as two candidate overloads to javac, and a Java host would need a cast.
     */
    protected var listener: L? = null
    private var soundEnabled = true
    private var debugLoggingOwned = false

    protected var attached = false
    private var released = false
    protected var overlayActive = false
    protected var playbackChrome = false
    private var infoVisible = true
    private var landingVisible = true
    private var pauseVisible = true
    private var muteVisible = true
    protected var infoUrl: String? = null
    protected var adStartedAtMs = 0L
    protected var skipOffsetMs: Long = DEFAULT_SKIP_OFFSET_MS
    /** VAST `<Error>` URLs for the current creative (fired on playback/render failure). */
    protected var errorTrackerUrls: List<String> = emptyList()
    protected val requestSeq = AtomicInteger(0)
    private var tickerScheduled = false

    // Last chrome the ticker actually pushed. The ticker runs four times a second and the
    // countdown changes once a second, so most rounds have nothing to rebuild.
    private var chromeErid: String? = null
    private var chromeMarking: String? = null
    private var lastChrome: Chrome? = null

    @Suppress("UNCHECKED_CAST")
    protected fun self(): T = this as T

    internal abstract fun onTrigger(reason: String)

    /** Host cancelled the opportunity (content playing again, seek ended, …). */
    internal open fun onTriggerCancelled() {
        requestSeq.incrementAndGet()
        if (overlayActive) dismiss(onlyIfShowing = false)
    }

    /**
     * Host fires an ad opportunity — a content pause, for example. What counts as one is the
     * host's decision; the library never watches the content player.
     * Ignored if not [attach]ed or if the overlay is already on screen.
     */
    @JvmOverloads
    @MainThread
    fun trigger(reason: String = "host"): T {
        if (!isMainThread("trigger") { trigger(reason) }) return self()
        if (!attached) {
            Log.w(logTag(), "trigger($reason) ignored: attach() first")
            return self()
        }
        if (overlayActive) return self()
        onTrigger(reason)
        return self()
    }

    /**
     * Host ends the opportunity. Drops an in-flight request and closes a showing overlay.
     * Distinct from [Listener.onClose] (slot window) and VAST `onClose(urls)`.
     */
    @MainThread
    fun close(): T {
        if (!isMainThread("close") { close() }) return self()
        if (!attached) return self()
        onTriggerCancelled()
        return self()
    }

    fun setRequestTimeoutMs(timeoutMs: Int): T {
        requestTimeoutMs = timeoutMs.coerceAtLeast(1)
        return self()
    }

    fun setMarkingTemplate(template: String): T {
        markingTemplate = template
        return self()
    }

    fun setSkipCountdownTemplate(template: String): T {
        skipCountdownTemplate = template
        return self()
    }

    fun setSkipTemplate(template: String): T {
        skipTemplate = template
        return self()
    }

    /** Label of the chip that opens the ClickThrough. */
    fun setLandingTemplate(template: String): T {
        landingTemplate = template
        return self()
    }

    fun setSkipOffsetSeconds(seconds: Int): T {
        skipOffsetOverrideMs = seconds.coerceAtLeast(0) * 1000L
        return self()
    }

    /**
     * Whether the overlay takes itself down when the creative ends. On by default: a linear
     * creative that played out closes, and so does a still once its soundtrack or display duration
     * is over. `false` keeps the creative on screen — the last video frame, or the still — until
     * the viewer skips or the host calls close.
     *
     * The VAST reporting does not change either way: `complete` and `closeLinear` are sent when the
     * creative ends, not when the overlay goes away.
     */
    fun setDismissOnCreativeEnd(enabled: Boolean): T {
        dismissAtCreativeEnd = enabled
        return self()
    }

    /**
     * How long a still stays on screen before it counts as ended. Used only when the response has
     * no duration of its own — a VAST `Linear` `Duration` in the same response wins — and only when
     * there is no soundtrack, whose end is the creative's end. `0`, the default, means a still with
     * nothing to time waits for the viewer.
     */
    fun setBannerDurationSeconds(seconds: Int): T {
        bannerDurationOverrideMs = seconds.coerceAtLeast(0) * 1000L
        return self()
    }

    /** Whether the creative's own end closes the overlay. */
    protected fun dismissesAtCreativeEnd(): Boolean = dismissAtCreativeEnd

    /** Host-configured display duration for a still, `0` when unset. */
    protected fun bannerDurationMs(): Long = bannerDurationOverrideMs

    /**
     * Advertising ID to report as `${IFA}`. Overrides what the device offers, for a host that
     * manages consent itself. Pass an empty string to go back to the platform ID.
     */
    fun setIfa(value: String): T {
        identity.setIfa(value)
        return self()
    }

    /**
     * Client IP to report as `${IP}`. The library cannot see the public address from inside the
     * app, so this is empty unless the host knows it.
     */
    fun setIp(value: String): T {
        identity.setIp(value)
        return self()
    }

    /**
     * Whether creatives play with sound. Default is on.
     * `false` plays at zero volume and does not request audio focus.
     * Call before the creative starts; the starting silence is not a VAST `mute`.
     */
    fun setSoundEnabled(enabled: Boolean): T {
        soundEnabled = enabled
        videoPlayer.setSoundEnabled(enabled)
        refreshChromeIfShowing()
        return self()
    }

    /**
     * Corner for the playback group (info, mute, pause).
     * Default is left × bottom. Mute and pause only appear for a linear creative.
     * Info appears when the creative has an http(s) ClickThrough.
     */
    fun setControlsPosition(
        horizontal: ControlsHorizontal,
        vertical: ControlsVertical,
    ): T {
        ui.setPlacement(corner(horizontal, vertical))
        return self()
    }

    /** Corner for the ad-marking chip. Default is left × top. */
    fun setMarkingPosition(
        horizontal: ControlsHorizontal,
        vertical: ControlsVertical,
    ): T {
        ui.setMarkingPlacement(corner(horizontal, vertical))
        return self()
    }

    /** Corner for the skip chip. Default is right × top. */
    fun setSkipPosition(
        horizontal: ControlsHorizontal,
        vertical: ControlsVertical,
    ): T {
        ui.setSkipPlacement(corner(horizontal, vertical))
        return self()
    }

    /** Brand mark inside the ad-marking chip. On by default; pass false to hide the icon only. */
    fun setLogoVisible(visible: Boolean): T {
        ui.setLogoVisible(visible)
        return self()
    }

    /**
     * The black fill the creative sits on. Off by default: the transparent pixels of a PNG, and
     * the letterbox around a creative of another aspect ratio, show the host's content.
     *
     * `true` puts the fill back, so a creative that does not cover the screen is framed in black
     * instead of the frame behind it. The controls keep their own scrim either way.
     */
    fun setBackdropVisible(visible: Boolean): T {
        ui.setBackdropVisible(visible)
        return self()
    }

    /** Info control (QR of the ClickThrough). On by default; still hidden without a landing URL. */
    fun setInfoVisible(visible: Boolean): T {
        infoVisible = visible
        refreshChromeIfShowing()
        return self()
    }

    /**
     * Chip next to skip that hands the ClickThrough to the system browser. On by default;
     * still hidden without a landing URL. Devices without a browser drop the intent, so the
     * chip is worth hiding on a fleet where the info QR is the only way out.
     */
    fun setLandingVisible(visible: Boolean): T {
        landingVisible = visible
        refreshChromeIfShowing()
        return self()
    }

    /** Pause / resume of the linear creative. On by default; still hidden for a banner. */
    fun setPauseVisible(visible: Boolean): T {
        pauseVisible = visible
        refreshChromeIfShowing()
        return self()
    }

    /** Mute of the linear creative. On by default; still hidden for a banner or when sound is off. */
    fun setMuteVisible(visible: Boolean): T {
        muteVisible = visible
        refreshChromeIfShowing()
        return self()
    }

    /** Diagnostic logging. Process-wide: only enable it on test builds. */
    fun setDebugLogging(enabled: Boolean): T {
        debugLoggingOwned = enabled
        Log.enabled = enabled
        return self()
    }

    /**
     * Prepares the overlay. Call once, after the container exists.
     * A second call while attached, or any call after [detach], does nothing.
     */
    @MainThread
    fun attach(): T {
        if (!isMainThread("attach") { attach() }) return self()
        if (released) {
            Log.w(logTag(), "attach ignored: instance was detached — create a new one")
            return self()
        }
        if (attached) return self()
        ui.attach()
        attached = true
        // The advertising ID needs a service round trip. Doing it here, on the same single-thread
        // executor the tag fetch uses, means the first request already carries the ID without any
        // call ever waiting for it.
        Safe.execute(io, logTag(), "device lookup") { identity.refresh() }
        Log.i(logTag(), "attached lib=${SdkVersion.NAME}")
        return self()
    }

    /**
     * Releases everything the library holds: own threads, pending main work,
     * host callbacks and views. The instance is terminal afterwards.
     */
    @MainThread
    fun detach() {
        if (!isMainThread("detach") { detach() }) return
        if (released) return
        released = true
        val wasAttached = attached
        attached = false
        requestSeq.incrementAndGet()
        dismiss(onlyIfShowing = true)
        stopTicker()
        videoPlayer.release()
        ui.detach()
        ui.release()
        // Drop host callbacks so Activity/Fragment is not retained after detach.
        listener = null
        releaseOwnedExecutors()
        onDetached()
        if (wasAttached) Log.i(logTag(), "detached")
        mainPoster.cancelAll()
        restoreDebugLogging()
    }

    /** Format-specific cleanup after public [detach] (listeners already cleared). */
    protected open fun onDetached() = Unit

    /**
     * Public entry points touch host views, so they belong to the main thread. A host that calls
     * from somewhere else is not punished with a crash inside our code: the call is logged and
     * handed to the looper instead.
     */
    private fun isMainThread(what: String, retry: () -> Unit): Boolean {
        if (Looper.myLooper() === Looper.getMainLooper()) return true
        Log.w(logTag(), "$what() called off the main thread — deferring to the main looper")
        mainPoster.post(retry)
        return false
    }

    protected fun beginShow() {
        resetChromeCache()
        overlayActive = true
        playbackChrome = false
        adStartedAtMs = clock.currentTimeMillis()
    }

    /** Starts ticker and emits [Listener.onOpen]. Tracking pings are done by the format. */
    protected fun afterShow() {
        startTicker()
        emitOpen()
    }

    /**
     * Plays a linear creative (video or audio).
     * @param visual true → bind video surface; false → audio-only (no surface required).
     */
    protected fun showLinearCreative(
        mediaUrl: String,
        markingErid: String?,
        visual: Boolean = true,
        onEnded: () -> Unit,
        onPlaybackError: (String) -> Unit,
        onMutedChanged: ((Boolean) -> Unit)? = null,
        onPlayingChanged: ((Boolean) -> Unit)? = null,
    ): Boolean {
        val surface = if (visual) {
            ui.videoSurface() ?: run {
                emitError("video surface unavailable")
                return false
            }
        } else {
            null
        }
        beginShow()
        playbackChrome = true
        if (visual) {
            ui.showVideo(chromeOf(markingErid))
        } else {
            ui.showImage(null, chromeOf(markingErid))
        }
        Log.i(logTag(), "play ad media=$mediaUrl visual=$visual")
        return playCreative(
            mediaUrl = mediaUrl,
            surface = surface,
            onEnded = onEnded,
            onPlaybackError = onPlaybackError,
            onMutedChanged = onMutedChanged,
            onPlayingChanged = onPlayingChanged,
            onReady = { ui.revealChrome() },
        )
    }

    protected fun showImageCreative(bitmap: Bitmap, markingErid: String?) {
        beginShow()
        ui.showImage(bitmap, chromeOf(markingErid))
        ui.revealChrome()
    }

    /**
     * Plays linear media under an already-shown companion still. No video surface.
     * Mute / pause chrome come up; the image stays. Caller decides what happens on end.
     */
    protected fun playBackgroundAudio(
        mediaUrl: String,
        markingErid: String?,
        onEnded: () -> Unit,
        onPlaybackError: (String) -> Unit,
        onMutedChanged: ((Boolean) -> Unit)? = null,
        onPlayingChanged: ((Boolean) -> Unit)? = null,
    ) {
        if (!overlayActive) return
        playbackChrome = true
        lastChrome = null
        ui.updateControls(chromeOf(markingErid))
        Log.i(logTag(), "play soundtrack media=$mediaUrl")
        playCreative(
            mediaUrl = mediaUrl,
            surface = null,
            onEnded = onEnded,
            onPlaybackError = onPlaybackError,
            onMutedChanged = onMutedChanged,
            onPlayingChanged = onPlayingChanged,
            onReady = {
                lastChrome = null
                ui.updateControls(chromeOf(markingErid))
            },
        )
    }

    /**
     * Starts the ad player and moves every callback onto the main thread.
     *
     * A host that ships without Media3 would otherwise get a linkage error thrown out of a
     * library call; here it becomes the same playback failure as any other, so the overlay is
     * taken down and the format reports it.
     *
     * @param onReady runs on the main thread only while the overlay is still up.
     * @return false when playback could not be started at all.
     */
    private fun playCreative(
        mediaUrl: String,
        surface: TextureView?,
        onEnded: () -> Unit,
        onPlaybackError: (String) -> Unit,
        onMutedChanged: ((Boolean) -> Unit)?,
        onPlayingChanged: ((Boolean) -> Unit)?,
        onReady: () -> Unit,
    ): Boolean = try {
        videoPlayer.play(
            context = appContext,
            url = mediaUrl,
            textureView = surface,
            onEnded = { mainPoster.post { onEnded() } },
            onError = { message -> mainPoster.post { onPlaybackError(message) } },
            onMutedChanged = onMutedChanged?.let { cb -> { muted -> mainPoster.post { cb(muted) } } },
            onPlayingChanged = onPlayingChanged?.let { cb ->
                { playing -> mainPoster.post { cb(playing) } }
            },
            onReady = {
                mainPoster.post {
                    if (!overlayActive) return@post
                    onReady()
                }
            },
        )
        true
    } catch (t: Throwable) {
        Log.e(logTag(), "ad player could not start", t)
        onPlaybackError(t.message ?: t.javaClass.name)
        false
    }

    /** Stops linear media and hides mute / pause. The overlay, if showing, stays. */
    protected fun stopLinearPlayback() {
        playbackChrome = false
        videoPlayer.release()
        if (overlayActive) {
            lastChrome = null
            ui.updateControls(chromeOf(chromeErid))
        }
    }

    /**
     * Hides mute / pause on a creative that played out but stays on screen. The player keeps the
     * media, and with it the last frame — releasing it here would leave a black surface.
     */
    protected fun holdEndedCreative() {
        playbackChrome = false
        if (overlayActive) {
            lastChrome = null
            ui.updateControls(chromeOf(chromeErid))
        }
    }

    /** Playback position of the linear creative; 0 when no linear media is on screen. */
    protected fun mediaPositionMs(): Long = videoPlayer.positionMs()

    /**
     * Takes the overlay off screen and ends the opportunity.
     *
     * @param onlyIfShowing true → do nothing unless a creative is on screen; false → clean up
     *   either way. A matching [Listener.onClose] is emitted whenever a show is being ended.
     */
    protected fun dismiss(onlyIfShowing: Boolean) {
        if (!overlayActive && onlyIfShowing) return
        val wasActive = overlayActive
        overlayActive = false
        playbackChrome = false
        infoUrl = null
        resetChromeCache()
        stopTicker()
        ui.hide()
        videoPlayer.release()
        onOpportunityFinished()
        if (wasActive) emitClose()
    }

    /**
     * The current opportunity has no outcome left to deliver, so a later [trigger] is a new one.
     * Called for every ending: skip, creative end, host [close], no fill, and failures.
     */
    protected open fun onOpportunityFinished() = Unit

    protected fun refreshControls(markingErid: String?) {
        val chrome = chromeOf(markingErid)
        if (chrome == lastChrome) return
        lastChrome = chrome
        ui.updateControls(chrome)
    }

    private fun chromeOf(markingErid: String?): Chrome {
        val cached = chromeMarking
        val marking = if (cached != null && markingErid == chromeErid) {
            cached
        } else {
            Templates.marking(markingTemplate, markingErid).also {
                chromeErid = markingErid
                chromeMarking = it
            }
        }
        val skipEnabled = canSkip()
        return Chrome(
            marking = marking,
            skipLabel = skipLabel(skipEnabled),
            skipEnabled = skipEnabled,
            landingLabel = landingTemplate,
            landingAvailable = landingVisible && !infoUrl.isNullOrBlank(),
            muted = videoPlayer.isMuted(),
            paused = playbackChrome && !videoPlayer.isPlaying(),
            pauseAvailable = pauseVisible && playbackChrome,
            muteAvailable = muteVisible && playbackChrome && soundEnabled,
            infoAvailable = infoVisible && !infoUrl.isNullOrBlank(),
        )
    }

    protected fun bindInfo(url: String?) {
        infoUrl = landingUrl(url)
        refreshChromeIfShowing()
    }

    protected fun showInfoQr() {
        val url = infoUrl ?: return
        ui.showQr(url)
    }

    /**
     * Hands the landing URL to the device's default browser, not the host. A new task, so the
     * browser does not land in the host back stack. TVs without a browser just log.
     */
    protected fun openLanding(): Boolean {
        val url = infoUrl ?: return false
        val uri = Uri.parse(url)
        val opened = startLanding(browserIntent(uri)) ||
            externalBrowser(uri)?.let { startLanding(it) } == true
        if (opened) {
            Log.i(logTag(), "landing opened $url")
        } else {
            Log.w(logTag(), "no app to open $url")
        }
        return opened
    }

    private fun browserIntent(uri: Uri): Intent =
        Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .apply {
                selector = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)
            }

    /** A browsable handler that is not this app and not a TV stub. */
    private fun externalBrowser(uri: Uri): Intent? {
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val self = appContext.packageName
        val target = appContext.packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo }
            .firstOrNull { info ->
                val pkg = info.packageName.orEmpty()
                pkg.isNotEmpty() &&
                    pkg != self &&
                    pkg != "android" &&
                    !pkg.contains("resolver", ignoreCase = true) &&
                    !pkg.contains("fallback", ignoreCase = true) &&
                    !pkg.contains("stub", ignoreCase = true)
            } ?: return null
        intent.setClassName(target.packageName, target.name)
        return intent
    }

    private fun startLanding(intent: Intent): Boolean = try {
        appContext.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (t: Throwable) {
        Log.e(logTag(), "landing could not be opened", t)
        false
    }

    protected fun landingUrl(raw: String?): String? {
        val t = raw?.trim().orEmpty()
        return t.takeIf {
            it.startsWith("http://", ignoreCase = true) ||
                it.startsWith("https://", ignoreCase = true)
        }
    }

    private fun refreshChromeIfShowing() {
        if (!overlayActive) return
        lastChrome = null
        ui.updateControls(chromeOf(chromeErid))
    }

    protected fun toggleAdMuted() {
        if (!overlayActive || !playbackChrome || !soundEnabled) return
        videoPlayer.setMuted(!videoPlayer.isMuted())
    }

    protected fun toggleAdPaused() {
        if (!overlayActive || !playbackChrome) return
        videoPlayer.setPlaying(!videoPlayer.isPlaying())
    }

    private fun resetChromeCache() {
        chromeErid = null
        chromeMarking = null
        lastChrome = null
    }

    protected fun skipLabel(skipEnabled: Boolean): String =
        if (skipEnabled) {
            skipTemplate
        } else {
            Templates.skipCountdown(
                skipCountdownTemplate,
                secs(remainingToSkipMs()),
            )
        }

    protected fun remainingToSkipMs(): Long =
        (skipOffsetMs - shownForMs()).coerceAtLeast(0L)

    protected fun canSkip(): Boolean = overlayActive && shownForMs() >= skipOffsetMs

    /** Wall-clock time the overlay has been on screen. */
    protected fun shownForMs(): Long = clock.currentTimeMillis() - adStartedAtMs

    /**
     * Fire-and-forget tracker GETs on the track pool (bounded parallel).
     *
     * A queued ping is not tied to the request that earned it. The show already happened, so
     * neither the host ending the opportunity nor [detach] may swallow the pixel: the task holds
     * a URL and nothing of the host, and it runs on a daemon thread with a short read timeout.
     */
    protected fun fireTrackers(urls: List<String>) {
        if (urls.isEmpty() || !attached) return
        val ua = deviceUserAgent()
        for (url in urls) {
            // Expanded per URL: every pixel gets its own cache buster and timestamp.
            val expanded = expandMacros(url)
            Safe.execute(track, logTag(), "tracker") {
                client.fireTrackers(listOf(expanded), ua)
            }
        }
    }

    /**
     * Fills the macros in an outgoing URL from the device snapshot. Trackers, the creative and
     * the ClickThrough all go through here, so a `[IFA]` in a pixel means the same thing as the
     * `${IFA}` a launcher put in its tag URL.
     */
    internal fun expandMacros(url: String): String =
        Macros.expand(url, identity.current(), clock.currentTimeMillis())

    protected fun emitOpen() {
        mainPoster.post { listener?.onOpen() }
    }

    protected fun emitClose() {
        mainPoster.post { listener?.onClose() }
    }

    protected fun emitNoAd() {
        onOpportunityFinished()
        mainPoster.post { listener?.onNoAd() }
    }

    protected fun emitError(message: String) {
        Log.e(logTag(), message)
        onOpportunityFinished()
        mainPoster.post { listener?.onError(message) }
    }

    internal fun deviceUserAgent(): String = identity.current().userAgent

    protected open fun logTag(): String = "Overlay"

    private fun startTicker() {
        if (tickerScheduled) return
        tickerScheduled = true
        scheduleTick()
    }

    private fun scheduleTick() {
        mainPoster.postDelayed(TICK_INTERVAL_MS) {
            if (!overlayActive || !attached) {
                tickerScheduled = false
                return@postDelayed
            }
            onTicker()
            scheduleTick()
        }
    }

    protected open fun onTicker() = Unit

    private fun stopTicker() {
        tickerScheduled = false
        mainPoster.cancelDelayed()
    }

    private fun releaseOwnedExecutors() {
        if (!ownsExecutors) return
        // Markup and creative work has nobody left to answer, so it is cancelled outright.
        (io as? ExecutorService)?.shutdownNow()
        // Pixels already queued are allowed to drain: daemon threads with a short read timeout,
        // holding nothing of the host. Dropping them would under-report a show that happened.
        (track as? ExecutorService)?.takeIf { it !== io }?.shutdown()
    }

    private fun restoreDebugLogging() {
        // Only undo logging this instance switched on; another slot may still need it.
        if (!debugLoggingOwned) return
        debugLoggingOwned = false
        Log.enabled = false
    }

    private fun secs(ms: Long): Int = ceil(ms / 1000.0).toInt()

    companion object {
        internal const val DEFAULT_SKIP_OFFSET_MS = 5_000L
        internal const val DEFAULT_REQUEST_TIMEOUT_MS = 10_000
        internal const val DEFAULT_MARKING_TEMPLATE = "РЕКЛАМА \${ERID}"
        internal const val DEFAULT_SKIP_COUNTDOWN_TEMPLATE = "Пропустить через \${SECONDS}"
        internal const val DEFAULT_SKIP_TEMPLATE = "Пропустить"
        internal const val DEFAULT_LANDING_TEMPLATE = "Перейти"
        internal const val TICK_INTERVAL_MS = 250L
        private const val POOL_KEEP_ALIVE_SECONDS = 30L

        internal fun defaultClient(): Client = UrlConnectionClient()

        internal fun defaultVideoPlayer(soundEnabled: Boolean): VideoPlayer =
            try {
                ExoVideoPlayer(soundEnabled)
            } catch (t: Throwable) {
                Log.e("Overlay", UnavailablePlayer.MESSAGE, t)
                UnavailablePlayer
            }

        internal fun defaultClock(): Clock = SystemClock

        internal fun defaultIdentity(appContext: Context): Identity = Identity(appContext)

        internal fun defaultMainPoster(): MainPoster = HandlerMainPoster()

        internal fun defaultIo(threadName: String): ExecutorService =
            Executors.newSingleThreadExecutor { r -> Safe.thread(threadName, r) }

        internal fun trackPoolSize(): Int =
            Runtime.getRuntime().availableProcessors().coerceIn(2, 6)

        /** Idle tracker threads are given back instead of parked for the slot's lifetime. */
        internal fun defaultTrackIo(threadName: String): ExecutorService {
            val size = trackPoolSize()
            return ThreadPoolExecutor(
                size,
                size,
                POOL_KEEP_ALIVE_SECONDS,
                TimeUnit.SECONDS,
                LinkedBlockingQueue(),
            ) { r -> Safe.thread(threadName, r) }.apply { allowCoreThreadTimeOut(true) }
        }
    }

    private fun corner(
        horizontal: ControlsHorizontal,
        vertical: ControlsVertical,
    ): Placement = Placement(horizontal.toInternal(), vertical.toInternal())

    private fun ControlsHorizontal.toInternal(): Placement.Horizontal = when (this) {
        ControlsHorizontal.LEFT -> Placement.Horizontal.LEFT
        ControlsHorizontal.RIGHT -> Placement.Horizontal.RIGHT
    }

    private fun ControlsVertical.toInternal(): Placement.Vertical = when (this) {
        ControlsVertical.TOP -> Placement.Vertical.TOP
        ControlsVertical.BOTTOM -> Placement.Vertical.BOTTOM
    }
}
