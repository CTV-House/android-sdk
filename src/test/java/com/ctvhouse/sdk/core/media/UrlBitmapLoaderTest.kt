package com.ctvhouse.sdk.core.media

import com.ctvhouse.sdk.core.net.Client
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/** The companion path end to end: bytes off the wire turn into a bitmap the host can afford. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class UrlBitmapLoaderTest {

    @Test
    fun smallCreativeIsDecodedAtFullSize() {
        val loader = UrlBitmapLoader(client = ServingClient(png(320, 240)))

        val bitmap = loader.load(URL, TIMEOUT, UA)

        assertNotNull(bitmap)
        assertEquals(320, bitmap!!.width)
        assertEquals(240, bitmap.height)
    }

    @Test
    fun oversizedCreativeIsDownsampledToTheBudget() {
        val budget = 100_000
        val loader = UrlBitmapLoader(client = ServingClient(png(1_600, 1_200)), maxPixels = budget)

        val bitmap = loader.load(URL, TIMEOUT, UA)

        assertNotNull(bitmap)
        assertTrue(
            "decoded ${bitmap!!.width}x${bitmap.height} exceeds the budget",
            bitmap.width * bitmap.height <= budget,
        )
    }

    @Test
    fun downloadFailure_yieldsNoBitmap() {
        val loader = UrlBitmapLoader(client = ServingClient(null))

        assertNull(loader.load(URL, TIMEOUT, UA))
    }

    @Test
    fun aCreativeThePlatformCannotDecodeYieldsNoBitmap() {
        val loader = UrlBitmapLoader(
            client = ServingClient(png(320, 240).copyOf(40)),
            decode = { _, _ -> null },
        )

        assertNull(loader.load(URL, TIMEOUT, UA))
    }

    @Test
    fun aDecoderThatThrowsIsNotPassedOnToTheHost() {
        val loader = UrlBitmapLoader(
            client = ServingClient(png(8, 8)),
            decode = { _, _ -> throw OutOfMemoryError("creative too big") },
        )

        assertNull(loader.load(URL, TIMEOUT, UA))
    }

    @Test
    fun theCreativeLimitIsWhatReachesTheClient() {
        val client = ServingClient(png(8, 8))
        UrlBitmapLoader(client = client).load(URL, TIMEOUT, UA)

        assertEquals(8 * 1024 * 1024, client.requestedMaxBytes)
        assertEquals(URL, client.requestedUrl)
    }

    /** Minimal 8-bit greyscale PNG. Hand-rolled so the test needs no image toolkit. */
    private fun png(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))

        val header = ByteArrayOutputStream().apply {
            writeInt(width)
            writeInt(height)
            write(byteArrayOf(8, 0, 0, 0, 0))
        }
        out.writeChunk("IHDR", header.toByteArray())

        val raw = ByteArray(height * (width + 1)) // one filter byte per scanline, pixels stay 0
        out.writeChunk("IDAT", deflate(raw))
        out.writeChunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write(value ushr 24)
        write((value ushr 16) and 0xFF)
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.writeChunk(type: String, data: ByteArray) {
        writeInt(data.size)
        val typed = type.toByteArray(Charsets.US_ASCII)
        write(typed)
        write(data)
        val crc = CRC32().apply {
            update(typed)
            update(data)
        }
        writeInt(crc.value.toInt())
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer))
        }
        deflater.end()
        return out.toByteArray()
    }

    private class ServingClient(private val body: ByteArray?) : Client {
        var requestedUrl: String? = null
        var requestedMaxBytes: Int = 0

        override fun fetchText(url: String, timeoutMs: Int, userAgent: String): String? = null

        override fun fetchBytes(
            url: String,
            timeoutMs: Int,
            userAgent: String,
            maxBytes: Int,
        ): ByteArray? {
            requestedUrl = url
            requestedMaxBytes = maxBytes
            return body
        }

        override fun fireTrackers(urls: List<String>, userAgent: String) = Unit
    }

    private companion object {
        const val URL = "https://example.com/companion.png"
        const val TIMEOUT = 1_000
        const val UA = "ctv-sdk-test-agent"
    }
}
