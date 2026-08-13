package com.ctvhouse.sdk.core.vast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFilesTest {

    @Test
    fun typelessUrl_isClassifiedByPathNotQuery() {
        val video = MediaFile(url = "https://cdn.example/creative?file=ad.mp4&t=1")
        assertFalse("a query parameter must not decide the container", video.isVideo)

        val real = MediaFile(url = "https://cdn.example/ad.mp4?token=abc#f")
        assertTrue(real.isVideo)
        assertFalse(real.isAudio)
    }

    @Test
    fun manifestWithQuery_isStillRecognised() {
        val hls = MediaFile(url = "https://cdn.example/master.m3u8?token=abc")
        assertTrue(hls.isStreamingManifest)
    }

    @Test
    fun mimeTypeWins_overPath() {
        val file = MediaFile(url = "https://cdn.example/creative", mimeType = "video/mp4")
        assertTrue(file.isVideo)
    }

    @Test
    fun selectBest_prefersProgressiveOverManifest() {
        val files = listOf(
            MediaFile(
                url = "https://cdn.example/master.m3u8",
                mimeType = "application/x-mpegURL",
                width = 1920,
                height = 1080,
            ),
            MediaFile(
                url = "https://cdn.example/ad.mp4?token=1",
                mimeType = "video/mp4",
                delivery = "progressive",
                width = 640,
                height = 360,
            ),
        )
        assertEquals("https://cdn.example/ad.mp4?token=1", MediaFiles.selectBestUrl(files))
    }

    @Test
    fun selectBest_skipsNonHttpAndEmpty() {
        val files = listOf(
            MediaFile(url = "file:///sdcard/ad.mp4", mimeType = "video/mp4"),
            MediaFile(url = "", mimeType = "video/mp4"),
        )
        assertNull(MediaFiles.selectBestUrl(files))
    }

    @Test
    fun selectBest_prefersLargerAreaWithinSameContainer() {
        val files = listOf(
            MediaFile(
                url = "https://cdn.example/small.mp4",
                mimeType = "video/mp4",
                delivery = "progressive",
                width = 640,
                height = 360,
            ),
            MediaFile(
                url = "https://cdn.example/large.mp4",
                mimeType = "video/mp4",
                delivery = "progressive",
                width = 1920,
                height = 1080,
            ),
        )
        assertEquals("https://cdn.example/large.mp4", MediaFiles.selectBestUrl(files))
    }

    @Test
    fun emptyList_hasNoSelection() {
        assertNull(MediaFiles.selectBest(emptyList()))
    }

    @Test
    fun selectSoundtrack_picksAudioAndIgnoresVideo() {
        val files = listOf(
            MediaFile(
                url = "https://cdn.example/ad.mp4",
                mimeType = "video/mp4",
                delivery = "progressive",
                width = 1920,
                height = 1080,
            ),
            MediaFile(
                url = "https://cdn.example/ad.mp3",
                mimeType = "audio/mpeg",
                delivery = "progressive",
            ),
        )
        assertEquals("https://cdn.example/ad.mp3", MediaFiles.selectSoundtrackUrl(files))
    }

    @Test
    fun selectSoundtrack_isAbsentWhenOnlyVideoExists() {
        val files = listOf(
            MediaFile(
                url = "https://cdn.example/ad.mp4",
                mimeType = "video/mp4",
                delivery = "progressive",
            ),
        )
        assertNull(MediaFiles.selectSoundtrack(files))
        assertEquals("https://cdn.example/ad.mp4", MediaFiles.selectBestUrl(files))
    }
}
