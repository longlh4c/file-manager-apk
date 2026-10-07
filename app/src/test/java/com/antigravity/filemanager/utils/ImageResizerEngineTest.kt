package com.antigravity.filemanager.utils

import com.antigravity.filemanager.domain.model.ImageResizeMode
import com.antigravity.filemanager.domain.model.ImageResizeParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ImageResizerEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun isImageFile_identifiesSupportedFormats() {
        assertTrue(ImageResizerEngine.isImageFile("photo.jpg"))
        assertTrue(ImageResizerEngine.isImageFile("photo.JPEG"))
        assertTrue(ImageResizerEngine.isImageFile("image.png"))
        assertTrue(ImageResizerEngine.isImageFile("picture.webp"))
        assertTrue(ImageResizerEngine.isImageFile("graphic.bmp"))
        assertTrue(ImageResizerEngine.isImageFile("camera.heic"))
        assertTrue(ImageResizerEngine.isImageFile("camera.HEIF"))

        assertFalse(ImageResizerEngine.isImageFile("document.pdf"))
        assertFalse(ImageResizerEngine.isImageFile("song.mp3"))
        assertFalse(ImageResizerEngine.isImageFile("video.mp4"))
        assertFalse(ImageResizerEngine.isImageFile("archive.zip"))
        assertFalse(ImageResizerEngine.isImageFile("notes.txt"))
    }

    @Test
    fun calculateTargetDimensions_percentageMode() {
        val params50 = ImageResizeParams(
            mode = ImageResizeMode.PERCENTAGE,
            percentage = 50
        )
        val (w50, h50) = ImageResizerEngine.calculateTargetDimensions(4000, 3000, params50)
        assertEquals(2000, w50)
        assertEquals(1500, h50)

        val params25 = ImageResizeParams(
            mode = ImageResizeMode.PERCENTAGE,
            percentage = 25
        )
        val (w25, h25) = ImageResizerEngine.calculateTargetDimensions(1920, 1080, params25)
        assertEquals(480, w25)
        assertEquals(270, h25)
    }

    @Test
    fun calculateTargetDimensions_dimensionsKeepAspectRatio() {
        // Landscape 4:3 image fit into 16:9 1920x1080 bounding box
        val params = ImageResizeParams(
            mode = ImageResizeMode.DIMENSIONS,
            targetWidth = 1920,
            targetHeight = 1080,
            keepAspectRatio = true
        )
        val (w, h) = ImageResizerEngine.calculateTargetDimensions(4000, 3000, params)
        // 3000 * (1080/3000 = 0.36) = 1080; 4000 * 0.36 = 1440
        assertEquals(1440, w)
        assertEquals(1080, h)
        assertEquals(4.0 / 3.0, w.toDouble() / h.toDouble(), 0.01)

        // Portrait 9:16 image fit into 1920x1080 bounding box
        val (pw, ph) = ImageResizerEngine.calculateTargetDimensions(1080, 1920, params)
        assertEquals(608, pw)
        assertEquals(1080, ph)

        // Square 1:1 image fit into 1920x1080 bounding box
        val (sw, sh) = ImageResizerEngine.calculateTargetDimensions(2000, 2000, params)
        assertEquals(1080, sw)
        assertEquals(1080, sh)
    }

    @Test
    fun calculateTargetDimensions_dimensionsExact() {
        val params = ImageResizeParams(
            mode = ImageResizeMode.DIMENSIONS,
            targetWidth = 800,
            targetHeight = 600,
            keepAspectRatio = false
        )
        val (w, h) = ImageResizerEngine.calculateTargetDimensions(4000, 2000, params)
        assertEquals(800, w)
        assertEquals(600, h)
    }

    @Test
    fun calculateInSampleSize_computesPowerOfTwo() {
        assertEquals(1, ImageResizerEngine.calculateInSampleSize(1000, 1000, 1920, 1080))
        assertEquals(2, ImageResizerEngine.calculateInSampleSize(4000, 3000, 1920, 1080))
        assertEquals(4, ImageResizerEngine.calculateInSampleSize(8000, 6000, 1920, 1080))
    }

    @Test
    fun resolveUniqueDestinationFile_avoidsCollisions() {
        val rootDir = tempFolder.root
        val file1 = ImageResizerEngine.resolveUniqueDestinationFile(rootDir, "photo", "jpg")
        assertEquals("photo.jpg", file1.name)
        file1.createNewFile()

        val file2 = ImageResizerEngine.resolveUniqueDestinationFile(rootDir, "photo", "jpg")
        assertEquals("photo (1).jpg", file2.name)
        file2.createNewFile()

        val file3 = ImageResizerEngine.resolveUniqueDestinationFile(rootDir, "photo", "jpg")
        assertEquals("photo (2).jpg", file3.name)
    }

    @Test
    fun estimateFileSize_calculatesExpectedApproximations() {
        val originalSize = 10_000_000L // 10 MB

        // 50% scale -> pixelRatio = 0.25 -> 2.5 MB (quality 85)
        val size50 = ImageResizerEngine.estimateFileSize(originalSize, 50, 85, com.antigravity.filemanager.domain.model.OutputImageFormat.ORIGINAL)
        assertEquals(2_500_000L, size50)

        // 100% scale -> pixelRatio = 1.0 -> 10 MB (quality 85)
        val size100 = ImageResizerEngine.estimateFileSize(originalSize, 100, 85, com.antigravity.filemanager.domain.model.OutputImageFormat.JPEG)
        assertEquals(10_000_000L, size100)

        // WebP format should apply 0.8 multiplier
        val sizeWebP = ImageResizerEngine.estimateFileSize(originalSize, 50, 85, com.antigravity.filemanager.domain.model.OutputImageFormat.WEBP)
        assertEquals(2_000_000L, sizeWebP)

        // PNG format should apply 1.5 multiplier
        val sizePng = ImageResizerEngine.estimateFileSize(originalSize, 50, 85, com.antigravity.filemanager.domain.model.OutputImageFormat.PNG)
        assertEquals(3_750_000L, sizePng)

        // Zero original size
        assertEquals(0L, ImageResizerEngine.estimateFileSize(0L, 50))
    }

    @Test
    fun calculateTargetDimensions_percentageOverload() {
        val (w, h) = ImageResizerEngine.calculateTargetDimensions(1920, 1080, 50)
        assertEquals(960, w)
        assertEquals(540, h)

        val (w75, h75) = ImageResizerEngine.calculateTargetDimensions(2000, 1000, 75)
        assertEquals(1500, w75)
        assertEquals(750, h75)
    }
}
