package com.ctvhouse.sdk.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.ctvhouse.sdk.core.net.Client
import com.ctvhouse.sdk.core.net.MAX_CREATIVE_BYTES
import com.ctvhouse.sdk.core.net.UrlConnectionClient

internal interface BitmapLoader {
    fun load(url: String, timeoutMs: Int, userAgent: String): Bitmap?
}

/**
 * Downloads a still creative and decodes it downsampled.
 *
 * The decoded bitmap is capped at [maxPixels] so an oversized creative costs bounded memory
 * instead of an allocation the host process cannot satisfy.
 */
internal class UrlBitmapLoader(
    private val client: Client = UrlConnectionClient(),
    private val maxPixels: Int = MAX_PIXELS,
    /** Platform decode, taken as a parameter so the failure branch is reachable off-device. */
    private val decode: (ByteArray, BitmapFactory.Options) -> Bitmap? = { bytes, options ->
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    },
) : BitmapLoader {

    override fun load(url: String, timeoutMs: Int, userAgent: String): Bitmap? {
        val bytes = client.fetchBytes(url, timeoutMs, userAgent, MAX_CREATIVE_BYTES) ?: return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            decode(bytes, bounds)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxPixels)
            }
            decode(bytes, options)
        } catch (_: Throwable) {
            null
        }
    }

    internal companion object {
        /** 4 MPx ≈ 16 MB as ARGB_8888 — enough for a full-screen CTV creative. */
        const val MAX_PIXELS: Int = 4_000_000

        fun sampleSize(width: Int, height: Int, maxPixels: Int): Int {
            if (width <= 0 || height <= 0 || maxPixels <= 0) return 1
            var sample = 1
            while ((width.toLong() / sample) * (height.toLong() / sample) > maxPixels) {
                sample *= 2
            }
            return sample
        }
    }
}
