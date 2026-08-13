package com.ctvhouse.sdk.core.vast

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * Full VAST 2 / 3 / 4.x document parser (single streaming pass).
 * Formats extract nodes from [Document]; this type does not know ad formats.
 */
internal object Parser {

    /**
     * Parse a single VAST document (one hop). For Wrapper chains use [resolve].
     */
    fun parse(xml: String): Document {
        if (xml.isBlank()) return Document.EMPTY
        return try {
            val parser = newPullParser(sanitize(xml))
            var version: String? = null
            val ads = mutableListOf<Ad>()
            val errors = mutableListOf<String>()
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when {
                        tag(parser, "VAST") ->
                            version = parser.getAttributeValue(null, "version")
                        tag(parser, "Ad") -> ads.add(readAd(parser))
                        tag(parser, "Error") -> readText(parser)?.let { errors.add(it) }
                        else -> skip(parser)
                    }
                }
                event = parser.next()
            }
            Document(version = version, ads = ads, errors = errors)
        } catch (_: Exception) {
            Document.EMPTY
        }
    }

    /**
     * Follow Wrapper [VASTAdTagURI] chain and merge trackings / impressions / errors.
     * @param fetch loads the next VAST body; runs on the caller's thread (use fetch IO).
     */
    fun resolve(
        xml: String,
        maxDepth: Int = Resolve.DEFAULT_MAX_DEPTH,
        fetch: (url: String) -> String?,
    ): Document = Resolve.resolve(xml, fetch, maxDepth)

    /**
     * Resolving the factory goes through reflection, so it is done once and kept per thread —
     * the class itself carries parser state and is not safe to share.
     */
    private val factories = object : ThreadLocal<XmlPullParserFactory>() {
        override fun initialValue(): XmlPullParserFactory =
            XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
    }

    private fun newPullParser(xml: String): XmlPullParser =
        factories.get()!!.newPullParser().apply {
            // Namespaces off; document declaration stays unprocessed (the pull parser default),
            // so an entity-expansion payload in a third-party tag cannot grow in the host heap.
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(StringReader(xml))
        }

    private fun readAd(parser: XmlPullParser): Ad {
        val id = parser.getAttributeValue(null, "id")
        val sequence = parser.getAttributeValue(null, "sequence")?.toIntOrNull()
        var inLine: InLine? = null
        var wrapper: Wrapper? = null
        while (true) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> if (tag(parser, "Ad")) break
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> when {
                    tag(parser, "InLine") -> inLine = readInLine(parser)
                    tag(parser, "Wrapper") -> wrapper = readWrapper(parser)
                    else -> skip(parser)
                }
            }
        }
        return Ad(id = id, sequence = sequence, inLine = inLine, wrapper = wrapper)
    }

    private fun readInLine(parser: XmlPullParser): InLine {
        var adSystem: String? = null
        var adTitle: String? = null
        val impressions = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val creatives = mutableListOf<Creative>()
        val ext = ExtBag()
        val categories = mutableListOf<String>()
        var viewable = ViewableImpression()
        while (true) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> if (tag(parser, "InLine")) break
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> when {
                    tag(parser, "AdSystem") -> adSystem = readText(parser)
                    tag(parser, "AdTitle") -> adTitle = readText(parser)
                    tag(parser, "Impression") -> readText(parser)?.let { impressions.add(it) }
                    tag(parser, "Error") -> readText(parser)?.let { errors.add(it) }
                    tag(parser, "Category") -> readText(parser)?.let { categories.add(it) }
                    tag(parser, "ViewableImpression") ->
                        viewable = mergeViewable(viewable, readViewableImpression(parser))
                    tag(parser, "Creatives") -> creatives.addAll(readCreatives(parser))
                    tag(parser, "Erid") -> ext.preferErid(readText(parser))
                    tag(parser, "Extensions") -> readExtensions(parser, ext)
                    tag(parser, "Extension") -> readExtension(parser, ext)
                    else -> skip(parser)
                }
            }
        }
        return InLine(
            adSystem = adSystem,
            adTitle = adTitle,
            impressions = impressions,
            errors = errors,
            creatives = creatives,
            erid = ext.resolvedErid(),
            nroa = ext.nroa,
            categories = categories,
            viewableImpression = viewable,
        )
    }

    private fun readWrapper(parser: XmlPullParser): Wrapper {
        val follow =
            parser.getAttributeValue(null, "followAdditionalWrappers") != "false"
        var vastAdTagUri: String? = null
        val impressions = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val creatives = mutableListOf<Creative>()
        val ext = ExtBag()
        var viewable = ViewableImpression()
        while (true) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> if (tag(parser, "Wrapper")) break
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> when {
                    tag(parser, "VASTAdTagURI") -> vastAdTagUri = readText(parser)
                    tag(parser, "Impression") -> readText(parser)?.let { impressions.add(it) }
                    tag(parser, "Error") -> readText(parser)?.let { errors.add(it) }
                    tag(parser, "ViewableImpression") ->
                        viewable = mergeViewable(viewable, readViewableImpression(parser))
                    tag(parser, "Creatives") -> creatives.addAll(readCreatives(parser))
                    tag(parser, "Erid") -> ext.preferErid(readText(parser))
                    tag(parser, "Extensions") -> readExtensions(parser, ext)
                    tag(parser, "Extension") -> readExtension(parser, ext)
                    else -> skip(parser)
                }
            }
        }
        return Wrapper(
            vastAdTagUri = vastAdTagUri,
            followAdditionalWrappers = follow,
            impressions = impressions,
            errors = errors,
            creatives = creatives,
            erid = ext.resolvedErid(),
            nroa = ext.nroa,
            viewableImpression = viewable,
        )
    }

    private fun readCreatives(parser: XmlPullParser): List<Creative> {
        val creatives = mutableListOf<Creative>()
        while (true) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> if (tag(parser, "Creatives")) break
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> when {
                    tag(parser, "Creative") -> creatives.add(readCreative(parser))
                    else -> skip(parser)
                }
            }
        }
        return creatives
    }

    private fun readCreative(parser: XmlPullParser): Creative {
        val id = parser.getAttributeValue(null, "id")
        var linear: Linear? = null
        val companions = mutableListOf<Companion>()
        val nonLinears = mutableListOf<NonLinear>()
        while (true) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> if (tag(parser, "Creative")) break
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> when {
                    tag(parser, "Linear") -> linear = readLinear(parser)
                    tag(parser, "CompanionAds") ->
                        companions.addAll(readCompanions(parser))
                    tag(parser, "NonLinearAds") ->
                        nonLinears.addAll(readNonLinears(parser))
                    else -> skip(parser)
                }
            }
        }
        return Creative(
            id = id,
            linear = linear,
            companions = companions,
            nonLinears = nonLinears,
        )
    }

    private fun readLinear(parser: XmlPullParser): Linear {
        val skipOffset = parseOffset(parser.getAttributeValue(null, "skipoffset"))
        var durationMs: Long? = null
        val mediaFiles = mutableListOf<MediaFile>()
        val trackings = mutableListOf<Tracking>()
        var clickThrough: String? = null
        val clickTracking = mutableListOf<String>()
        val customClick = mutableListOf<String>()
        while (true) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> if (tag(parser, "Linear")) break
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> when {
                    tag(parser, "Duration") -> durationMs = parseDurationMs(readText(parser))
                    tag(parser, "TrackingEvents") -> trackings.addAll(readTrackingEvents(parser))
                    tag(parser, "Tracking") -> readTracking(parser)?.let { trackings.add(it) }
                    tag(parser, "MediaFiles") -> mediaFiles.addAll(readMediaFiles(parser))
                    tag(parser, "MediaFile") -> readMediaFile(parser)?.let { mediaFiles.add(it) }
                    tag(parser, "VideoClicks") -> {
                        val clicks = readVideoClicks(parser)
                        if (clicks.clickThrough != null) clickThrough = clicks.clickThrough
                        clickTracking.addAll(clicks.clickTracking)
                        customClick.addAll(clicks.customClick)
                    }
                    tag(parser, "ClickThrough") -> clickThrough = readText(parser)
                    tag(parser, "ClickTracking") ->
                        readText(parser)?.let { clickTracking.add(it) }
                    else -> skip(parser)
                }
            }
        }
        return Linear(
            skipOffset = skipOffset,
            durationMs = durationMs,
            mediaFiles = mediaFiles,
            trackings = trackings,
            clickThrough = clickThrough,
            clickTracking = clickTracking,
            customClick = customClick,
        )
    }

    private fun readMediaFiles(parser: XmlPullParser): List<MediaFile> {
        val files = mutableListOf<MediaFile>()
        while (true) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> if (tag(parser, "MediaFiles")) break
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> when {
                    tag(parser, "MediaFile") -> readMediaFile(parser)?.let { files.add(it) }
                    else -> skip(parser)
                }
            }
        }
        return files
    }

    private fun readMediaFile(parser: XmlPullParser): MediaFile? {
        val type = parser.getAttributeValue(null, "type").orEmpty()
        val width = attrInt(parser, "width")
        val height = attrInt(parser, "height")
        val delivery = parser.getAttributeValue(null, "delivery").orEmpty()
        val bitrate = attrInt(parser, "bitrate")
        val api = parser.getAttributeValue(null, "apiFramework").orEmpty()
        val url = readText(parser) ?: return null
        return MediaFile(
            url = url,
            mimeType = type,
            width = width,
            height = height,
            delivery = delivery,
            bitrate = bitrate,
            apiFramework = api,
        )
    }

    private fun readCompanions(parser: XmlPullParser): List<Companion> {
        val list = mutableListOf<Companion>()
        while (true) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> if (tag(parser, "CompanionAds")) break
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> when {
                    tag(parser, "Companion") -> list.add(readCompanion(parser))
                    else -> skip(parser)
                }
            }
        }
        return list
    }

    private fun readCompanion(parser: XmlPullParser): Companion {
        val width = attrInt(parser, "width")
        val height = attrInt(parser, "height")
        var staticUrl: String? = null
        var html: String? = null
        var iframe: String? = null
        val trackings = mutableListOf<Tracking>()
        var clickThrough: String? = null
        val clickTracking = mutableListOf<String>()
        while (true) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> if (tag(parser, "Companion")) break
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> when {
                    tag(parser, "StaticResource") -> staticUrl = readText(parser)
                    tag(parser, "HTMLResource") -> html = readText(parser)
                    tag(parser, "IFrameResource") -> iframe = readText(parser)
                    tag(parser, "TrackingEvents") -> trackings.addAll(readTrackingEvents(parser))
                    tag(parser, "Tracking") -> readTracking(parser)?.let { trackings.add(it) }
                    tag(parser, "CompanionClickThrough") -> clickThrough = readText(parser)
                    tag(parser, "CompanionClickTracking") ->
                        readText(parser)?.let { clickTracking.add(it) }
                    tag(parser, "ClickThrough") -> clickThrough = readText(parser)
                    tag(parser, "ClickTracking") ->
                        readText(parser)?.let { clickTracking.add(it) }
                    else -> skip(parser)
                }
            }
        }
        return Companion(
            width = width,
            height = height,
            staticResourceUrl = staticUrl,
            htmlResource = html,
            iframeResourceUrl = iframe,
            trackings = trackings,
            clickThrough = clickThrough,
            clickTracking = clickTracking,
        )
    }

    private fun readNonLinears(parser: XmlPullParser): List<NonLinear> {
        val list = mutableListOf<NonLinear>()
        while (true) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> if (tag(parser, "NonLinearAds")) break
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> when {
                    tag(parser, "NonLinear") -> list.add(readNonLinear(parser))
                    // NonLinearAds-level TrackingEvents belong to the group, not to a node;
                    // no format consumes them, so they are dropped rather than duplicated.
                    tag(parser, "TrackingEvents") -> skip(parser)
                    else -> skip(parser)
                }
            }
        }
        return list
    }

    private fun readNonLinear(parser: XmlPullParser): NonLinear {
        val width = attrInt(parser, "width")
        val height = attrInt(parser, "height")
        var staticUrl: String? = null
        val trackings = mutableListOf<Tracking>()
        var clickThrough: String? = null
        val clickTracking = mutableListOf<String>()
        while (true) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> if (tag(parser, "NonLinear")) break
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> when {
                    tag(parser, "StaticResource") -> staticUrl = readText(parser)
                    tag(parser, "TrackingEvents") -> trackings.addAll(readTrackingEvents(parser))
                    tag(parser, "Tracking") -> readTracking(parser)?.let { trackings.add(it) }
                    tag(parser, "NonLinearClickThrough") -> clickThrough = readText(parser)
                    tag(parser, "NonLinearClickTracking") ->
                        readText(parser)?.let { clickTracking.add(it) }
                    tag(parser, "ClickThrough") -> clickThrough = readText(parser)
                    tag(parser, "ClickTracking") ->
                        readText(parser)?.let { clickTracking.add(it) }
                    else -> skip(parser)
                }
            }
        }
        return NonLinear(
            width = width,
            height = height,
            staticResourceUrl = staticUrl,
            trackings = trackings,
            clickThrough = clickThrough,
            clickTracking = clickTracking,
        )
    }

    private data class VideoClicks(
        val clickThrough: String?,
        val clickTracking: List<String>,
        val customClick: List<String>,
    )

    private fun readVideoClicks(parser: XmlPullParser): VideoClicks {
        var through: String? = null
        val tracks = mutableListOf<String>()
        val custom = mutableListOf<String>()
        while (true) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> if (tag(parser, "VideoClicks")) break
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> when {
                    tag(parser, "ClickThrough") -> through = readText(parser)
                    tag(parser, "ClickTracking") ->
                        readText(parser)?.let { tracks.add(it) }
                    tag(parser, "CustomClick") ->
                        readText(parser)?.let { custom.add(it) }
                    else -> skip(parser)
                }
            }
        }
        return VideoClicks(through, tracks, custom)
    }

    private fun readTrackingEvents(parser: XmlPullParser): List<Tracking> {
        val list = mutableListOf<Tracking>()
        while (true) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> if (tag(parser, "TrackingEvents")) break
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> when {
                    tag(parser, "Tracking") -> readTracking(parser)?.let { list.add(it) }
                    else -> skip(parser)
                }
            }
        }
        return list
    }

    private fun readTracking(parser: XmlPullParser): Tracking? {
        val event = parser.getAttributeValue(null, "event") ?: run {
            skip(parser)
            return null
        }
        val offset = parseOffset(parser.getAttributeValue(null, "offset"))
        val url = readText(parser) ?: return null
        return Tracking(event = event, url = url, offset = offset)
    }

    private fun readViewableImpression(parser: XmlPullParser): ViewableImpression {
        val viewable = mutableListOf<String>()
        val notViewable = mutableListOf<String>()
        val undetermined = mutableListOf<String>()
        // Direct text under ViewableImpression (rare) — treat as viewable.
        val direct = StringBuilder()
        while (true) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> if (tag(parser, "ViewableImpression")) break
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.TEXT, XmlPullParser.CDSECT ->
                    parser.text?.let { direct.append(it) }
                XmlPullParser.START_TAG -> when {
                    tag(parser, "Viewable") ->
                        readText(parser)?.let { viewable.add(it) }
                    tag(parser, "NotViewable") ->
                        readText(parser)?.let { notViewable.add(it) }
                    tag(parser, "ViewUndetermined") ->
                        readText(parser)?.let { undetermined.add(it) }
                    else -> skip(parser)
                }
            }
        }
        direct.toString().trim().takeIf { it.startsWith("http") }?.let { viewable.add(0, it) }
        return ViewableImpression(
            viewable = viewable,
            notViewable = notViewable,
            viewUndetermined = undetermined,
        )
    }

    private fun mergeViewable(a: ViewableImpression, b: ViewableImpression): ViewableImpression =
        ViewableImpression(
            viewable = a.viewable + b.viewable,
            notViewable = a.notViewable + b.notViewable,
            viewUndetermined = a.viewUndetermined + b.viewUndetermined,
        )

    private fun readExtensions(parser: XmlPullParser, into: ExtBag) {
        while (true) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> if (tag(parser, "Extensions")) break
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> when {
                    tag(parser, "Extension") -> readExtension(parser, into)
                    else -> skip(parser)
                }
            }
        }
    }

    private fun readExtension(parser: XmlPullParser, into: ExtBag) {
        val type = parser.getAttributeValue(null, "type").orEmpty()
        when {
            type.equals("ERID", ignoreCase = true) -> {
                // <Extension type="ERID">token</Extension> and/or nested <Erid>
                val direct = StringBuilder()
                while (true) {
                    when (parser.next()) {
                        XmlPullParser.END_TAG -> if (tag(parser, "Extension")) break
                        XmlPullParser.END_DOCUMENT -> break
                        XmlPullParser.TEXT, XmlPullParser.CDSECT ->
                            parser.text?.let { direct.append(it) }
                        XmlPullParser.START_TAG -> when {
                            tag(parser, "Erid") -> into.preferErid(readText(parser))
                            else -> skip(parser)
                        }
                    }
                }
                into.preferErid(direct.toString().trim().takeIf { it.isNotBlank() })
            }
            type.equals("nroa_inform", ignoreCase = true) -> {
                var title: String? = null
                var url: String? = null
                var erid: String? = null
                while (true) {
                    when (parser.next()) {
                        XmlPullParser.END_TAG -> if (tag(parser, "Extension")) break
                        XmlPullParser.END_DOCUMENT -> break
                        XmlPullParser.START_TAG -> when {
                            tag(parser, "Title") -> title = readText(parser)
                            tag(parser, "Url") -> url = readText(parser)
                            tag(parser, "Erid") -> erid = readText(parser)
                            else -> skip(parser)
                        }
                    }
                }
                if (title != null || url != null || erid != null) {
                    into.nroa = NroaInform(title = title, url = url, erid = erid)
                    into.preferErid(erid)
                }
            }
            else -> {
                while (true) {
                    when (parser.next()) {
                        XmlPullParser.END_TAG -> if (tag(parser, "Extension")) break
                        XmlPullParser.END_DOCUMENT -> break
                        XmlPullParser.START_TAG -> when {
                            tag(parser, "Erid") -> into.preferErid(readText(parser))
                            else -> skip(parser)
                        }
                    }
                }
            }
        }
    }

    private class ExtBag {
        var erid: String? = null
        var nroa: NroaInform? = null

        fun preferErid(value: String?) {
            if (!value.isNullOrBlank() && erid == null) erid = value.trim()
        }

        fun resolvedErid(): String? = erid ?: nroa?.erid
    }

    private fun readText(parser: XmlPullParser): String? {
        val sb = StringBuilder()
        while (true) {
            when (parser.next()) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT ->
                    parser.text?.let { sb.append(it) }
                else -> break
            }
        }
        return sb.toString().trim().takeIf { it.isNotBlank() }
    }

    private fun skip(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    private fun tag(parser: XmlPullParser, name: String): Boolean =
        parser.name.equals(name, ignoreCase = true)

    /** Ad servers put tabs and newlines inside numeric attributes. */
    private fun attrInt(parser: XmlPullParser, name: String): Int =
        parser.getAttributeValue(null, name)?.trim()?.toIntOrNull() ?: 0

    private fun sanitize(xml: String): String {
        val noBom = xml.removePrefix("\uFEFF")
        val start = noBom.indexOf('<')
        return if (start > 0) noBom.substring(start) else noBom
    }

    internal fun parseDurationMs(value: String?): Long? {
        val parts = value?.trim()?.split(":") ?: return null
        if (parts.size != 3) return null
        return try {
            val h = parts[0].toLong()
            val m = parts[1].toLong()
            val s = parts[2].toDouble()
            ((h * 3600 + m * 60) * 1000 + (s * 1000).toLong())
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseOffset(raw: String?): Offset? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        if (trimmed.endsWith("%")) {
            val pct = trimmed.dropLast(1).toFloatOrNull() ?: return null
            return Offset.Percent(pct)
        }
        val ms = parseDurationMs(trimmed) ?: return null
        return Offset.Absolute(ms)
    }
}
