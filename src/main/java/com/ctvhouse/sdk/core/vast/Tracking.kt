package com.ctvhouse.sdk.core.vast

/**
 * One VAST `<Tracking event="…" [offset="…"]>` URL.
 * [offset] is required by spec for `progress`; ignored for other events.
 */
internal data class Tracking(
    val event: String,
    val url: String,
    val offset: Offset? = null,
)

/**
 * VAST 4 [ViewableImpression](https://iabtechlab.com/standards/vast/) children.
 * Older creatives may put a bare URL under ViewableImpression — stored in [viewable].
 */
internal data class ViewableImpression(
    val viewable: List<String> = emptyList(),
    val notViewable: List<String> = emptyList(),
    val viewUndetermined: List<String> = emptyList(),
)

/**
 * Standard Tracking `@event` values from VAST 2 / 3 / 4.0 / 4.1 / 4.2.
 * Unknown event names are still parsed and kept as-is on [Tracking.event].
 */
internal object Events {
    const val MUTE = "mute"
    const val UNMUTE = "unmute"
    const val PAUSE = "pause"
    const val RESUME = "resume"
    const val REWIND = "rewind"
    const val SKIP = "skip"
    const val PLAYER_EXPAND = "playerExpand"
    const val PLAYER_COLLAPSE = "playerCollapse"
    const val LOADED = "loaded"
    const val START = "start"
    const val FIRST_QUARTILE = "firstQuartile"
    const val MIDPOINT = "midpoint"
    const val THIRD_QUARTILE = "thirdQuartile"
    const val COMPLETE = "complete"
    const val PROGRESS = "progress"
    const val CLOSE_LINEAR = "closeLinear"
    const val CREATIVE_VIEW = "creativeView"
    const val ACCEPT_INVITATION = "acceptInvitation"
    const val AD_EXPAND = "adExpand"
    const val AD_COLLAPSE = "adCollapse"
    const val MINIMIZE = "minimize"
    const val CLOSE = "close"
    const val OVERLAY_VIEW_DURATION = "overlayViewDuration"
    const val OTHER_AD_INTERACTION = "otherAdInteraction"
    /** VAST 4.2+ (SIMID). */
    const val INTERACTIVE_START = "interactiveStart"

    /** Full catalog from VAST 4.2 XSD (+ names used since 2.0/3.0). */
    val ALL: Set<String> = setOf(
        MUTE, UNMUTE, PAUSE, RESUME, REWIND, SKIP,
        PLAYER_EXPAND, PLAYER_COLLAPSE, LOADED, START,
        FIRST_QUARTILE, MIDPOINT, THIRD_QUARTILE, COMPLETE, PROGRESS,
        CLOSE_LINEAR, CREATIVE_VIEW, ACCEPT_INVITATION,
        AD_EXPAND, AD_COLLAPSE, MINIMIZE, CLOSE,
        OVERLAY_VIEW_DURATION, OTHER_AD_INTERACTION, INTERACTIVE_START,
    )
}

internal fun List<Tracking>.urls(event: String): List<String> =
    asSequence()
        .filter { it.event.equals(event, ignoreCase = true) }
        .map { it.url }
        .toList()

/** Progress helpers keyed by elapsed playback time. */
internal fun List<Tracking>.dueProgress(elapsedMs: Long, durationMs: Long?): List<Tracking> {
    if (elapsedMs < 0L) return emptyList()
    return filter { it.event.equals(Events.PROGRESS, ignoreCase = true) }
        .mapNotNull { t ->
            val dueAt = when (val off = t.offset) {
                is Offset.Absolute -> off.ms
                is Offset.Percent -> {
                    val dur = durationMs ?: return@mapNotNull null
                    (dur * (off.value / 100.0)).toLong()
                }
                null -> return@mapNotNull null
            }
            if (elapsedMs >= dueAt) t else null
        }
}

internal fun List<Tracking>.dueProgressUrls(elapsedMs: Long, durationMs: Long?): List<String> =
    dueProgress(elapsedMs, durationMs).map { it.url }
