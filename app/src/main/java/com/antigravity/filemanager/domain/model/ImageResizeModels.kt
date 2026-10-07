package com.antigravity.filemanager.domain.model

enum class ImageResizeMode {
    PERCENTAGE,
    DIMENSIONS
}

enum class OutputImageFormat(val displayName: String, val extension: String) {
    ORIGINAL("Original", ""),
    JPEG("JPG", "jpg"),
    PNG("PNG", "png"),
    WEBP("WEBP", "webp")
}

data class ImageDimensions(
    val width: Int,
    val height: Int,
    val sizeBytes: Long = 0L
)

data class ImageResizeParams(
    val mode: ImageResizeMode = ImageResizeMode.PERCENTAGE,
    val percentage: Int = 50,
    val targetWidth: Int = 1920,
    val targetHeight: Int = 1080,
    val keepAspectRatio: Boolean = true,
    val quality: Int = 85,
    val format: OutputImageFormat = OutputImageFormat.ORIGINAL,
    val subfolderName: String = "Resized"
)
