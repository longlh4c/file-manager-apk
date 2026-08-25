package com.antigravity.filemanager.presentation.network

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.antigravity.filemanager.presentation.components.FileManagerTopBar
import com.antigravity.filemanager.presentation.theme.*

@Composable
fun AccessFromNetworkScreen(
    onNavigateBack: () -> Unit,
    showBackButton: Boolean = true,
    viewModel: NetworkAccessViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            FileManagerTopBar(
                title = "FTP",
                showBackButton = showBackButton,
                onNavigationClick = onNavigateBack,
                actions = {
                    IconButton(onClick = { /* Device scan / help */ }) {
                        Icon(
                            imageVector = Icons.Default.Devices,
                            contentDescription = "Device",
                            tint = TextPrimary
                        )
                    }
                }
            )
        },
        containerColor = DarkBackground
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkBackground)
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // Main Config Card with cyan/teal outline matching screenshot 2
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(DarkBackground)
                    .border(1.dp, Color(0xFF2E6171), RoundedCornerShape(6.dp))
                    .padding(24.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Port field row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Port",
                            color = TextSecondary,
                            fontSize = 16.sp,
                            modifier = Modifier.width(90.dp)
                        )
                        OutlinedTextField(
                            value = uiState.port.toString(),
                            onValueChange = { viewModel.onPortChanged(it) },
                            singleLine = true,
                            enabled = !uiState.isRunning,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedBorderColor = TealPrimary,
                                unfocusedBorderColor = Color(0xFF444444),
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Password field row (optional, leave blank for Anonymous access)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Password",
                            color = TextSecondary,
                            fontSize = 16.sp,
                            modifier = Modifier.width(90.dp)
                        )
                        OutlinedTextField(
                            value = uiState.password,
                            onValueChange = { viewModel.onPasswordChanged(it) },
                            placeholder = { Text("(Leave blank for anonymous)", color = Color(0xFF666666), fontSize = 12.sp) },
                            singleLine = true,
                            enabled = !uiState.isRunning,
                            visualTransformation = PasswordVisualTransformation(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedBorderColor = TealPrimary,
                                unfocusedBorderColor = Color(0xFF444444),
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Show hidden files checkbox row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.onShowHiddenToggled(!uiState.showHiddenFiles) }
                            .padding(vertical = 6.dp)
                    ) {
                        Spacer(modifier = Modifier.width(48.dp))
                        Checkbox(
                            checked = uiState.showHiddenFiles,
                            onCheckedChange = { viewModel.onShowHiddenToggled(it) },
                            colors = CheckboxDefaults.colors(
                                checkedColor = TealPrimary,
                                uncheckedColor = TextSecondary,
                                checkmarkColor = PureBlack
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Show hidden files",
                            color = TextPrimary,
                            fontSize = 16.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                    HorizontalDivider(color = Color(0xFF2E6171), thickness = 1.dp)
                    Spacer(modifier = Modifier.height(24.dp))

                    // Description or Active URL
                    if (uiState.isRunning) {
                        Text(
                            text = "Service is active!\nEnter this address on your PC browser or FTP client:",
                            color = TealAccent,
                            fontSize = 15.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = uiState.accessUrl,
                            color = TextPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        Text(
                            text = "Start the service to access files\nfrom another device.",
                            color = TextPrimary,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Normal,
                            textAlign = TextAlign.Center,
                            lineHeight = 26.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // START / STOP SERVICE Button
                    Button(
                        onClick = { viewModel.toggleService() },
                        shape = RoundedCornerShape(4.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (uiState.isRunning) Color(0xFFEF5350) else Color(0xFF66BB6A).copy(alpha = 0.85f),
                            contentColor = PureBlack
                        ),
                        modifier = Modifier
                            .padding(horizontal = 24.dp)
                            .height(44.dp)
                    ) {
                        Text(
                            text = if (uiState.isRunning) "STOP SERVICE" else "START SERVICE",
                            color = PureBlack,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.8.sp
                        )
                    }
                }
            }
        }
    }
}
