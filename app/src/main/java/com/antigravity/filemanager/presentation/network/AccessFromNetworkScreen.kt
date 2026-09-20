package com.antigravity.filemanager.presentation.network

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.antigravity.filemanager.presentation.components.FileManagerTopBar
import com.antigravity.filemanager.presentation.theme.*

// Theme Colors for Access from Network
private val SoftPastelMint = Color(0xFF5EEAD4)
private val SoftPastelSage = Color(0xFF80CBC4)

@Composable
fun AccessFromNetworkScreen(
    onNavigateBack: () -> Unit,
    showBackButton: Boolean = true,
    onDualPanelToggle: (() -> Unit)? = null,
    isDualPanelActive: Boolean = false,
    onCloseDualPanel: (() -> Unit)? = null,
    viewModel: NetworkAccessViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(uiState.copiedMessage) {
        uiState.copiedMessage?.let { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }

    Scaffold(
        topBar = {
            FileManagerTopBar(
                title = "FTP",
                showBackButton = showBackButton,
                isDualPanelActive = isDualPanelActive,
                navigationIcon = if (onCloseDualPanel != null) {
                    {
                        IconButton(onClick = onCloseDualPanel) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close Dual Panel",
                                tint = TextPrimary
                            )
                        }
                    }
                } else null,
                onNavigationClick = {
                    if (showBackButton) onNavigateBack() else onDualPanelToggle?.invoke()
                },
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = DarkBackground
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkBackground)
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Web HTTP Port field row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Web Port",
                    color = TextSecondary,
                    fontSize = 15.sp,
                    modifier = Modifier.width(100.dp)
                )
                OutlinedTextField(
                    value = uiState.httpPortInput,
                    onValueChange = { input ->
                        viewModel.onHttpPortChanged(input)
                    },
                    placeholder = { Text("8080", color = Color(0xFF666666), fontSize = 14.sp) },
                    singleLine = true,
                    enabled = !uiState.isRunning,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        disabledTextColor = TextSecondary,
                        focusedBorderColor = SoftPastelMint,
                        unfocusedBorderColor = Color(0xFF444444),
                        disabledBorderColor = Color(0xFF2A2A2A),
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { focusState ->
                            if (!focusState.isFocused && uiState.httpPortInput.isBlank()) {
                                viewModel.onHttpPortChanged("8080")
                            }
                        }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // FTP Port field row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "FTP Port",
                    color = TextSecondary,
                    fontSize = 15.sp,
                    modifier = Modifier.width(100.dp)
                )
                OutlinedTextField(
                    value = uiState.portInput,
                    onValueChange = { input ->
                        viewModel.onPortChanged(input)
                    },
                    placeholder = { Text("1524", color = Color(0xFF666666), fontSize = 14.sp) },
                    singleLine = true,
                    enabled = !uiState.isRunning,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        disabledTextColor = TextSecondary,
                        focusedBorderColor = SoftPastelMint,
                        unfocusedBorderColor = Color(0xFF444444),
                        disabledBorderColor = Color(0xFF2A2A2A),
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { focusState ->
                            if (!focusState.isFocused && uiState.portInput.isBlank()) {
                                viewModel.onPortChanged("1524")
                            }
                        }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Password field row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Password",
                    color = TextSecondary,
                    fontSize = 15.sp,
                    modifier = Modifier.width(100.dp)
                )
                OutlinedTextField(
                    value = uiState.password,
                    onValueChange = { input ->
                        val filtered = input.filter { it.isLetterOrDigit() }.take(8)
                        viewModel.onPasswordChanged(filtered)
                    },
                    placeholder = { Text("(Leave blank for anonymous)", color = Color(0xFF666666), fontSize = 12.sp) },
                    singleLine = true,
                    enabled = !uiState.isRunning,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        disabledTextColor = TextSecondary,
                        focusedBorderColor = SoftPastelMint,
                        unfocusedBorderColor = Color(0xFF444444),
                        disabledBorderColor = Color(0xFF2A2A2A),
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(color = Color(0xFF222222), thickness = 1.dp)
            Spacer(modifier = Modifier.height(24.dp))

            // START / STOP SERVICE Button
            Button(
                onClick = { viewModel.toggleService() },
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (uiState.isRunning) PastelCoral else SoftPastelMint,
                    contentColor = PureBlack
                ),
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .height(46.dp)
                    .fillMaxWidth(0.7f)
            ) {
                Text(
                    text = if (uiState.isRunning) "STOP SERVICE" else "START SERVICE",
                    color = PureBlack,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (uiState.isRunning) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Service is active!",
                        color = SoftPastelSage,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Normal,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Enter this address on your PC browser or\nFTP client:",
                        color = SoftPastelSage,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )
                    Spacer(modifier = Modifier.height(18.dp))

                    val ip = uiState.ipAddress ?: "127.0.0.1"

                    // Display URLs with aligned icons and aligned "://"
                    Column(
                        horizontalAlignment = Alignment.Start,
                        modifier = Modifier.wrapContentWidth()
                    ) {
                        AlignedUrlItem(
                            icon = Icons.Default.Language,
                            iconDescription = "Web Browser",
                            scheme = "http",
                            address = "$ip:${uiState.httpPort}",
                            onClick = {
                                viewModel.copyToClipboard(context, "http://$ip:${uiState.httpPort}", "Web address")
                            }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        AlignedUrlItem(
                            icon = Icons.Default.Devices,
                            iconDescription = "FTP Client",
                            scheme = "ftp",
                            address = "$ip:${uiState.port}",
                            onClick = {
                                viewModel.copyToClipboard(context, "ftp://$ip:${uiState.port}", "FTP address")
                            }
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Start the service to access files\nfrom your computer's browser or FTP client.",
                        color = TextSecondary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp
                    )
                }
            }
        }
    }
}

/**
 * Row displaying an endpoint URL where both the leading icon and the "://" delimiter
 * are vertically aligned across lines:
 * (icon Web) http://192.168.1.7:8080
 * (icon FTP)  ftp://192.168.1.7:1524
 */
@Composable
private fun AlignedUrlItem(
    icon: ImageVector,
    iconDescription: String,
    scheme: String,
    address: String,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = iconDescription,
            tint = SoftPastelMint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        // Scheme fixed width and right-aligned so "http" and "ftp" end at the exact same horizontal position
        Box(
            modifier = Modifier.width(42.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            Text(
                text = scheme,
                color = Color.White,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold
            )
        }
        // "://" and host:port start at the exact same horizontal position
        Text(
            text = "://$address",
            color = Color.White,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
