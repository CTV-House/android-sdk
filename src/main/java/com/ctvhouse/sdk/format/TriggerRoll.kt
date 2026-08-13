package com.ctvhouse.sdk.format

import android.content.Context
import android.view.ViewGroup
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import com.ctvhouse.sdk.core.identity.Identity
import com.ctvhouse.sdk.core.media.BitmapLoader
import com.ctvhouse.sdk.core.media.UrlBitmapLoader
import com.ctvhouse.sdk.core.net.Client
import com.ctvhouse.sdk.core.player.VideoPlayer
import com.ctvhouse.sdk.core.runtime.Clock
import com.ctvhouse.sdk.core.runtime.Log
import com.ctvhouse.sdk.core.runtime.MainPoster
import com.ctvhouse.sdk.core.runtime.Safe
import com.ctvhouse.sdk.core.ui.Controls
import com.ctvhouse.sdk.core.ui.Ui
import com.ctvhouse.sdk.core.ui.ViewUi
import com.ctvhouse.sdk.core.vast.Document
import com.ctvhouse.sdk.core.vast.Events
import com.ctvhouse.sdk.core.vast.MediaFiles
import com.ctvhouse.sdk.core.vast.Offset
import com.ctvhouse.sdk.core.vast.Parser
import com.ctvhouse.sdk.core.vast.Tracking
import com.ctvhouse.sdk.core.vast.dueProgress
import com.ctvhouse.sdk.core.vast.urls
import java.util.concurrent.Executor

/**
 * TriggerRoll — overlay format driven by a host opportunity (`trigger` / `close`).
 *
 * The creative comes from a [Controller]. [com.ctvhouse.sdk.manual.PauseRollAd] is one
 * launcher: it fetches a VAST tag URL and hands the markup in.
 */
