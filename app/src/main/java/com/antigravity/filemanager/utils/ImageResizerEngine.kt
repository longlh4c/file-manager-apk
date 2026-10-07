package com.antigravity.filemanager.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import com.antigravity.filemanager.domain.model.ImageDimensions
import com.antigravity.filemanager.domain.model.ImageResizeMode
import com.antigravity.filemanager.domain.model.ImageResizeParams
import com.antigravity.filemanager.domain.model.OutputImageFormat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import kotlin.math.roundToInt

object ImageResizerEngine {

    val supportedExtensions = setOf("jpg", "jpeg", "png", "webp", "bmp", "heic", "heif")

    fun isImageFile(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in supportedExtensions
    }

    fun isImageFile(file: File): Boolean {
        if (file.isDirectory) return false
        return isImageFile(file.path)
    }

    fun getImageDimensions(filePath: String): ImageDimensions? {
        val file = File(filePath)
        if (!file.exists() || !isImageFile(file)) return null

        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return null

            val orientation = try {
                val exif = ExifInterface(file.absolutePath)
                exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } catch (e: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }

            val isSwapped = orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
                    orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                    orientation == ExifInterface.ORIENTATION_TRANSVERSE ||
                    orientation == ExifInterface.ORIENTATION_ROTATE_270

            val effectiveWidth = if (isSwapped) options.outHeight else options.outWidth
            val effectiveHeight = if (isSwapped) options.outWidth else options.outHeight

            ImageDimensions(
                width = effectiveWidth,
                height = effectiveHeight,
                sizeBytes = file.length()
            )
        } catch (e: Exception) {
            null
        }
    }

    fun calculateTargetDimensions(
        origWidth: Int,
        origHeight: Int,
        percentage: Int
    ): Pair<Int, Int> {
        if (origWidth <= 0 || origHeight <= 0) return 1 to 1
        val scale = percentage.coerceIn(1, 1000) / 100.0
        val targetW = (origWidth * scale).roundToInt().coerceAtLeast(1)
        val targetH = (origHeight * scale).roundToInt().coerceAtLeast(1)
        return targetW to targetH
    }

    fun estimateFileSize(
        originalSizeBytes: Long,
        percentage: Int,
        quality: Int = 85,
        format: OutputImageFormat = OutputImageFormat.ORIGINAL
    ): Long {
        if (originalSizeBytes <= 0L) return 0L
        val scale = percentage.coerceIn(1, 100) / 100.0
        val pixelRatio = scale * scale
        val qualityRatio = (quality.coerceIn(10, 100) / 85.0).coerceIn(0.2, 1.3)
        val formatMultiplier = when (format) {
            OutputImageFormat.WEBP -> 0.8
            OutputImageFormat.PNG -> 1.5
            OutputImageFormat.JPEG, OutputImageFormat.ORIGINAL -> 1.0
        }
        val estimated = (originalSizeBytes * pixelRatio * qualityRatio * formatMultiplier).toLong()
        val minBound = minOf(128L, originalSizeBytes)
        val maxBound = maxOf(minBound, originalSizeBytes * 3)
        return estimated.coerceIn(minBound, maxBound)
    }

    fun calculateTargetDimensions(
        origWidth: Int,
        origHeight: Int,
        params: ImageResizeParams
    ): Pair<Int, Int> {
        if (origWidth <= 0 || origHeight <= 0) return 1 to 1

        return when (params.mode) {
            ImageResizeMode.PERCENTAGE -> {
                val scale = params.percentage.coerceIn(1, 1000) / 100.0
                val targetW = (origWidth * scale).roundToInt().coerceAtLeast(1)
                val targetH = (origHeight * scale).roundToInt().coerceAtLeast(1)
                targetW to targetH
            }
            ImageResizeMode.DIMENSIONS -> {
                if (params.keepAspectRatio) {
                    val reqW = params.targetWidth.coerceAtLeast(1).toDouble()
                    val reqH = params.targetHeight.coerceAtLeast(1).toDouble()
                    val scale = minOf(reqW / origWidth, reqH / origHeight)
                    val targetW = (origWidth * scale).roundToInt().coerceAtLeast(1)
                    val targetH = (origHeight * scale).roundToInt().coerceAtLeast(1)
                    targetW to targetH
                } else {
                    params.targetWidth.coerceAtLeast(1) to params.targetHeight.coerceAtLeast(1)
                }
            }
        }
    }

    fun calculateInSampleSize(rawWidth: Int, rawHeight: Int, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        if (rawHeight > reqHeight || rawWidth > reqWidth) {
            val halfHeight = rawHeight / 2
            val halfWidth = rawWidth / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    fun resolveUniqueDestinationFile(targetDir: File, baseName: String, extension: String): File {
        var candidate = File(targetDir, "$baseName.$extension")
        if (!candidate.exists()) return candidate

        var index = 1
        while (candidate.exists()) {
            candidate = File(targetDir, "$baseName ($index).$extension")
            index++
        }
        return candidate
    }

    fun resizeFile(sourceFile: File, targetDir: File, params: ImageResizeParams): File {
        if (!sourceFile.exists()) {
            throw IOException("Source file does not exist: ${sourceFile.path}")
        }

        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(sourceFile.absolutePath, boundsOptions)
        val rawWidth = boundsOptions.outWidth
        val rawHeight = boundsOptions.outHeight
        if (rawWidth <= 0 || rawHeight <= 0) {
            throw IOException("Unable to decode image bounds for ${sourceFile.name}")
        }

        val orientation = try {
            val exif = ExifInterface(sourceFile.absolutePath)
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val isSwapped = orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
                orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                orientation == ExifInterface.ORIENTATION_TRANSVERSE ||
                orientation == ExifInterface.ORIENTATION_ROTATE_270

        val effectiveOrigWidth = if (isSwapped) rawHeight else rawWidth
        val effectiveOrigHeight = if (isSwapped) rawWidth else rawHeight

        val (targetWidth, targetHeight) = calculateTargetDimensions(
            effectiveOrigWidth,
            effectiveOrigHeight,
            params
        )

        val reqDecodeWidth = if (isSwapped) targetHeight else targetWidth
        val reqDecodeHeight = if (isSwapped) targetWidth else targetHeight
        val inSampleSize = calculateInSampleSize(rawWidth, rawHeight, reqDecodeWidth, reqDecodeHeight)

        val decodeOptions = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            this.inJustDecodeBounds = false
        }

        val decodedBitmap = BitmapFactory.decodeFile(sourceFile.absolutePath, decodeOptions)
            ?: throw IOException("Failed to decode image bitmap: ${sourceFile.name}")

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
        }

        val orientedBitmap = if (matrix.isIdentity) {
            decodedBitmap
        } else {
            val transformed = Bitmap.createBitmap(
                decodedBitmap,
                0,
                0,
                decodedBitmap.width,
                decodedBitmap.height,
                matrix,
                true
            )
            if (transformed != decodedBitmap) {
                decodedBitmap.recycle()
            }
            transformed
        }

        val scaledBitmap = if (orientedBitmap.width == targetWidth && orientedBitmap.height == targetHeight) {
            orientedBitmap
        } else {
            val scaled = Bitmap.createScaledBitmap(orientedBitmap, targetWidth, targetHeight, true)
            if (scaled != orientedBitmap) {
                orientedBitmap.recycle()
            }
            scaled
        }

        val origExt = sourceFile.extension.lowercase(Locale.ROOT)
        val (outExt, compressFormat) = when (params.format) {
            OutputImageFormat.JPEG -> "jpg" to Bitmap.CompressFormat.JPEG
            OutputImageFormat.PNG -> "png" to Bitmap.CompressFormat.PNG
            OutputImageFormat.WEBP -> "webp" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            OutputImageFormat.ORIGINAL -> when (origExt) {
                "png" -> "png" to Bitmap.CompressFormat.PNG
                "webp" -> "webp" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                else -> "jpg" to Bitmap.CompressFormat.JPEG
            }
        }

        val finalBitmap = if (compressFormat == Bitmap.CompressFormat.JPEG && scaledBitmap.hasAlpha()) {
            val solidBitmap = Bitmap.createBitmap(scaledBitmap.width, scaledBitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(solidBitmap)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(scaledBitmap, 0f, 0f, null)
            scaledBitmap.recycle()
            solidBitmap
        } else {
            scaledBitmap
        }

        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        val baseName = sourceFile.nameWithoutExtension
        val destFile = resolveUniqueDestinationFile(targetDir, baseName, outExt)

        try {
            FileOutputStream(destFile).use { fos ->
                finalBitmap.compress(compressFormat, params.quality.coerceIn(1, 100), fos)
                fos.flush()
            }
        } finally {
            finalBitmap.recycle()
        }

        return destFile
    }
}
