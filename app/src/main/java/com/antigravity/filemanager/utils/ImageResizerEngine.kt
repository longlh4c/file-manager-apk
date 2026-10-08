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
import java.text.SimpleDateFormat
import java.util.Date
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

    fun extractOriginalCreationDate(file: File, fallbackTimestamp: Long = 0L): Long {
        if (file.exists()) {
            try {
                val exif = ExifInterface(file.absolutePath)
                val dateStr = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
                if (!dateStr.isNullOrBlank()) {
                    val sdf = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.ROOT)
                    val parsed = sdf.parse(dateStr)?.time
                    if (parsed != null && parsed > 0L) {
                        return parsed
                    }
                }
            } catch (_: Exception) {}
        }

        if (fallbackTimestamp > 0L) {
            return fallbackTimestamp
        }
        val mtime = file.lastModified()
        return if (mtime > 0L) mtime else System.currentTimeMillis()
    }

    private fun copyExifAndTimestamp(sourceFile: File, targetFile: File, customTimestamp: Long? = null) {
        val targetTimestamp = customTimestamp?.takeIf { it > 0L }
            ?: extractOriginalCreationDate(sourceFile)

        try {
            val sourceExif = ExifInterface(sourceFile.absolutePath)
            val destExif = ExifInterface(targetFile.absolutePath)

            val tagsToCopy = arrayOf(
                ExifInterface.TAG_DATETIME,
                ExifInterface.TAG_DATETIME_ORIGINAL,
                ExifInterface.TAG_DATETIME_DIGITIZED,
                ExifInterface.TAG_SUBSEC_TIME,
                ExifInterface.TAG_GPS_DATESTAMP,
                ExifInterface.TAG_GPS_TIMESTAMP,
                ExifInterface.TAG_GPS_LATITUDE,
                ExifInterface.TAG_GPS_LATITUDE_REF,
                ExifInterface.TAG_GPS_LONGITUDE,
                ExifInterface.TAG_GPS_LONGITUDE_REF,
                ExifInterface.TAG_GPS_ALTITUDE,
                ExifInterface.TAG_GPS_ALTITUDE_REF,
                ExifInterface.TAG_MAKE,
                ExifInterface.TAG_MODEL,
                ExifInterface.TAG_FOCAL_LENGTH,
                ExifInterface.TAG_F_NUMBER,
                ExifInterface.TAG_EXPOSURE_TIME,
                ExifInterface.TAG_WHITE_BALANCE,
                ExifInterface.TAG_FLASH
            )

            for (tag in tagsToCopy) {
                val value = sourceExif.getAttribute(tag)
                if (!value.isNullOrBlank()) {
                    destExif.setAttribute(tag, value)
                }
            }

            // Ensure original date is present in EXIF tags
            if (targetTimestamp > 0L) {
                val sdf = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.ROOT)
                val dateStr = sdf.format(Date(targetTimestamp))
                if (destExif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL).isNullOrBlank()) {
                    destExif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateStr)
                }
                if (destExif.getAttribute(ExifInterface.TAG_DATETIME).isNullOrBlank()) {
                    destExif.setAttribute(ExifInterface.TAG_DATETIME, dateStr)
                }
            }

            destExif.saveAttributes()
        } catch (_: Exception) {
            // Some file formats might not support all EXIF attributes
        }

        if (targetTimestamp > 0L) {
            targetFile.setLastModified(targetTimestamp)
        }
    }

    fun resizeFile(
        sourceFile: File,
        targetDir: File = sourceFile.parentFile ?: sourceFile,
        params: ImageResizeParams,
        overwrite: Boolean = true,
        customTimestamp: Long? = null
    ): File {
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
        val destFile = if (overwrite) {
            File(targetDir, "$baseName.$outExt")
        } else {
            resolveUniqueDestinationFile(targetDir, baseName, outExt)
        }

        val tempOutput = File(targetDir, ".tmp_resize_${System.currentTimeMillis()}_$baseName.$outExt")

        try {
            FileOutputStream(tempOutput).use { fos ->
                finalBitmap.compress(compressFormat, params.quality.coerceIn(1, 100), fos)
                fos.flush()
            }

            copyExifAndTimestamp(sourceFile, tempOutput, customTimestamp)

            if (overwrite) {
                if (destFile.exists()) {
                    destFile.delete()
                }
                val renamed = tempOutput.renameTo(destFile)
                if (!renamed) {
                    tempOutput.copyTo(destFile, overwrite = true)
                    tempOutput.delete()
                }
                if (sourceFile.absolutePath != destFile.absolutePath && sourceFile.exists()) {
                    sourceFile.delete()
                }
            } else {
                val renamed = tempOutput.renameTo(destFile)
                if (!renamed) {
                    tempOutput.copyTo(destFile, overwrite = true)
                    tempOutput.delete()
                }
            }

            val finalTimestamp = customTimestamp?.takeIf { it > 0L }
                ?: extractOriginalCreationDate(sourceFile)
            if (finalTimestamp > 0L) {
                destFile.setLastModified(finalTimestamp)
            }
        } finally {
            finalBitmap.recycle()
            if (tempOutput.exists()) {
                tempOutput.delete()
            }
        }

        return destFile
    }
}