class TriggerRoll internal constructor(
    appContext: Context,
    ui: Ui,
    client: Client,
    private val bitmapLoader: BitmapLoader,
    videoPlayer: VideoPlayer,
    clock: Clock,
    mainPoster: MainPoster,
    io: Executor,
    track: Executor,
    identity: Identity = Identity(appContext),
    ownsExecutors: Boolean = false,
) : Overlay<TriggerRoll, TriggerRoll.Listener>(
    appContext = appContext,
    ui = ui,
    client = client,
    videoPlayer = videoPlayer,
    clock = clock,
    mainPoster = mainPoster,
    io = io,
    track = track,
    identity = identity,
    ownsExecutors = ownsExecutors,
) {
    /** Supplies the creative for a pause opportunity. */
    fun interface Controller {
        fun onOpportunity(reason: String, sink: Sink)
    }

    /** Answer channel for one opportunity. Safe to call from any thread; answer once. */
    interface Sink {
        fun deliverXml(vastXml: String)
        fun noAd()
        fun error(message: String)
    }

    /** VAST `progress@offset` for [Listener.onProgress]. */
    sealed class ProgressOffset {
        data class Absolute(val ms: Long) : ProgressOffset()
        data class Percent(val value: Float) : ProgressOffset()
    }

    /**
     * Advertising events (VAST 2–4.2). Tracker callbacks receive the URLs the library pings.
     * Slot window is the inherited [Overlay.Listener.onOpen] / [Overlay.Listener.onClose]
     * (no-arg). [onClose] below is VAST `close`.
     */
    interface Listener : Overlay.Listener {
        fun onImpression(urls: List<String>) {}
        fun onVastError(urls: List<String>) {}
        fun onViewable(urls: List<String>) {}
        fun onNotViewable(urls: List<String>) {}

        fun onMute(urls: List<String>) {}
        fun onUnmute(urls: List<String>) {}
        fun onPause(urls: List<String>) {}
        fun onResume(urls: List<String>) {}
        fun onRewind(urls: List<String>) {}
        fun onSkip(urls: List<String>) {}
        fun onPlayerExpand(urls: List<String>) {}
        fun onPlayerCollapse(urls: List<String>) {}
        fun onLoaded(urls: List<String>) {}
        fun onStart(urls: List<String>) {}
        fun onFirstQuartile(urls: List<String>) {}
        fun onMidpoint(urls: List<String>) {}
        fun onThirdQuartile(urls: List<String>) {}
        fun onComplete(urls: List<String>) {}
        fun onProgress(offset: ProgressOffset, urls: List<String>) {}
        fun onCloseLinear(urls: List<String>) {}
        fun onCreativeView(urls: List<String>) {}
        fun onAcceptInvitation(urls: List<String>) {}
        fun onAdExpand(urls: List<String>) {}
        fun onAdCollapse(urls: List<String>) {}
        fun onMinimize(urls: List<String>) {}
        fun onClose(urls: List<String>) {}
        fun onOverlayViewDuration(urls: List<String>) {}
        fun onOtherAdInteraction(urls: List<String>) {}
        fun onInteractiveStart(urls: List<String>) {}

        /** Unknown / custom `@event`. */
        fun onTracking(event: String, urls: List<String>) {}
    }

    /** Format-local extract from [Document] — not a VAST model. */
    private data class Creative(
        val mediaUrl: String?,
        val mediaVisual: Boolean,
        val soundtrackUrl: String?,
        val companionImageUrl: String?,
        val erid: String?,
        val impressionUrls: List<String>,
        val errorUrls: List<String>,
        val viewableUrls: List<String>,
        val notViewableUrls: List<String>,
        val trackings: List<Tracking>,
        val progressTrackings: List<Tracking>,
        val durationMs: Long?,
        val skipOffsetMs: Long?,
        val linearClickThrough: String?,
        val linearClickTracking: List<String>,
        val companionClickThrough: String?,
        val companionClickTracking: List<String>,
    ) {
        val hasFill: Boolean
            get() = !mediaUrl.isNullOrBlank() || !companionImageUrl.isNullOrBlank()
        val hasLinearMedia: Boolean
            get() = !mediaUrl.isNullOrBlank()
        val hasCompanionImage: Boolean
            get() = !companionImageUrl.isNullOrBlank()
    }

    private var controller: Controller? = null
    /** True while a skip is notifying the host, so [close] inside [Listener.onSkip] is not a content-close. */
    private var skipping = false
    private var chromeBridge: ChromeBridge? = null
    private var requestedForOpportunity = false
    private var creative = emptyCreative()
    private val firedEvents = mutableSetOf<String>()
    private val firedProgressUrls = mutableSetOf<String>()
    private var adPlaying: Boolean? = null
    private var showedOverlay = false
    private var linearCompleted = false
    private var viewableFired = false
    private var clickTrackingUrls: List<String> = emptyList()

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
        Bootstrap(overlayContainer, soundEnabled),
    )

    private constructor(boot: Bootstrap) : this(
        appContext = boot.appContext,
        ui = boot.ui,
        client = defaultClient(),
        bitmapLoader = UrlBitmapLoader(),
        videoPlayer = defaultVideoPlayer(boot.soundEnabled),
        clock = defaultClock(),
        mainPoster = defaultMainPoster(),
        io = defaultIo("ctv-sdk-triggerroll-io"),
        track = defaultTrackIo("ctv-sdk-triggerroll-track"),
        identity = defaultIdentity(boot.appContext),
        ownsExecutors = true,
    ) {
        boot.bindChrome(this)
        chromeBridge = boot.chrome
        setSoundEnabled(boot.soundEnabled)
    }

    fun setController(controller: Controller?): TriggerRoll {
        this.controller = controller
        return this
    }

    fun setListener(listener: Listener?): TriggerRoll {
        this.listener = listener
        return this
    }

    internal override fun onTrigger(reason: String) {
        if (requestedForOpportunity) return
        val ctrl = controller
        if (ctrl == null) {
            emitError("TriggerRoll controller is not set")
            return
        }
        requestedForOpportunity = true
        val seq = requestSeq.incrementAndGet()
        Log.i(logTag(), "opportunity ($reason)")
        Safe.run(logTag(), "controller callback") { ctrl.onOpportunity(reason, sink(seq)) }
    }

    internal override fun onTriggerCancelled() {
        requestedForOpportunity = false
        requestSeq.incrementAndGet()
        if (overlayActive && !skipping) {
            finishDismiss(skipped = false)
        }
    }

    override fun onTicker() {
        refreshControls(creative.erid)
        fireViewableWhenDue()
        if (dismissStillWhenDue()) return
        if (!overlayActive || !playbackChrome) return
        // Creative position, not wall clock: buffering must not advance the quartiles.
        val elapsed = mediaPositionMs()
        val duration = creative.durationMs
        if (duration != null && duration > 0L) {
            fireEventOnce(Events.FIRST_QUARTILE, elapsed >= duration / 4)
            fireEventOnce(Events.MIDPOINT, elapsed >= duration / 2)
            fireEventOnce(Events.THIRD_QUARTILE, elapsed >= duration * 3 / 4)
        }
        if (creative.progressTrackings.isEmpty()) return
        creative.progressTrackings.dueProgress(elapsed, duration).forEach { tracking ->
            if (firedProgressUrls.add(tracking.url)) {
                pingProgress(tracking)
            }
        }
    }

    override fun logTag(): String = TAG

    /**
     * A show that ended by itself — skip, creative end, no fill, playback failure — closes the
     * opportunity as surely as the host calling `close`, so the next [trigger] asks again.
     */
    override fun onOpportunityFinished() {
        requestedForOpportunity = false
    }

    override fun onDetached() {
        controller = null
        creative = emptyCreative()
        firedEvents.clear()
        firedProgressUrls.clear()
        showedOverlay = false
        linearCompleted = false
        viewableFired = false
        adPlaying = null
        requestedForOpportunity = false
        clickTrackingUrls = emptyList()
        chromeBridge?.clear()
        chromeBridge = null
        skipping = false
    }

    @VisibleForTesting
    internal fun onSkipFromUi() {
        if (!canSkip()) return
        skipping = true
        pingEvent(Events.SKIP)
        finishDismiss(skipped = true)
        skipping = false
    }

    @VisibleForTesting
    internal fun onMuteFromUi() {
        toggleAdMuted()
        refreshControls(creative.erid)
    }

    @VisibleForTesting
    internal fun onPauseFromUi() {
        toggleAdPaused()
        refreshControls(creative.erid)
    }

    @VisibleForTesting
    internal fun onInfoFromUi() {
        if (!overlayActive || infoUrl == null) return
        fireTrackers(clickTrackingUrls)
        showInfoQr()
    }

    private fun sink(seq: Int): Sink = object : Sink {
        override fun deliverXml(vastXml: String) {
            onMain { if (stillWanted(seq)) startResolve(vastXml, seq) }
        }

        override fun noAd() {
            onMain { if (stillWanted(seq)) emitNoAd() }
        }

        override fun error(message: String) {
            onMain {
                if (!stillWanted(seq)) return@onMain
                pingVastError(creative.errorUrls)
                emitError(message)
            }
        }
    }

    /**
     * A [Controller] may answer from any thread, but the overlay is main-thread only —
     * hop before touching it.
     */
    private fun onMain(block: () -> Unit) {
        mainPoster.post(block)
    }

    private fun stillWanted(seq: Int): Boolean =
        seq == requestSeq.get() && attached

    private fun startResolve(vastXml: String, seq: Int) {
        val ua = deviceUserAgent()
        val timeout = requestTimeoutMs
        Safe.execute(io, logTag(), "resolve VAST") {
            val doc = Parser.resolve(vastXml) { url -> client.fetchText(url, timeout, ua) }
            mainPoster.post {
                if (!stillWanted(seq)) return@post
                applyDocument(doc, seq)
            }
        }
    }

    private fun applyDocument(doc: Document, seq: Int) {
        val extracted = extract(doc)
        creative = extracted
        errorTrackerUrls = extracted.errorUrls
        firedEvents.clear()
        firedProgressUrls.clear()
        adPlaying = null
        showedOverlay = false
        linearCompleted = false
        viewableFired = false
        skipOffsetMs = skipOffsetOverrideMs
            ?: extracted.skipOffsetMs
            ?: DEFAULT_SKIP_OFFSET_MS
        if (!extracted.hasFill) {
            Log.i(logTag(), "NO_AD — no MediaFile / companion")
            if (extracted.errorUrls.isNotEmpty()) {
                pingVastError(extracted.errorUrls)
            }
            emitNoAd()
            return
        }
        // PauseRoll tags from the ad server ship both a Linear leftover and a fullscreen
        // companion. The still is the pause creative; video is used only when there is none.
        if (extracted.hasCompanionImage) {
            loadAndShowImage(seq, extracted.companionImageUrl.orEmpty())
        } else {
            showLinear(extracted)
        }
    }

    private fun showLinear(extracted: Creative) {
        bindClicks(useCompanion = false)
        val started = showLinearCreative(
            mediaUrl = extracted.mediaUrl.orEmpty(),
            markingErid = extracted.erid,
            visual = extracted.mediaVisual,
            onEnded = {
                if (!overlayActive) return@showLinearCreative
                linearCompleted = true
                if (dismissesAtCreativeEnd()) {
                    finishDismiss(skipped = false)
                } else {
                    // The host wants the creative held: report the playout now and keep the last
                    // frame up, so skip is what ends the show.
                    fireEventOnce(Events.COMPLETE, true)
                    fireEventOnce(Events.CLOSE_LINEAR, true)
                    holdEndedCreative()
                }
            },
            onPlaybackError = { message ->
                pingVastError(errorTrackerUrls)
                emitError("Ad media error: $message")
                if (overlayActive) dismiss(onlyIfShowing = true)
            },
            onMutedChanged = { muted ->
                if (!overlayActive) return@showLinearCreative
                if (muted) pingEvent(Events.MUTE) else pingEvent(Events.UNMUTE)
            },
            onPlayingChanged = { playing ->
                if (!overlayActive) return@showLinearCreative
                val prev = adPlaying
                adPlaying = playing
                if (prev == null) return@showLinearCreative
                if (!playing && prev) pingEvent(Events.PAUSE)
                if (playing && !prev) pingEvent(Events.RESUME)
            },
        )
        if (!started) return
        showedOverlay = true
        fireShowTrackers(linear = true)
        afterShow()
    }

    private fun extract(doc: Document): Creative {
        val inline = doc.firstInLine
        if (inline == null) {
            return emptyCreative().copy(errorUrls = doc.errors)
        }
        val linear = inline.creatives.mapNotNull { it.linear }.firstOrNull()
        val companions = inline.creatives.flatMap { it.companions }
        val nonLinears = inline.creatives.flatMap { it.nonLinears }
        val companion = companions.firstOrNull {
            it.staticResourceUrl.orEmpty().startsWith("http")
        }
        val companionUrl = companion?.staticResourceUrl
        val trackings = buildList {
            linear?.trackings?.let { addAll(it) }
            companions.forEach { addAll(it.trackings) }
            nonLinears.forEach { addAll(it.trackings) }
        }
        val skipMs = when (val off = linear?.skipOffset) {
            is Offset.Absolute -> off.ms
            else -> null
        }
        val best = MediaFiles.selectBest(linear?.mediaFiles.orEmpty())
        val soundtrack = MediaFiles.selectSoundtrack(linear?.mediaFiles.orEmpty())
        val viewable = inline.viewableImpression
        // Creative and landing URLs carry macros as often as trackers do, so they are filled here,
        // once per response, rather than at every place that later reads them.
        return Creative(
            mediaUrl = best?.url?.let { expandMacros(it) },
            mediaVisual = best?.isVideo == true,
            soundtrackUrl = soundtrack?.url?.let { expandMacros(it) },
            companionImageUrl = companionUrl?.let { expandMacros(it) },
            erid = inline.erid,
            impressionUrls = inline.impressions,
            errorUrls = (inline.errors + doc.errors).distinct(),
            viewableUrls = viewable.viewable,
            notViewableUrls = viewable.notViewable,
            trackings = trackings,
            progressTrackings = trackings.filter {
                it.event.equals(Events.PROGRESS, ignoreCase = true)
            },
            durationMs = linear?.durationMs,
            skipOffsetMs = skipMs,
            linearClickThrough = landingUrl(linear?.clickThrough)?.let { expandMacros(it) },
            linearClickTracking = linear?.clickTracking.orEmpty(),
            companionClickThrough = landingUrl(companion?.clickThrough)?.let { expandMacros(it) },
            companionClickTracking = companion?.clickTracking.orEmpty(),
        )
    }

    private fun bindClicks(useCompanion: Boolean) {
        val companionUrl = creative.companionClickThrough
        val url: String?
        val tracking: List<String>
        if (useCompanion && companionUrl != null) {
            url = companionUrl
            tracking = creative.companionClickTracking
        } else {
            url = creative.linearClickThrough
            tracking = creative.linearClickTracking
        }
        clickTrackingUrls = tracking
        bindInfo(url)
    }

    private fun fireShowTrackers(linear: Boolean) {
        pingUrls(creative.impressionUrls) { onImpression(it) }
        pingEvent(Events.CREATIVE_VIEW)
        pingEvent(Events.LOADED)
        if (linear) pingEvent(Events.START)
    }

    /**
     * A still has no playback that can end, so its display duration ends it: the `Duration` the
     * response carries, otherwise what the host configured. Only while nothing is playing — the end
     * of a soundtrack is the end of that creative.
     *
     * Returns true when the show was taken down, so the caller stops touching it.
     */
    private fun dismissStillWhenDue(): Boolean {
        if (!overlayActive || playbackChrome || !dismissesAtCreativeEnd()) return false
        val duration = stillDurationMs()
        if (duration <= 0L || shownForMs() < duration) return false
        Log.i(logTag(), "still ended after ${duration}ms")
        finishDismiss(skipped = false)
        return true
    }

    private fun stillDurationMs(): Long =
        creative.durationMs?.takeIf { it > 0L } ?: bannerDurationMs()

    /** ViewableImpression is earned by staying on screen, not by being rendered once. */
    private fun fireViewableWhenDue() {
        if (viewableFired || !overlayActive) return
        if (shownForMs() < VIEWABLE_AFTER_MS) return
        viewableFired = true
        pingUrls(creative.viewableUrls) { onViewable(it) }
    }

    private fun finishDismiss(skipped: Boolean) {
        if (!overlayActive) return
        if (showedOverlay) {
            pingEvent(Events.OVERLAY_VIEW_DURATION)
            if (!viewableFired) {
                pingUrls(creative.notViewableUrls) { onNotViewable(it) }
            }
        }
        if (!skipped) {
            // complete/closeLinear describe a linear creative that actually played out.
            if (linearCompleted) {
                fireEventOnce(Events.COMPLETE, true)
                fireEventOnce(Events.CLOSE_LINEAR, true)
            }
            pingEvent(Events.CLOSE)
        }
        dismiss(onlyIfShowing = false)
        showedOverlay = false
        linearCompleted = false
        adPlaying = null
        clickTrackingUrls = emptyList()
    }

    private fun fireEventOnce(event: String, due: Boolean) {
        if (!due || !firedEvents.add(event)) return
        pingEvent(event)
    }

    private fun pingEvent(event: String) {
        val urls = creative.trackings.urls(event)
        if (urls.isEmpty()) return
        fireTrackers(urls)
        notifyTracking(event, urls)
    }

    private fun pingProgress(tracking: Tracking) {
        val urls = listOf(tracking.url)
        fireTrackers(urls)
        val offset = when (val off = tracking.offset) {
            is Offset.Absolute -> ProgressOffset.Absolute(off.ms)
            is Offset.Percent -> ProgressOffset.Percent(off.value)
            null -> return
        }
        mainPoster.post { listener?.onProgress(offset, urls) }
    }

    private fun pingVastError(urls: List<String>) {
        pingUrls(urls) { onVastError(it) }
    }

    private fun pingUrls(urls: List<String>, notify: Listener.(List<String>) -> Unit) {
        if (urls.isEmpty()) return
        fireTrackers(urls)
        mainPoster.post { listener?.notify(urls) }
    }

    private fun notifyTracking(event: String, urls: List<String>) {
        val callback = CALLBACKS[event.trim().lowercase()]
        mainPoster.post {
            val l = listener ?: return@post
            if (callback != null) callback(l, urls) else l.onTracking(event, urls)
        }
    }

    private fun loadAndShowImage(seq: Int, imageUrl: String) {
        val ua = deviceUserAgent()
        val timeout = requestTimeoutMs
        Safe.execute(io, logTag(), "load companion") {
            val bitmap = bitmapLoader.load(imageUrl, timeout, ua)
            mainPoster.post {
                if (!stillWanted(seq)) return@post
                if (bitmap == null) {
                    Log.w(logTag(), "companion image unavailable: $imageUrl")
                    if (creative.hasLinearMedia) {
                        Log.i(logTag(), "companion failed — falling back to linear")
                        showLinear(creative)
                        return@post
                    }
                    // Never put an empty overlay over host content, and never count that show.
                    pingVastError(creative.errorUrls)
                    emitNoAd()
                    return@post
                }
                bindClicks(useCompanion = true)
                showImageCreative(bitmap, creative.erid)
                showedOverlay = true
                val soundtrack = creative.soundtrackUrl
                if (!soundtrack.isNullOrBlank()) {
                    playBackgroundAudio(
                        mediaUrl = soundtrack,
                        markingErid = creative.erid,
                        onEnded = {
                            if (!overlayActive) return@playBackgroundAudio
                            linearCompleted = true
                            fireEventOnce(Events.COMPLETE, true)
                            fireEventOnce(Events.CLOSE_LINEAR, true)
                            stopLinearPlayback()
                            // The soundtrack is how long this still was meant to be watched.
                            if (dismissesAtCreativeEnd()) finishDismiss(skipped = false)
                        },
                        onPlaybackError = { message ->
                            pingVastError(errorTrackerUrls)
                            emitError("Ad media error: $message")
                            stopLinearPlayback()
                        },
                        onMutedChanged = { muted ->
                            if (!overlayActive) return@playBackgroundAudio
                            if (muted) pingEvent(Events.MUTE) else pingEvent(Events.UNMUTE)
                        },
                        onPlayingChanged = { playing ->
                            if (!overlayActive) return@playBackgroundAudio
                            val prev = adPlaying
                            adPlaying = playing
                            if (prev == null) return@playBackgroundAudio
                            if (!playing && prev) pingEvent(Events.PAUSE)
                            if (playing && !prev) pingEvent(Events.RESUME)
                        },
                    )
                    fireShowTrackers(linear = true)
                } else {
                    fireShowTrackers(linear = false)
                }
                afterShow()
            }
        }
    }

    private class Bootstrap(
        overlayContainer: ViewGroup,
        val soundEnabled: Boolean,
    ) {
        val appContext: Context = overlayContainer.context.applicationContext
        val chrome = ChromeBridge()
        val ui: Ui = ViewUi(
            overlayContainer,
            Controls.Actions(
                onSkip = { chrome.onSkip() },
                onMuteToggle = { chrome.onMute() },
                onPauseToggle = { chrome.onPause() },
                onInfo = { chrome.onInfo() },
            ),
        )

        fun bindChrome(ad: TriggerRoll) {
            chrome.skip = ad::onSkipFromUi
            chrome.mute = ad::onMuteFromUi
            chrome.pause = ad::onPauseFromUi
            chrome.info = ad::onInfoFromUi
        }
    }

    private class ChromeBridge {
        var skip: (() -> Unit)? = null
        var mute: (() -> Unit)? = null
        var pause: (() -> Unit)? = null
        var info: (() -> Unit)? = null
        fun onSkip() { skip?.invoke() }
        fun onMute() { mute?.invoke() }
        fun onPause() { pause?.invoke() }
        fun onInfo() { info?.invoke() }
        fun clear() {
            skip = null
            mute = null
            pause = null
            info = null
        }
    }

    companion object {
        private const val TAG = "TriggerRoll"

        /** Overlay must hold the screen this long before a viewable impression is earned. */
        private const val VIEWABLE_AFTER_MS = 2_000L

        /** `@event` (lower-cased) → listener callback. Anything absent goes to `onTracking`. */
        private val CALLBACKS: Map<String, Listener.(List<String>) -> Unit> = listOf(
            on(Events.MUTE) { onMute(it) },
            on(Events.UNMUTE) { onUnmute(it) },
            on(Events.PAUSE) { onPause(it) },
            on(Events.RESUME) { onResume(it) },
            on(Events.REWIND) { onRewind(it) },
            on(Events.SKIP) { onSkip(it) },
            on(Events.PLAYER_EXPAND) { onPlayerExpand(it) },
            on("fullscreen") { onPlayerExpand(it) },
            on("expand") { onPlayerExpand(it) },
            on(Events.PLAYER_COLLAPSE) { onPlayerCollapse(it) },
            on("exitfullscreen") { onPlayerCollapse(it) },
            on("collapse") { onPlayerCollapse(it) },
            on(Events.LOADED) { onLoaded(it) },
            on(Events.START) { onStart(it) },
            on(Events.FIRST_QUARTILE) { onFirstQuartile(it) },
            on(Events.MIDPOINT) { onMidpoint(it) },
            on(Events.THIRD_QUARTILE) { onThirdQuartile(it) },
            on(Events.COMPLETE) { onComplete(it) },
            on(Events.CLOSE_LINEAR) { onCloseLinear(it) },
            on(Events.CREATIVE_VIEW) { onCreativeView(it) },
            on(Events.ACCEPT_INVITATION) { onAcceptInvitation(it) },
            on(Events.AD_EXPAND) { onAdExpand(it) },
            on(Events.AD_COLLAPSE) { onAdCollapse(it) },
            on(Events.MINIMIZE) { onMinimize(it) },
            on(Events.CLOSE) { onClose(it) },
            on(Events.OVERLAY_VIEW_DURATION) { onOverlayViewDuration(it) },
            on(Events.OTHER_AD_INTERACTION) { onOtherAdInteraction(it) },
            on(Events.INTERACTIVE_START) { onInteractiveStart(it) },
            on(Events.PROGRESS) { },
        ).toMap()

        private fun on(
            event: String,
            callback: Listener.(List<String>) -> Unit,
        ): Pair<String, Listener.(List<String>) -> Unit> = event.lowercase() to callback

        private fun emptyCreative() = Creative(
            mediaUrl = null,
            mediaVisual = true,
            soundtrackUrl = null,
            companionImageUrl = null,
            erid = null,
            impressionUrls = emptyList(),
            errorUrls = emptyList(),
            viewableUrls = emptyList(),
            notViewableUrls = emptyList(),
            trackings = emptyList(),
            progressTrackings = emptyList(),
            durationMs = null,
            skipOffsetMs = null,
            linearClickThrough = null,
            linearClickTracking = emptyList(),
            companionClickThrough = null,
            companionClickTracking = emptyList(),
        )
    }
}
