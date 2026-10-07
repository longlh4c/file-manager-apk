package com.antigravity.filemanager.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoSizeSelectLarge
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.filemanager.domain.model.FileItem
import com.antigravity.filemanager.domain.model.ImageDimensions
import com.antigravity.filemanager.domain.model.ImageResizeMode
import com.antigravity.filemanager.domain.model.ImageResizeParams
import com.antigravity.filemanager.domain.model.OutputImageFormat
import com.antigravity.filemanager.presentation.theme.DarkCard
import com.antigravity.filemanager.presentation.theme.TealPrimary
import com.antigravity.filemanager.presentation.theme.TextPrimary
import com.antigravity.filemanager.presentation.theme.TextSecondary
import com.antigravity.filemanager.utils.ImageResizerEngine
import kotlin.math.roundToInt

@Composable
fun ImageResizerDialog(
    totalSelectedCount: Int,
    validImagesCount: Int,
    totalSizeBytes: Long = 0L,
    singleImageDimensions: ImageDimensions? = null,
    isCloud: Boolean = false,
    cloudAccountProviderName: String? = null,
    onConfirm: (ImageResizeParams) -> Unit,
    onDismiss: () -> Unit
) {
    var percentage by remember { mutableFloatStateOf(50f) }
    var selectedFormat by remember { mutableStateOf(OutputImageFormat.ORIGINAL) }
    var quality by remember { mutableFloatStateOf(85f) }

    val baseSizeBytes = remember(singleImageDimensions, totalSizeBytes) {
        if (singleImageDimensions != null && singleImageDimensions.sizeBytes > 0L) {
            singleImageDimensions.sizeBytes
        } else {
            totalSizeBytes
        }
    }

    val estimatedBytes = remember(baseSizeBytes, percentage, quality, selectedFormat) {
        if (baseSizeBytes > 0L) {
            ImageResizerEngine.estimateFileSize(
                originalSizeBytes = baseSizeBytes,
                percentage = percentage.toInt(),
                quality = quality.toInt(),
                format = selectedFormat
            )
        } else 0L
    }

    val estimatedDimensions = remember(percentage, singleImageDimensions) {
        if (singleImageDimensions != null) {
            ImageResizerEngine.calculateTargetDimensions(
                singleImageDimensions.width,
                singleImageDimensions.height,
                percentage.toInt()
            )
        } else null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoSizeSelectLarge,
                    contentDescription = null,
                    tint = TealPrimary,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = if (validImagesCount <= 1) "Resize Image" else "Resize Images ($validImagesCount)",
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Info header: Single image info or batch total info
                if (singleImageDimensions != null) {
                    Surface(
                        color = Color(0xFF24272B),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val origSizeStr = if (baseSizeBytes > 0L) " • ${FileItem.formatBytes(baseSizeBytes)}" else ""
                            Text(
                                text = "Original: ${singleImageDimensions.width} × ${singleImageDimensions.height}$origSizeStr",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                            if (estimatedDimensions != null) {
                                val estimatedSizeStr = if (estimatedBytes > 0L) " • ~${FileItem.formatBytes(estimatedBytes)}" else ""
                                val diffStr = if (baseSizeBytes > 0L && estimatedBytes > 0L && estimatedBytes < baseSizeBytes) {
                                    val percentReduction = (((baseSizeBytes - estimatedBytes).toDouble() / baseSizeBytes) * 100).roundToInt()
                                    if (percentReduction > 0) " (-$percentReduction%)" else ""
                                } else ""
                                Text(
                                    text = "Target: ${estimatedDimensions.first} × ${estimatedDimensions.second}$estimatedSizeStr$diffStr",
                                    color = TealPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                } else if (baseSizeBytes > 0L) {
                    Surface(
                        color = Color(0xFF24272B),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "$validImagesCount images • Total: ${FileItem.formatBytes(baseSizeBytes)}",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                            if (estimatedBytes > 0L) {
                                val diffStr = if (estimatedBytes < baseSizeBytes) {
                                    val percentReduction = (((baseSizeBytes - estimatedBytes).toDouble() / baseSizeBytes) * 100).roundToInt()
                                    if (percentReduction > 0) " (-$percentReduction%)" else ""
                                } else ""
                                Text(
                                    text = "Estimated total: ~${FileItem.formatBytes(estimatedBytes)}$diffStr",
                                    color = TealPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                // Mixed selection notice (if non-image files are present)
                if (totalSelectedCount > validImagesCount) {
                    Surface(
                        color = Color(0xFF2E2C22),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = Color(0xFFFFD54F),
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "$validImagesCount image${if (validImagesCount > 1) "s" else ""} selected (${totalSelectedCount - validImagesCount} non-image file${if (totalSelectedCount - validImagesCount > 1) "s" else ""} ignored)",
                                color = Color(0xFFFFE082),
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                // Scale (Percentage) Section
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Scale", color = TextSecondary, fontSize = 13.sp)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${percentage.toInt()}%",
                                color = TealPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            if (estimatedBytes > 0L) {
                                Text(
                                    text = "(~${FileItem.formatBytes(estimatedBytes)})",
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    // Quick Percentage Chips
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(25, 50, 75).forEach { preset ->
                            val isSelected = percentage.toInt() == preset
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSelected) TealPrimary.copy(alpha = 0.2f) else Color.Transparent)
                                    .border(
                                        1.dp,
                                        if (isSelected) TealPrimary else TextSecondary.copy(alpha = 0.35f),
                                        RoundedCornerShape(6.dp)
                                    )
                                    .clickable { percentage = preset.toFloat() }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "$preset%",
                                    color = if (isSelected) TealPrimary else TextSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }

                    Slider(
                        value = percentage,
                        onValueChange = { percentage = it },
                        valueRange = 10f..100f,
                        steps = 17,
                        colors = SliderDefaults.colors(
                            thumbColor = TealPrimary,
                            activeTrackColor = TealPrimary,
                            inactiveTrackColor = Color(0xFF333333)
                        )
                    )
                }

                HorizontalDivider(color = Color(0xFF2E2E2E), thickness = 0.5.dp)

                // Output Format Section
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = "Format", color = TextSecondary, fontSize = 13.sp)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutputImageFormat.entries.forEach { fmt ->
                            val isSelected = selectedFormat == fmt
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSelected) TealPrimary.copy(alpha = 0.2f) else Color.Transparent)
                                    .border(
                                        1.dp,
                                        if (isSelected) TealPrimary else TextSecondary.copy(alpha = 0.35f),
                                        RoundedCornerShape(6.dp)
                                    )
                                    .clickable { selectedFormat = fmt }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = fmt.displayName,
                                    color = if (isSelected) TealPrimary else TextSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }

                // Quality Slider (for JPG, WEBP, or ORIGINAL)
                if (selectedFormat != OutputImageFormat.PNG) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "Quality", color = TextSecondary, fontSize = 13.sp)
                            Text(
                                text = "${quality.toInt()}%",
                                color = TealPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }

                        Slider(
                            value = quality,
                            onValueChange = { quality = it },
                            valueRange = 10f..100f,
                            steps = 17,
                            colors = SliderDefaults.colors(
                                thumbColor = TealPrimary,
                                activeTrackColor = TealPrimary,
                                inactiveTrackColor = Color(0xFF333333)
                            )
                        )
                    }
                }

                // Overwrite Notice
                Text(
                    text = if (isCloud) {
                        "Warning: Original file(s) on ${cloudAccountProviderName ?: "Cloud"} will be directly overwritten."
                    } else {
                        "Warning: Original file(s) will be directly overwritten."
                    },
                    color = Color(0xFFFFD54F),
                    fontSize = 12.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val params = ImageResizeParams(
                        mode = ImageResizeMode.PERCENTAGE,
                        percentage = percentage.toInt().coerceIn(1, 100),
                        quality = quality.toInt().coerceIn(1, 100),
                        format = selectedFormat
                    )
                    onConfirm(params)
                },
                colors = ButtonDefaults.buttonColors(containerColor = TealPrimary)
            ) {
                Text("RESIZE", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = TextSecondary)
            }
        },
        containerColor = DarkCard
    )
}
