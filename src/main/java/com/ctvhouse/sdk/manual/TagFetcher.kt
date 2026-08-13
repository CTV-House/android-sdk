package com.ctvhouse.sdk.manual

import com.ctvhouse.sdk.core.runtime.Log
import com.ctvhouse.sdk.core.runtime.Safe
import com.ctvhouse.sdk.format.TriggerRoll

/**
 * Answers every opportunity by fetching the launcher's VAST tag.
 *
 * Shared by the launchers in this package so that a tag URL is filled from the same device
 * snapshot as the trackers of the show it produces.
 */
internal class TagFetcher(
    private val format: TriggerRoll,
    private val logTag: String,
    private val tagUrl: () -> String?,
) : TriggerRoll.Controller {

    override fun onOpportunity(reason: String, sink: TriggerRoll.Sink) {
        val url = tagUrl()
        if (url.isNullOrBlank()) {
            sink.error("tagUrl is not set")
            return
        }
        val resolved = format.expandMacros(url)
        val userAgent = format.deviceUserAgent()
        val timeoutMs = format.requestTimeoutMs
        Log.i(logTag, "fetch tag ($reason)")
        Safe.execute(format.io, logTag, "fetch tag") {
            val body = format.client.fetchText(resolved, timeoutMs, userAgent)
            if (body.isNullOrBlank()) {
                sink.error("VAST fetch failed: $resolved")
            } else {
                sink.deliverXml(body)
            }
        }
    }
}
