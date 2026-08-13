package com.ctvhouse.sdk.core.vast

import com.ctvhouse.sdk.core.identity.Connection
import com.ctvhouse.sdk.core.identity.Device
import com.ctvhouse.sdk.core.identity.DeviceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MacrosTest {

    private val device = Device(
        ifa = "aa-bb-cc",
        ifaType = "gaid",
        limitAdTracking = false,
        userAgent = "Dalvik/2.1.0 (Linux)",
        ip = "1.2.3.4",
        make = "Xiaomi",
        model = "MiBox 4",
        osVersion = "9",
        type = DeviceType.TV,
        widthPx = 1920,
        heightPx = 1080,
        language = "ru",
        connection = Connection.ETHERNET,
        appBundle = "com.example.tv",
        appName = "Example TV",
        appVersion = "2.1.0",
    )

    @Test
    fun expand_fillsKnownTokens() {
        val url = Macros.expand(
            url = "https://tag.example/vast?ifa=\${IFA}&ua=\${USER_AGENT}&ip=\${IP}&r=\${RANDOM}",
            device = device,
            timestampMs = 0L,
            random = "00000042",
        )
        assertEquals(
            "https://tag.example/vast?ifa=aa-bb-cc&ua=Dalvik%2F2.1.0%20%28Linux%29" +
                "&ip=1.2.3.4&r=00000042",
            url,
        )
    }

    /** A tracker written against the VAST catalog uses square brackets and the VAST names. */
    @Test
    fun expand_fillsVastBracketSpelling() {
        val url = Macros.expand(
            url = "https://px.example/i?ifa=[IFA]&lmt=[LIMITADTRACKING]&ua=[DEVICEUA]" +
                "&cb=[CACHEBUSTING]",
            device = device,
            timestampMs = 0L,
            random = "00000007",
        )
        assertEquals(
            "https://px.example/i?ifa=aa-bb-cc&lmt=0&ua=Dalvik%2F2.1.0%20%28Linux%29&cb=00000007",
            url,
        )
    }

    @Test
    fun expand_reportsTheDeviceAndTheApp() {
        val url = Macros.expand(
            url = "https://t?b=\${APPBUNDLE}&n=\${APPNAME}&v=\${APPVERSION}&mk=\${DEVICEMAKE}" +
                "&md=\${DEVICEMODEL}&os=\${OS}\${OSVERSION}&dt=\${DEVICETYPE}" +
                "&ct=\${CONNECTIONTYPE}&lang=\${LANGUAGE}&size=\${PLAYERSIZE}&ts=\${TIMESTAMP}",
            device = device,
            timestampMs = 1_700_000_000_000L,
        )
        assertEquals(
            "https://t?b=com.example.tv&n=Example%20TV&v=2.1.0&mk=Xiaomi&md=MiBox%204" +
                "&os=android9&dt=3&ct=1&lang=ru&size=1920%2C1080&ts=1700000000000",
            url,
        )
    }

    @Test
    fun expand_isCaseInsensitive() {
        val url = Macros.expand(
            url = "https://t?x=\${ifa}&y=[ifa]",
            device = device,
            timestampMs = 0L,
        )
        assertEquals("https://t?x=aa-bb-cc&y=aa-bb-cc", url)
    }

    /** No ID means an empty value and an opt-out flag, never a leftover token. */
    @Test
    fun expand_deviceWithoutAnIdSaysTrackingIsLimited() {
        val url = Macros.expand(
            url = "https://t?ifa=\${IFA}&type=\${IFATYPE}&lmt=\${LMT}&ip=\${IP}&r=\${RANDOM}",
            device = Device(),
            timestampMs = 0L,
            random = "00000001",
        )
        assertEquals("https://t?ifa=&type=&lmt=1&ip=&r=00000001", url)
        assertFalse(url.contains("\${"))
    }

    /** A token we do not own belongs to whoever wrote the URL. */
    @Test
    fun expand_leavesForeignTokensAlone() {
        val url = Macros.expand(
            url = "https://t?ifa=\${IFA}&puid=\${PUID30}&seg=[SEGMENT]",
            device = device,
            timestampMs = 0L,
        )
        assertEquals("https://t?ifa=aa-bb-cc&puid=\${PUID30}&seg=[SEGMENT]", url)
    }

    /** Brackets and braces appear in real URLs for reasons that have nothing to do with macros. */
    @Test
    fun expand_survivesUrlsThatOnlyLookLikeTheyHaveTokens() {
        val cases = listOf(
            "https://t?a=[]&b=\${}",
            "https://t?a=[IFA&b=\${IFA",
            "https://t?filter=[0-9]&range=a{2}",
            "https://t?nested=\${\${IFA}}",
            "https://t?a=]&b=}",
        )
        cases.forEach { url ->
            val expanded = Macros.expand(url, device, timestampMs = 0L, random = "00000001")
            assertTrue("mangled $url into $expanded", expanded.startsWith("https://t?"))
        }
        assertEquals(
            "https://t?nested=\${aa-bb-cc}",
            Macros.expand("https://t?nested=\${\${IFA}}", device, timestampMs = 0L),
        )
    }

    @Test
    fun expand_leavesAUrlWithoutTokensAlone() {
        val url = "https://tag.example/vast?p1=abc&p2=def"
        assertEquals(url, Macros.expand(url, device, timestampMs = 0L))
    }

    @Test
    fun cacheBuster_isEightDigits() {
        val value = Macros.cacheBuster(Random(0))
        assertEquals(8, value.length)
        assertTrue(value.all { it.isDigit() })
    }
}
