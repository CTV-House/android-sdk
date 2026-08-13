package com.ctvhouse.sdk.core.vast

/**
 * Resolves Wrapper chains and merges impressions / errors / viewable / trackings
 * from all VAST versions into a single [Document] with one [InLine].
 */
internal object Resolve {

    const val DEFAULT_MAX_DEPTH = 5

    /**
     * @param fetch loads next VAST XML by [Wrapper.vastAdTagURI]; null/blank aborts chain
     */
    fun resolve(
        xml: String,
        fetch: (url: String) -> String?,
        maxDepth: Int = DEFAULT_MAX_DEPTH,
    ): Document {
        val acc = Acc()
        return hop(xml = xml, fetch = fetch, acc = acc, depth = 0, maxDepth = maxDepth)
    }

    private fun hop(
        xml: String,
        fetch: (String) -> String?,
        acc: Acc,
        depth: Int,
        maxDepth: Int,
    ): Document {
        val doc = Parser.parse(xml)
        acc.addErrors(doc.errors)
        val ad = doc.ads.firstOrNull()
            ?: return finishedErrors(doc.version, acc)

        ad.inLine?.let { inline ->
            return mergeFinal(doc.version, acc, inline)
        }

        val wrapper = ad.wrapper ?: return finishedErrors(doc.version, acc)
        acc.addWrapper(wrapper)

        val uri = wrapper.vastAdTagUri?.trim().orEmpty()
        if (uri.isEmpty() || depth >= maxDepth) {
            return finishedErrors(doc.version, acc)
        }

        val nextXml = fetch(uri)
        if (nextXml.isNullOrBlank()) {
            return finishedErrors(doc.version, acc)
        }

        if (!wrapper.followAdditionalWrappers) {
            val next = Parser.parse(nextXml)
            acc.addErrors(next.errors)
            val inline = next.ads.firstOrNull()?.inLine
            return if (inline != null) {
                mergeFinal(next.version, acc, inline)
            } else {
                finishedErrors(next.version, acc)
            }
        }

        return hop(nextXml, fetch, acc, depth = depth + 1, maxDepth = maxDepth)
    }

    private fun finishedErrors(version: String?, acc: Acc): Document {
        val errors = acc.errors.dedupe()
        return Document(version = version, ads = emptyList(), errors = errors)
    }

    private fun mergeFinal(version: String?, acc: Acc, inline: InLine): Document {
        val allErrors = (acc.errors + inline.errors).dedupe()
        val impressions = (acc.impressions + inline.impressions).dedupe()
        val viewable = mergeViewable(acc.viewable, inline.viewableImpression)
        val erid = inline.erid ?: acc.erid

        val baseCreatives = inline.creatives.ifEmpty {
            listOf(Creative(linear = Linear()))
        }

        val creatives = baseCreatives.mapIndexed { index, creative ->
            if (index != 0) {
                creative
            } else {
                val linear = creative.linear ?: Linear()
                creative.copy(
                    linear = linear.copy(
                        trackings = acc.trackings + linear.trackings,
                        clickTracking = acc.clickTracking + linear.clickTracking,
                        customClick = acc.customClick + linear.customClick,
                    ),
                    companions = mergeCompanions(acc, creative.companions),
                    nonLinears = mergeNonLinears(acc, creative.nonLinears),
                )
            }
        }

        val merged = inline.copy(
            impressions = impressions,
            errors = allErrors,
            viewableImpression = viewable,
            erid = erid,
            creatives = creatives,
        )
        return Document(
            version = version,
            ads = listOf(Ad(inLine = merged)),
            errors = allErrors,
        )
    }

    private fun mergeCompanions(acc: Acc, companions: List<Companion>): List<Companion> {
        if (acc.companionTrackings.isEmpty() && acc.companionClicks.isEmpty()) return companions
        if (companions.isEmpty()) {
            return listOf(
                Companion(
                    trackings = acc.companionTrackings.toList(),
                    clickTracking = acc.companionClicks.toList(),
                ),
            )
        }
        val first = companions.first()
        return listOf(
            first.copy(
                trackings = acc.companionTrackings + first.trackings,
                clickTracking = acc.companionClicks + first.clickTracking,
            ),
        ) + companions.drop(1)
    }

    private fun mergeNonLinears(acc: Acc, items: List<NonLinear>): List<NonLinear> {
        if (acc.nonLinearTrackings.isEmpty() && acc.nonLinearClicks.isEmpty()) return items
        if (items.isEmpty()) {
            return listOf(
                NonLinear(
                    trackings = acc.nonLinearTrackings.toList(),
                    clickTracking = acc.nonLinearClicks.toList(),
                ),
            )
        }
        val first = items.first()
        return listOf(
            first.copy(
                trackings = acc.nonLinearTrackings + first.trackings,
                clickTracking = acc.nonLinearClicks + first.clickTracking,
            ),
        ) + items.drop(1)
    }

    private fun mergeViewable(a: ViewableImpression, b: ViewableImpression): ViewableImpression =
        ViewableImpression(
            viewable = (a.viewable + b.viewable).dedupe(),
            notViewable = (a.notViewable + b.notViewable).dedupe(),
            viewUndetermined = (a.viewUndetermined + b.viewUndetermined).dedupe(),
        )

    private class Acc {
        val impressions = mutableListOf<String>()
        val errors = mutableListOf<String>()
        var viewable = ViewableImpression()
        val trackings = mutableListOf<Tracking>()
        val clickTracking = mutableListOf<String>()
        val customClick = mutableListOf<String>()
        val companionTrackings = mutableListOf<Tracking>()
        val companionClicks = mutableListOf<String>()
        val nonLinearTrackings = mutableListOf<Tracking>()
        val nonLinearClicks = mutableListOf<String>()
        var erid: String? = null

        fun addErrors(urls: List<String>) {
            errors.addAll(urls.filter { it.isNotBlank() })
        }

        fun addWrapper(w: Wrapper) {
            impressions.addAll(w.impressions.filter { it.isNotBlank() })
            addErrors(w.errors)
            viewable = mergeViewable(viewable, w.viewableImpression)
            if (erid == null) erid = w.erid
            w.creatives.forEach { c ->
                c.linear?.let { linear ->
                    trackings.addAll(linear.trackings)
                    clickTracking.addAll(linear.clickTracking)
                    customClick.addAll(linear.customClick)
                }
                c.companions.forEach { companion ->
                    companionTrackings.addAll(companion.trackings)
                    companionClicks.addAll(companion.clickTracking)
                }
                c.nonLinears.forEach { nl ->
                    nonLinearTrackings.addAll(nl.trackings)
                    nonLinearClicks.addAll(nl.clickTracking)
                }
            }
        }
    }
}

private fun List<String>.dedupe(): List<String> {
    val seen = LinkedHashSet<String>()
    for (u in this) {
        if (u.isNotBlank()) seen.add(u)
    }
    return seen.toList()
}
