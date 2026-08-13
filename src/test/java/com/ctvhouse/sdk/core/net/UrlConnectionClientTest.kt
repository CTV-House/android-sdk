package com.ctvhouse.sdk.core.net

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UrlConnectionClientTest {

    private lateinit var server: TestHttpServer
    private val client = UrlConnectionClient()

    @Before
    fun setUp() {
        server = TestHttpServer()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun fetchText_returnsBody() {
        server.text("/vast", "<VAST/>")
        assertEquals("<VAST/>", client.fetchText(server.url("/vast"), TIMEOUT, UA))
    }

    @Test
    fun fetchText_sendsUserAgent() {
        server.text("/vast", "<VAST/>")
        client.fetchText(server.url("/vast"), TIMEOUT, UA)
        assertEquals(UA, server.requests.single().headers["user-agent"])
    }

    @Test
    fun fetchText_serverError_null() {
        server.text("/boom", "nope", code = 500)
        assertNull(client.fetchText(server.url("/boom"), TIMEOUT, UA))
    }

    @Test
    fun fetchText_notFound_null() {
        assertNull(client.fetchText(server.url("/missing"), TIMEOUT, UA))
    }

    @Test
    fun fetchText_emptyBody_isEmptyString() {
        server.text("/empty", "")
        assertEquals("", client.fetchText(server.url("/empty"), TIMEOUT, UA))
    }

    @Test
    fun fetchText_followsRedirect() {
        server.redirect("/from", server.url("/to"))
        server.text("/to", "<VAST/>")
        assertEquals("<VAST/>", client.fetchText(server.url("/from"), TIMEOUT, UA))
    }

    @Test
    fun fetchText_followsRelativeRedirect() {
        server.on("/rel") {
            TestHttpServer.Response(code = 301, headers = mapOf("Location" to "/target"))
        }
        server.text("/target", "<VAST/>")
        assertEquals("<VAST/>", client.fetchText(server.url("/rel"), TIMEOUT, UA))
    }

    @Test
    fun fetchText_followsChainUpToTheLimit() {
        for (hop in 1..5) {
            server.redirect("/hop$hop", server.url("/hop${hop + 1}"))
        }
        server.text("/hop6", "<VAST/>")
        assertEquals("<VAST/>", client.fetchText(server.url("/hop1"), TIMEOUT, UA))
    }

    @Test
    fun fetchText_redirectLoop_givesUpInsteadOfSpinning() {
        server.redirect("/loop", server.url("/loop"))
        assertNull(client.fetchText(server.url("/loop"), TIMEOUT, UA))
        assertTrue(server.requests.size <= 6)
    }

    @Test
    fun redirectToNonHttpScheme_isRefused() {
        server.redirect("/away", "javascript:alert(1)")
        assertNull(client.fetchText(server.url("/away"), TIMEOUT, UA))
    }

    @Test
    fun redirectWithoutLocation_isNotFollowed() {
        server.on("/nowhere") { TestHttpServer.Response(code = 302) }
        assertNull(client.fetchText(server.url("/nowhere"), TIMEOUT, UA))
    }

    @Test
    fun fireTrackers_followRedirects() {
        server.redirect("/px", server.url("/px-final"))
        server.text("/px-final", "")
        client.fireTrackers(listOf(server.url("/px")), UA)
        assertEquals(1, server.requests.count { it.path == "/px-final" })
    }

    @Test
    fun fetchBytes_overLimit_null() {
        server.text("/big", "x".repeat(4096))
        assertNull(client.fetchBytes(server.url("/big"), TIMEOUT, UA, maxBytes = 1024))
        assertNotNull(client.fetchBytes(server.url("/big"), TIMEOUT, UA, maxBytes = 8192))
    }

    @Test
    fun fetchBytes_declaredLengthOverLimit_isRefusedBeforeReading() {
        server.on("/liar") {
            TestHttpServer.Response(
                body = "small".toByteArray(),
                headers = mapOf("Content-Length" to "999999999"),
            )
        }
        assertNull(client.fetchBytes(server.url("/liar"), TIMEOUT, UA, maxBytes = 1024))
    }

    @Test
    fun fetchText_oversizedMarkup_null() {
        server.text("/huge", "y".repeat(MAX_MARKUP_BYTES + 1))
        assertNull(client.fetchText(server.url("/huge"), TIMEOUT, UA))
    }

    @Test
    fun nonHttpScheme_isRefused() {
        assertNull(client.fetchBytes("file:///etc/hosts", TIMEOUT, UA))
        assertNull(client.fetchBytes("   ", TIMEOUT, UA))
        assertNull(client.fetchText("ftp://example.com/a.xml", TIMEOUT, UA))
        assertNull(client.fetchText("javascript:alert(1)", TIMEOUT, UA))
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun malformedOrDeadUrl_returnsNullInsteadOfThrowing() {
        assertNull(client.fetchText("http://", TIMEOUT, UA))
        assertNull(client.fetchText("http://127.0.0.1:1/none", 500, UA))
    }

    @Test
    fun readTimeout_returnsNull() {
        server.on("/slow") {
            TestHttpServer.Response(body = "late".toByteArray(), delayMs = 1_500L)
        }
        assertNull(client.fetchText(server.url("/slow"), 300, UA))
    }

    @Test
    fun fireTrackers_pingsEveryUsableUrl() {
        server.text("/px", "")
        client.fireTrackers(
            listOf(server.url("/px"), "", "javascript:alert(1)", server.url("/px")),
            UA,
        )
        assertEquals(2, server.requests.count { it.path == "/px" })
    }

    @Test
    fun fireTrackers_survivesUnreachableHost() {
        client.fireTrackers(listOf("http://127.0.0.1:1/px"), UA)
    }

    private companion object {
        const val UA = "ctv-sdk-test-agent"
        const val TIMEOUT = 2_000
    }
}
