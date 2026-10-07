package com.antigravity.filemanager.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoSizeSelectLarge
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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

@Composable
fun ImageResizerDialog(
    totalSelectedCount: Int,
    validImagesCount: Int,
    singleImageDimensions: ImageDimensions? = null,
    isCloud: Boolean = false,
    cloudAccountProviderName: String? = null,
    onConfirm: (ImageResizeParams) -> Unit,
    onDismiss: () -> Unit
) {
    var resizeMode by remember { mutableStateOf(ImageResizeMode.PERCENTAGE) }
    var percentage by remember { mutableFloatStateOf(50f) }

    val initialWidth = singleImageDimensions?.width ?: 1920
    val initialHeight = singleImageDimensions?.height ?: 1080

    var customWidthStr by remember { mutableStateOf(initialWidth.toString()) }
    var customHeightStr by remember { mutableStateOf(initialHeight.toString()) }
    var keepAspectRatio by remember { mutableStateOf(true) }

    var selectedFormat by remember { mutableStateOf(OutputImageFormat.ORIGINAL) }
    var quality by remember { mutableFloatStateOf(85f) }

    val aspectRatio = remember(singleImageDimensions) {
        if (singleImageDimensions != null && singleImageDimensions.height > 0) {
            singleImageDimensions.width.toFloat() / singleImageDimensions.height.toFloat()
        } else {
            16f / 9f
        }
    }

    val currentWidth = customWidthStr.toIntOrNull() ?: initialWidth
    val currentHeight = customHeightStr.toIntOrNull() ?: initialHeight

    val estimatedDimensions = remember(resizeMode, percentage, currentWidth, currentHeight, singleImageDimensions, keepAspectRatio) {
        if (singleImageDimensions != null) {
            val params = ImageResizeParams(
                mode = resizeMode,
                percentage = percentage.toInt(),
                targetWidth = currentWidth,
                targetHeight = currentHeight,
                keepAspectRatio = keepAspectRatio
            )
            ImageResizerEngine.calculateTargetDimensions(
                singleImageDimensions.width,
                singleImageDimensions.height,
                params
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
                // Info header: Single image info or mixed selection notice
                if (singleImageDimensions != null) {
                    Surface(
                        color = Color(0xFF24272B),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Original: ${singleImageDimensions.width} × ${singleImageDimensions.height} • ${FileItem.formatBytes(singleImageDimensions.sizeBytes)}",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                            if (estimatedDimensions != null) {
                                Text(
                                    text = "Target: ${estimatedDimensions.first} × ${estimatedDimensions.second}",
                                    color = TealPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                } else if (totalSelectedCount > validImagesCount) {
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

                // Mode Selector: Percentage (%) vs Dimensions (px)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF24272B))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val isPercent = resizeMode == ImageResizeMode.PERCENTAGE
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isPercent) TealPrimary else Color.Transparent)
                            .clickable { resizeMode = ImageResizeMode.PERCENTAGE }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Percentage (%)",
                            color = if (isPercent) Color.Black else TextSecondary,
                            fontWeight = if (isPercent) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp
                        )
                    }

                    val isDim = resizeMode == ImageResizeMode.DIMENSIONS
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isDim) TealPrimary else Color.Transparent)
                            .clickable { resizeMode = ImageResizeMode.DIMENSIONS }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Dimensions (px)",
                            color = if (isDim) Color.Black else TextSecondary,
                            fontWeight = if (isDim) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp
                        )
                    }
                }

                // Settings for PERCENTAGE mode
                if (resizeMode == ImageResizeMode.PERCENTAGE) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "Scale", color = TextSecondary, fontSize = 13.sp)
                            Text(
                                text = "${percentage.toInt()}%",
                                color = TealPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
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
                } else {
                    // Settings for DIMENSIONS mode
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Presets: 1080p, 720p, 480p, Square
                        Text(text = "Resolution Presets", color = TextSecondary, fontSize = 13.sp)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val presets = listOf(
                                "1080p" to (1920 to 1080),
                                "720p" to (1280 to 720),
                                "480p" to (854 to 480),
                                "Square" to (1080 to 1080)
                            )
                            presets.forEach { (label, dim) ->
                                val isSelected = customWidthStr == dim.first.toString() && customHeightStr == dim.second.toString()
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isSelected) TealPrimary.copy(alpha = 0.2f) else Color.Transparent)
                                        .border(
                                            1.dp,
                                            if (isSelected) TealPrimary else TextSecondary.copy(alpha = 0.35f),
                                            RoundedCornerShape(6.dp)
                                        )
                                        .clickable {
                                            customWidthStr = dim.first.toString()
                                            customHeightStr = dim.second.toString()
                                        }
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = label,
                                        color = if (isSelected) TealPrimary else TextSecondary,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }

                        // Custom Width & Height Inputs
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = customWidthStr,
                                onValueChange = { newVal ->
                                    val filtered = newVal.filter { it.isDigit() }.take(5)
                                    customWidthStr = filtered
                                    if (keepAspectRatio && filtered.isNotEmpty()) {
                                        val w = filtered.toIntOrNull() ?: 1
                                        val h = (w / aspectRatio).toInt().coerceAtLeast(1)
                                        customHeightStr = h.toString()
                                    }
                                },
                                label = { Text("Width", fontSize = 12.sp) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary,
                                    focusedBorderColor = TealPrimary,
                                    unfocusedBorderColor = Color(0xFF333333)
                                ),
                                modifier = Modifier.weight(1f)
                            )

                            Text(text = "×", color = TextSecondary, fontSize = 18.sp)

                            OutlinedTextField(
                                value = customHeightStr,
                                onValueChange = { newVal ->
                                    val filtered = newVal.filter { it.isDigit() }.take(5)
                                    customHeightStr = filtered
                                    if (keepAspectRatio && filtered.isNotEmpty()) {
                                        val h = filtered.toIntOrNull() ?: 1
                                        val w = (h * aspectRatio).toInt().coerceAtLeast(1)
                                        customWidthStr = w.toString()
                                    }
                                },
                                label = { Text("Height", fontSize = 12.sp) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary,
                                    focusedBorderColor = TealPrimary,
                                    unfocusedBorderColor = Color(0xFF333333)
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // Keep aspect ratio toggle
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { keepAspectRatio = !keepAspectRatio },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Checkbox(
                                checked = keepAspectRatio,
                                onCheckedChange = { keepAspectRatio = it },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = TealPrimary,
                                    checkmarkColor = Color.Black
                                )
                            )
                            Text(
                                text = "Keep aspect ratio",
                                color = TextPrimary,
                                fontSize = 13.sp
                            )
                        }
                    }
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

                // Destination Note
                Text(
                    text = if (isCloud) {
                        "Output will be saved to: Resized/ folder on ${cloudAccountProviderName ?: "Cloud"}"
                    } else {
                        "Output will be saved to: Resized/ subfolder"
                    },
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val targetW = customWidthStr.toIntOrNull()?.coerceAtLeast(1) ?: initialWidth
                    val targetH = customHeightStr.toIntOrNull()?.coerceAtLeast(1) ?: initialHeight
                    val params = ImageResizeParams(
                        mode = resizeMode,
                        percentage = percentage.toInt().coerceIn(1, 100),
                        targetWidth = targetW,
                        targetHeight = targetH,
                        keepAspectRatio = keepAspectRatio,
                        quality = quality.toInt().coerceIn(1, 100),
                        format = selectedFormat,
                        subfolderName = "Resized"
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
