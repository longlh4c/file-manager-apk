package com.antigravity.filemanager.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.filemanager.presentation.theme.DarkCard
import com.antigravity.filemanager.presentation.theme.TealPrimary
import com.antigravity.filemanager.presentation.theme.TextPrimary
import com.antigravity.filemanager.presentation.theme.TextSecondary

enum class CompressFormat(val displayName: String, val extension: String) {
    ZIP("ZIP", "zip"),
    SEVEN_Z("7Z", "7z")
}

@Composable
fun CompressDialog(
    initialBaseName: String = "Archive",
    onConfirm: (archiveFileName: String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedFormat by remember { mutableStateOf(CompressFormat.ZIP) }
    var textFieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = initialBaseName,
                selection = androidx.compose.ui.text.TextRange(0, initialBaseName.length)
            )
        )
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Compress",
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Filename Input
                OutlinedTextField(
                    value = textFieldValue,
                    onValueChange = { textFieldValue = it },
                    singleLine = true,
                    label = { Text("Archive name", color = TextSecondary) },
                    trailingIcon = {
                        Text(
                            text = ".${selectedFormat.extension}",
                            color = TealPrimary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = TealPrimary,
                        unfocusedBorderColor = TextSecondary,
                        cursorColor = TealPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )

                // Format Selector Chips
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Format",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CompressFormat.entries.forEach { format ->
                            val isSelected = selectedFormat == format
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) TealPrimary.copy(alpha = 0.18f) else Color.Transparent)
                                    .border(
                                        1.dp,
                                        if (isSelected) TealPrimary else TextSecondary.copy(alpha = 0.35f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable { selectedFormat = format }
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = format.displayName,
                                    color = if (isSelected) TealPrimary else TextSecondary,
                                    fontSize = 14.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val rawText = textFieldValue.text.trim().ifEmpty { "Archive" }
                    // Strip existing extension if user accidentally typed it
                    val cleanBase = rawText.removeSuffix(".zip").removeSuffix(".ZIP")
                        .removeSuffix(".7z").removeSuffix(".7Z")
                        .ifEmpty { "Archive" }
                    val finalFileName = "$cleanBase.${selectedFormat.extension}"
                    onConfirm(finalFileName)
                },
                colors = ButtonDefaults.buttonColors(containerColor = TealPrimary, contentColor = Color.Black)
            ) {
                Text(text = "COMPRESS", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "CANCEL", color = TextSecondary)
            }
        },
        containerColor = DarkCard
    )
}
