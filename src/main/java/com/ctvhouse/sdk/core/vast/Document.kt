package com.ctvhouse.sdk.core.vast

/** Parsed VAST document (2.x / 3.x / 4.x). Formats read what they need. */
internal data class Document(
    val version: String? = null,
    val ads: List<Ad> = emptyList(),
    val errors: List<String> = emptyList(),
) {
    val firstInLine: InLine?
        get() = ads.firstNotNullOfOrNull { it.inLine }

    companion object {
        val EMPTY = Document()
    }
}

internal data class Ad(
    val id: String? = null,
    val sequence: Int? = null,
    val inLine: InLine? = null,
    val wrapper: Wrapper? = null,
)

internal data class InLine(
    val adSystem: String? = null,
    val adTitle: String? = null,
    val impressions: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val creatives: List<Creative> = emptyList(),
    /** Resolved ERID (Extension type=ERID, bare Erid, or nroa_inform). */
    val erid: String? = null,
    val nroa: NroaInform? = null,
    val categories: List<String> = emptyList(),
    val viewableImpression: ViewableImpression = ViewableImpression(),
)

internal data class Wrapper(
    val vastAdTagUri: String? = null,
    val followAdditionalWrappers: Boolean = true,
    val impressions: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val creatives: List<Creative> = emptyList(),
    val erid: String? = null,
    val nroa: NroaInform? = null,
    val viewableImpression: ViewableImpression = ViewableImpression(),
)

internal data class Creative(
    val id: String? = null,
    val linear: Linear? = null,
    val companions: List<Companion> = emptyList(),
    val nonLinears: List<NonLinear> = emptyList(),
)

internal data class Linear(
    val skipOffset: Offset? = null,
    val durationMs: Long? = null,
    val mediaFiles: List<MediaFile> = emptyList(),
    val trackings: List<Tracking> = emptyList(),
    val clickThrough: String? = null,
    val clickTracking: List<String> = emptyList(),
    val customClick: List<String> = emptyList(),
)

internal data class Companion(
    val width: Int = 0,
    val height: Int = 0,
    val staticResourceUrl: String? = null,
    val htmlResource: String? = null,
    val iframeResourceUrl: String? = null,
    val trackings: List<Tracking> = emptyList(),
    val clickThrough: String? = null,
    val clickTracking: List<String> = emptyList(),
)

internal data class NonLinear(
    val width: Int = 0,
    val height: Int = 0,
    val staticResourceUrl: String? = null,
    val trackings: List<Tracking> = emptyList(),
    val clickThrough: String? = null,
    val clickTracking: List<String> = emptyList(),
)

internal data class NroaInform(
    val title: String? = null,
    val url: String? = null,
    val erid: String? = null,
)

/** Linear skipoffset / Tracking progress offset: absolute time or percent of duration. */
internal sealed class Offset {
    data class Absolute(val ms: Long) : Offset()
    data class Percent(val value: Float) : Offset()
}
