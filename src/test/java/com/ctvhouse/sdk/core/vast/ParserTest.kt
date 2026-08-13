package com.ctvhouse.sdk.core.vast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParserTest {

    @Test
    fun parse_vast3_fill_mediaCompanionEridTrackersSkip() {
        val doc = Parser.parse(resource("vast/pauseroll_fill.xml"))
        assertEquals("3.0", doc.version)
        val inline = doc.firstInLine!!
        val linear = inline.creatives.mapNotNull { it.linear }.first()
        assertEquals("https://example.com/ad.mp4", MediaFiles.selectBestUrl(linear.mediaFiles))
        assertEquals(
            "https://example.com/companion.jpg",
            inline.creatives.flatMap { it.companions }.first().staticResourceUrl,
        )
        assertEquals("TESTERID123", inline.erid)
        assertEquals("TESTERID123", inline.nroa?.erid)
        assertEquals(listOf("https://example.com/imp"), inline.impressions)
        assertEquals(listOf("https://example.com/start"), linear.trackings.urls(Events.START))
        assertEquals(listOf("https://example.com/skip"), linear.trackings.urls(Events.SKIP))
        assertEquals(listOf("https://example.com/complete"), linear.trackings.urls(Events.COMPLETE))
        assertEquals(Offset.Absolute(5_000L), linear.skipOffset)
    }

    @Test
    fun parse_videoOnly_prefersLargestMp4_notDashOrHls() {
        val doc = Parser.parse(resource("vast/pauseroll_video_only.xml"))
        val linear = doc.firstInLine!!.creatives.mapNotNull { it.linear }.first()
        assertEquals("https://example.com/ad-1080.mp4", MediaFiles.selectBestUrl(linear.mediaFiles))
        assertTrue(doc.firstInLine!!.creatives.flatMap { it.companions }.isEmpty())
    }

    @Test
    fun parse_emptyMedia_withCompanion() {
        val doc = Parser.parse(resource("vast/pauseroll_no_media.xml"))
        val inline = doc.firstInLine!!
        val linear = inline.creatives.mapNotNull { it.linear }.firstOrNull()
        assertNull(MediaFiles.selectBestUrl(linear?.mediaFiles.orEmpty()))
        assertEquals(
            "https://example.com/companion.jpg",
            inline.creatives.flatMap { it.companions }.first().staticResourceUrl,
        )
    }

    @Test
    fun parse_numericAttributesSurviveWhitespace() {
        val doc = Parser.parse(
            """
            <VAST version="3.0"><Ad><InLine><Creatives>
            <Creative><Linear>
              <MediaFiles>
                <MediaFile delivery="progressive" type="video/mp4" width="	
1920" height="1080">
                  <![CDATA[https://example.com/ad.mp4]]>
                </MediaFile>
              </MediaFiles>
            </Linear></Creative>
            <Creative><CompanionAds>
              <Companion width=" 1920 " height="
1080">
                <StaticResource creativeType="image/jpg">
                  <![CDATA[https://example.com/companion.jpg]]>
                </StaticResource>
              </Companion>
            </CompanionAds></Creative>
            </Creatives></InLine></Ad></VAST>
            """.trimIndent(),
        )
        val linear = doc.firstInLine!!.creatives.mapNotNull { it.linear }.first()
        val media = MediaFiles.selectBest(linear.mediaFiles)!!
        assertEquals(1920, media.width)
        assertEquals(1080, media.height)
        val companion = doc.firstInLine!!.creatives.flatMap { it.companions }.first()
        assertEquals(1920, companion.width)
        assertEquals(1080, companion.height)
        assertEquals("https://example.com/companion.jpg", companion.staticResourceUrl)
    }

    @Test
    fun parse_vast20_inline() {
        val doc = Parser.parse(resource("vast/vast20_inline.xml"))
        assertEquals("2.0", doc.version)
        val linear = doc.firstInLine!!.creatives.mapNotNull { it.linear }.first()
        assertEquals("https://example.com/v2.mp4", MediaFiles.selectBestUrl(linear.mediaFiles))
        assertEquals(listOf("https://example.com/imp2"), doc.firstInLine!!.impressions)
    }

    @Test
    fun parse_vast40_inline_viewableAndUniversalAdIdSkippedSafely() {
        val doc = Parser.parse(resource("vast/vast40_inline.xml"))
        assertEquals("4.2", doc.version)
        val inline = doc.firstInLine!!
        assertEquals(
            listOf("https://example.com/viewable"),
            inline.viewableImpression.viewable,
        )
        assertEquals(
            listOf("https://example.com/not-viewable"),
            inline.viewableImpression.notViewable,
        )
        assertEquals("https://example.com/v4.mp4", MediaFiles.selectBestUrl(
            inline.creatives.mapNotNull { it.linear }.first().mediaFiles,
        ))
        assertEquals("ERID42", inline.erid)
        assertEquals("ERID42", inline.nroa?.erid)
    }

    @Test
    fun parse_allLinearTrackingEvents_andProgressOffset() {
        val doc = Parser.parse(
            """
            <VAST version="4.2"><Ad><InLine>
            <Creatives><Creative><Linear>
            <Duration>00:00:10</Duration>
            <TrackingEvents>
              <Tracking event="mute"><![CDATA[https://e/mute]]></Tracking>
              <Tracking event="unmute"><![CDATA[https://e/unmute]]></Tracking>
              <Tracking event="pause"><![CDATA[https://e/pause]]></Tracking>
              <Tracking event="resume"><![CDATA[https://e/resume]]></Tracking>
              <Tracking event="rewind"><![CDATA[https://e/rewind]]></Tracking>
              <Tracking event="skip"><![CDATA[https://e/skip]]></Tracking>
              <Tracking event="playerExpand"><![CDATA[https://e/pexp]]></Tracking>
              <Tracking event="playerCollapse"><![CDATA[https://e/pcoll]]></Tracking>
              <Tracking event="loaded"><![CDATA[https://e/loaded]]></Tracking>
              <Tracking event="start"><![CDATA[https://e/start]]></Tracking>
              <Tracking event="firstQuartile"><![CDATA[https://e/q1]]></Tracking>
              <Tracking event="midpoint"><![CDATA[https://e/mid]]></Tracking>
              <Tracking event="thirdQuartile"><![CDATA[https://e/q3]]></Tracking>
              <Tracking event="complete"><![CDATA[https://e/complete]]></Tracking>
              <Tracking event="progress" offset="00:00:03"><![CDATA[https://e/p3]]></Tracking>
              <Tracking event="progress" offset="50%"><![CDATA[https://e/p50]]></Tracking>
              <Tracking event="closeLinear"><![CDATA[https://e/closel]]></Tracking>
              <Tracking event="creativeView"><![CDATA[https://e/cview]]></Tracking>
              <Tracking event="acceptInvitation"><![CDATA[https://e/accept]]></Tracking>
              <Tracking event="adExpand"><![CDATA[https://e/aexp]]></Tracking>
              <Tracking event="adCollapse"><![CDATA[https://e/acoll]]></Tracking>
              <Tracking event="minimize"><![CDATA[https://e/min]]></Tracking>
              <Tracking event="close"><![CDATA[https://e/close]]></Tracking>
              <Tracking event="overlayViewDuration"><![CDATA[https://e/ovd]]></Tracking>
              <Tracking event="otherAdInteraction"><![CDATA[https://e/other]]></Tracking>
              <Tracking event="interactiveStart"><![CDATA[https://e/isim]]></Tracking>
              <Tracking event="customVendorEvent"><![CDATA[https://e/custom]]></Tracking>
            </TrackingEvents>
            <VideoClicks>
              <ClickThrough><![CDATA[https://e/through]]></ClickThrough>
              <ClickTracking><![CDATA[https://e/click]]></ClickTracking>
              <CustomClick><![CDATA[https://e/customclick]]></CustomClick>
            </VideoClicks>
            <MediaFiles>
              <MediaFile type="video/mp4" width="1" height="1" delivery="progressive">
                <![CDATA[https://example.com/a.mp4]]>
              </MediaFile>
            </MediaFiles>
            </Linear></Creative>
            <Creative><CompanionAds><Companion width="1" height="1">
              <StaticResource><![CDATA[https://example.com/c.jpg]]></StaticResource>
              <TrackingEvents>
                <Tracking event="creativeView"><![CDATA[https://e/comp-view]]></Tracking>
              </TrackingEvents>
              <CompanionClickTracking><![CDATA[https://e/comp-click]]></CompanionClickTracking>
            </Companion></CompanionAds></Creative>
            </Creatives>
            </InLine></Ad></VAST>
            """.trimIndent(),
        )
        val linear = doc.firstInLine!!.creatives.mapNotNull { it.linear }.first()
        Events.ALL.forEach { event ->
            assertTrue("missing $event", linear.trackings.any { it.event.equals(event, true) })
        }
        assertTrue(linear.trackings.any { it.event == "customVendorEvent" })
        val progress = linear.trackings.filter { it.event.equals(Events.PROGRESS, true) }
        assertEquals(2, progress.size)
        assertEquals(Offset.Absolute(3_000L), progress[0].offset)
        assertEquals(Offset.Percent(50f), progress[1].offset)
        assertEquals("https://e/through", linear.clickThrough)
        assertEquals(listOf("https://e/click"), linear.clickTracking)
        assertEquals(listOf("https://e/customclick"), linear.customClick)
        val companion = doc.firstInLine!!.creatives.flatMap { it.companions }.first()
        assertEquals(listOf("https://e/comp-view"), companion.trackings.urls(Events.CREATIVE_VIEW))
        assertEquals(listOf("https://e/comp-click"), companion.clickTracking)
        assertEquals(
            listOf("https://e/p3"),
            linear.trackings.dueProgressUrls(3_000L, 10_000L),
        )
        assertEquals(
            listOf("https://e/p3", "https://e/p50"),
            linear.trackings.dueProgressUrls(5_000L, 10_000L),
        )
    }

    @Test
    fun parse_extensionTypeErid_directText() {
        val doc = Parser.parse(
            """
            <VAST version="3.0"><Ad><InLine>
            <Creatives><Creative><Linear><MediaFiles>
            <MediaFile type="video/mp4" width="1" height="1" delivery="progressive">
            <![CDATA[https://example.com/a.mp4]]>
            </MediaFile></MediaFiles></Linear></Creative></Creatives>
            <Extensions>
              <Extension type="ERID"><![CDATA[DIRECT-ERID-1]]></Extension>
            </Extensions>
            </InLine></Ad></VAST>
            """.trimIndent(),
        )
        assertEquals("DIRECT-ERID-1", doc.firstInLine!!.erid)
        assertNull(doc.firstInLine!!.nroa)
    }

    @Test
    fun parse_extensionTypeErid_nestedErid() {
        val doc = Parser.parse(
            """
            <VAST version="3.0"><Ad><InLine>
            <Extensions>
              <Extension type="erid"><Erid>NESTED-ERID</Erid></Extension>
            </Extensions>
            </InLine></Ad></VAST>
            """.trimIndent(),
        )
        assertEquals("NESTED-ERID", doc.firstInLine!!.erid)
    }

    @Test
    fun parse_bareErid_underInLine() {
        val doc = Parser.parse(
            """
            <VAST version="3.0"><Ad><InLine>
            <Erid><![CDATA[BARE-ERID]]></Erid>
            </InLine></Ad></VAST>
            """.trimIndent(),
        )
        assertEquals("BARE-ERID", doc.firstInLine!!.erid)
    }

    @Test
    fun parse_wrapper_vastAdTagUri() {
        val doc = Parser.parse(resource("vast/wrapper.xml"))
        val wrapper = doc.ads.first().wrapper
        assertNotNull(wrapper)
        assertEquals("https://example.com/inline.xml", wrapper!!.vastAdTagUri)
        assertFalse(wrapper.followAdditionalWrappers)
        assertEquals(listOf("https://example.com/wrapper-imp"), wrapper.impressions)
        assertNull(doc.firstInLine)
    }

    @Test
    fun parse_wrapper_creativesTrackings() {
        val doc = Parser.parse(resource("vast/wrapper_with_trackings.xml"))
        val wrapper = doc.ads.first().wrapper!!
        val linear = wrapper.creatives.mapNotNull { it.linear }.first()
        assertEquals(listOf("https://example.com/wrapper-start"), linear.trackings.urls(Events.START))
        assertEquals(listOf("https://example.com/vast-error"), doc.errors)
        assertEquals(listOf("https://example.com/wrapper-error"), wrapper.errors)
    }

    @Test
    fun resolve_wrapper_mergesImpressionsTrackingsAndErrors() {
        val doc = Parser.resolve(resource("vast/wrapper_with_trackings.xml")) { url: String ->
            assertEquals("https://example.com/inline.xml", url)
            resource("vast/wrapper_inline.xml")
        }
        val inline = doc.firstInLine!!
        val linear = inline.creatives.mapNotNull { it.linear }.first()
        assertEquals(
            listOf("https://example.com/wrapper-imp", "https://example.com/inline-imp"),
            inline.impressions,
        )
        assertEquals(
            listOf(
                "https://example.com/vast-error",
                "https://example.com/wrapper-error",
                "https://example.com/inline-doc-error",
                "https://example.com/inline-error",
            ),
            inline.errors,
        )
        assertEquals(inline.errors, doc.errors)
        assertEquals(
            listOf("https://example.com/wrapper-start", "https://example.com/inline-start"),
            linear.trackings.urls(Events.START),
        )
        assertEquals(listOf("https://example.com/wrapper-skip"), linear.trackings.urls(Events.SKIP))
        assertEquals(
            "https://example.com/ad.mp4",
            MediaFiles.selectBestUrl(linear.mediaFiles),
        )
    }

    @Test
    fun resolve_followAdditionalWrappersFalse_stopsOnSecondWrapper() {
        val secondWrapper = """
            <VAST version="3.0"><Ad><Wrapper>
            <VASTAdTagURI><![CDATA[https://example.com/never.xml]]></VASTAdTagURI>
            </Wrapper></Ad></VAST>
        """.trimIndent()
        val doc = Parser.resolve(resource("vast/wrapper.xml")) { secondWrapper }
        assertNull(doc.firstInLine)
        // Outer wrapper had no Error; chain stops with empty ads
        assertTrue(doc.ads.isEmpty())
    }

    @Test
    fun resolve_fetchFail_keepsAccumulatedErrors() {
        val doc = Parser.resolve(resource("vast/wrapper_with_trackings.xml")) { null }
        assertNull(doc.firstInLine)
        assertEquals(
            listOf(
                "https://example.com/vast-error",
                "https://example.com/wrapper-error",
            ),
            doc.errors,
        )
    }

    @Test
    fun resolve_maxDepth_stopsWithoutInline() {
        val hop = """
            <VAST version="3.0"><Ad><Wrapper followAdditionalWrappers="true">
            <Error><![CDATA[https://example.com/depth-error]]></Error>
            <VASTAdTagURI><![CDATA[https://example.com/next.xml]]></VASTAdTagURI>
            </Wrapper></Ad></VAST>
        """.trimIndent()
        var fetches = 0
        val doc = Parser.resolve(hop, maxDepth = 0) {
            fetches++
            hop
        }
        assertEquals(0, fetches)
        assertNull(doc.firstInLine)
        assertEquals(listOf("https://example.com/depth-error"), doc.errors)
    }

    @Test
    fun parse_bomAndJunkBeforeXml_stillParses() {
        val xml = "\uFEFF  junk\n" + resource("vast/vast20_inline.xml")
        val doc = Parser.parse(xml)
        assertEquals("2.0", doc.version)
        assertNotNull(doc.firstInLine)
    }

    @Test
    fun parse_plainTextUrls_withoutCdata() {
        val doc = Parser.parse(
            """
            <VAST version="3.0"><Ad><InLine>
            <Impression>https://example.com/plain-imp</Impression>
            <Creatives><Creative><Linear>
            <MediaFiles>
            <MediaFile type="video/mp4" width="10" height="10" delivery="progressive">
            https://example.com/plain.mp4
            </MediaFile>
            </MediaFiles>
            </Linear></Creative></Creatives>
            </InLine></Ad></VAST>
            """.trimIndent(),
        )
        val inline = doc.firstInLine!!
        assertEquals(listOf("https://example.com/plain-imp"), inline.impressions)
        assertEquals(
            "https://example.com/plain.mp4",
            MediaFiles.selectBestUrl(inline.creatives.mapNotNull { it.linear }.first().mediaFiles),
        )
    }

    @Test
    fun selectBest_skipsVpaid() {
        val files = listOf(
            MediaFile(
                url = "https://example.com/vpaid.js",
                mimeType = "application/javascript",
                apiFramework = "VPAID",
                width = 1920,
                height = 1080,
            ),
            MediaFile(
                url = "https://example.com/ok.mp4",
                mimeType = "video/mp4",
                delivery = "progressive",
                width = 640,
                height = 360,
            ),
        )
        assertEquals("https://example.com/ok.mp4", MediaFiles.selectBestUrl(files))
    }

    @Test
    fun selectBest_prefersWebmWhenNoMp4() {
        val files = listOf(
            MediaFile(
                url = "https://example.com/a.webm",
                mimeType = "video/webm",
                delivery = "progressive",
                width = 1920,
                height = 1080,
            ),
            MediaFile(
                url = "https://example.com/b.3gp",
                mimeType = "video/3gpp",
                delivery = "progressive",
                width = 640,
                height = 360,
            ),
        )
        assertEquals("https://example.com/a.webm", MediaFiles.selectBestUrl(files))
    }

    @Test
    fun selectBest_audioWhenNoVideo() {
        val files = listOf(
            MediaFile(
                url = "https://example.com/ad.mp3",
                mimeType = "audio/mpeg",
                delivery = "progressive",
            ),
            MediaFile(
                url = "https://example.com/ad.m4a",
                mimeType = "audio/mp4",
                delivery = "progressive",
                bitrate = 128,
            ),
        )
        val best = MediaFiles.selectBest(files)!!
        assertEquals("https://example.com/ad.m4a", best.url)
        assertTrue(best.isAudio)
        assertFalse(best.isVideo)
    }

    @Test
    fun selectBest_videoPreferredOverAudio() {
        val files = listOf(
            MediaFile(
                url = "https://example.com/ad.m4a",
                mimeType = "audio/mp4",
                delivery = "progressive",
                bitrate = 256,
            ),
            MediaFile(
                url = "https://example.com/ad.webm",
                mimeType = "video/webm",
                delivery = "progressive",
                width = 640,
                height = 360,
            ),
        )
        assertEquals("https://example.com/ad.webm", MediaFiles.selectBestUrl(files))
    }

    @Test
    fun resolve_endlessWrapperChain_stopsAtTheShippedHopLimit() {
        val hop = """
            <VAST version="3.0"><Ad><Wrapper>
            <VASTAdTagURI><![CDATA[https://example.com/next.xml]]></VASTAdTagURI>
            </Wrapper></Ad></VAST>
        """.trimIndent()
        var fetches = 0
        val doc = Parser.resolve(hop) {
            fetches++
            hop
        }
        assertEquals(Resolve.DEFAULT_MAX_DEPTH, fetches)
        assertNull(doc.firstInLine)
    }

    @Test
    fun parse_entityPayload_isNotExpandedIntoTheHostHeap() {
        val doc = Parser.parse(
            """
            <?xml version="1.0"?>
            <!DOCTYPE VAST [
              <!ENTITY a "aaaaaaaaaa">
              <!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">
              <!ENTITY c "&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;">
            ]>
            <VAST version="3.0"><Ad><InLine>
            <Impression>https://example.com/&c;</Impression>
            </InLine></Ad></VAST>
            """.trimIndent(),
        )
        assertTrue(doc.firstInLine?.impressions.orEmpty().none { it.contains("aaaaaaaaaa") })
    }

    @Test
    fun parse_externalEntity_readsNoLocalFile() {
        val doc = Parser.parse(
            """
            <?xml version="1.0"?>
            <!DOCTYPE VAST [<!ENTITY leak SYSTEM "file:///etc/passwd">]>
            <VAST version="3.0"><Ad><InLine>
            <Impression>&leak;</Impression>
            </InLine></Ad></VAST>
            """.trimIndent(),
        )
        assertTrue(doc.firstInLine?.impressions.orEmpty().none { it.contains("root:") })
    }

    @Test
    fun parse_blank_andGarbage_emptyDocument() {
        assertEquals(Document.EMPTY, Parser.parse("   "))
        assertTrue(Parser.parse("<not-vast>").ads.isEmpty())
    }

    @Test
    fun parse_invalidSkipOffset_null() {
        val doc = Parser.parse(
            """
            <VAST><Ad><InLine><Creatives><Creative>
            <Linear skipoffset="bad"><MediaFiles>
            <MediaFile type="video/mp4" width="1" height="1" delivery="progressive">
            <![CDATA[https://example.com/a.mp4]]>
            </MediaFile></MediaFiles></Linear>
            </Creative></Creatives></InLine></Ad></VAST>
            """.trimIndent(),
        )
        val linear = doc.firstInLine!!.creatives.mapNotNull { it.linear }.first()
        assertNull(linear.skipOffset)
        assertNotNull(MediaFiles.selectBestUrl(linear.mediaFiles))
    }

    @Test
    fun parseOffset_percent() {
        assertEquals(Offset.Percent(10f), Parser.parseOffset("10%"))
        assertEquals(Offset.Absolute(5_000L), Parser.parseOffset("00:00:05"))
    }

    private fun resource(path: String): String =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream(path))
            .bufferedReader()
            .use { it.readText() }
}
