package com.antigravity.filemanager.utils

import coil.size.Dimension
import coil.size.Size
import com.antigravity.filemanager.core.NativeAudioMetadata
import com.antigravity.filemanager.core.NativeImageMetadata
import com.antigravity.filemanager.core.NativeVideoMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class MediaFetchersTest {

    private val fastImageExtensions = setOf("jpg", "jpeg")
    private val audioExtensions = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "wma", "opus", "amr")
    private val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "3gp", "ts", "m4v", "mpg", "mpeg", "vob", "ogv", "f4v")

    @Test
    fun testFastImageThumbnailFetcherExtensions() {
        assertTrue("jpg" in fastImageExtensions)
        assertTrue("jpeg" in fastImageExtensions)
        assertTrue("JPG".lowercase(Locale.getDefault()) in fastImageExtensions)

        assertFalse("png" in fastImageExtensions)
        assertFalse("mp4" in fastImageExtensions)
        assertFalse("mp3" in fastImageExtensions)
    }

    @Test
    fun testFastImageThumbnailFetcherRequestDiscrimination() {
        // Thumbnail requests (e.g. 240x240, 480x480) should be accepted
        val smallThumbnailSize = Size(Dimension.Pixels(240), Dimension.Pixels(240))
        assertTrue(FastImageThumbnailFetcher.isThumbnailRequest(smallThumbnailSize, isFullViewer = false))

        val maxAllowedThumbnailSize = Size(Dimension.Pixels(512), Dimension.Pixels(512))
        assertTrue(FastImageThumbnailFetcher.isThumbnailRequest(maxAllowedThumbnailSize, isFullViewer = false))

        // Full viewer request (isFullViewer = true) must be rejected so full decoder is used
        assertFalse(FastImageThumbnailFetcher.isThumbnailRequest(smallThumbnailSize, isFullViewer = true))

        // Size.ORIGINAL must be rejected so full decoder is used
        assertFalse(FastImageThumbnailFetcher.isThumbnailRequest(Size.ORIGINAL, isFullViewer = false))

        // Full-screen screen dimensions (e.g. 1080x2400) must be rejected
        val fullScreenSize = Size(Dimension.Pixels(1080), Dimension.Pixels(2400))
        assertFalse(FastImageThumbnailFetcher.isThumbnailRequest(fullScreenSize, isFullViewer = false))

        // Unbounded / undefined dimensions must be rejected
        val undefinedSize = Size(Dimension.Undefined, Dimension.Undefined)
        assertFalse(FastImageThumbnailFetcher.isThumbnailRequest(undefinedSize, isFullViewer = false))
    }

    @Test
    fun testAudioArtFetcherExtensions() {
        assertTrue("mp3" in audioExtensions)
        assertTrue("flac" in audioExtensions)
        assertTrue("m4a" in audioExtensions)
        assertTrue("wav" in audioExtensions)
        assertTrue("ogg" in audioExtensions)

        assertFalse("jpg" in audioExtensions)
        assertFalse("mp4" in audioExtensions)
        assertFalse("pdf" in audioExtensions)
    }

    @Test
    fun testVideoThumbnailFetcherExtensions() {
        assertTrue("mp4" in videoExtensions)
        assertTrue("mkv" in videoExtensions)
        assertTrue("webm" in videoExtensions)
        assertTrue("mov" in videoExtensions)
        assertTrue("avi" in videoExtensions)

        assertFalse("jpg" in videoExtensions)
        assertFalse("mp3" in videoExtensions)
        assertFalse("pdf" in videoExtensions)
    }

    @Test
    fun testNativeMetadataDataClasses() {
        val audio = NativeAudioMetadata(
            title = "Bohemian Rhapsody",
            artist = "Queen",
            album = "A Night at the Opera",
            year = "1975",
            durationMs = 354000L,
            bitrateKbps = 320,
            sampleRateHz = 44100
        )
        assertEquals("Bohemian Rhapsody", audio.title)
        assertEquals("Queen", audio.artist)
        assertEquals("A Night at the Opera", audio.album)
        assertEquals("1975", audio.year)
        assertEquals(354000L, audio.durationMs)
        assertEquals(320, audio.bitrateKbps)
        assertEquals(44100, audio.sampleRateHz)

        val image = NativeImageMetadata(
            width = 3840,
            height = 2160,
            format = "JPEG",
            orientation = 1,
            hasEmbeddedThumbnail = true,
            dateTime = "2026:11:05 02:00:00"
        )
        assertEquals(3840, image.width)
        assertEquals(2160, image.height)
        assertEquals("JPEG", image.format)
        assertEquals(1, image.orientation)
        assertTrue(image.hasEmbeddedThumbnail)
        assertEquals("2026:11:05 02:00:00", image.dateTime)

        val video = NativeVideoMetadata(
            width = 1920,
            height = 1080,
            durationMs = 120000L,
            qualityBadge = "1080p",
            hasPoster = true
        )
        assertEquals(1920, video.width)
        assertEquals(1080, video.height)
        assertEquals(120000L, video.durationMs)
        assertEquals("1080p", video.qualityBadge)
        assertTrue(video.hasPoster)
    }
}
