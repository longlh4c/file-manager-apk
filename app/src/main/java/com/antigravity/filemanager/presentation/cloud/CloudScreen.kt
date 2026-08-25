package com.antigravity.filemanager.presentation.cloud

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.antigravity.filemanager.domain.model.CloudAccount
import com.antigravity.filemanager.domain.model.CloudProvider
import com.antigravity.filemanager.presentation.components.AddCloudDialog
import com.antigravity.filemanager.presentation.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudScreen(
    onNavigateBack: () -> Unit,
    onNavigateToCloudBrowser: (accountId: String, accountName: String) -> Unit = { _, _ -> },
    viewModel: CloudViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var accountToDelete by remember { mutableStateOf<CloudAccount?>(null) }

    BackHandler {
        if (uiState.isReorderMode) {
            viewModel.toggleReorderMode()
        } else {
            onNavigateBack()
        }
    }

    if (uiState.showAddDialog) {
        AddCloudDialog(
            onSelectProvider = { provider, name, email, token, session ->
                viewModel.addAccount(provider, name, email, token, session) { newId, newName ->
                    onNavigateToCloudBrowser(newId, newName)
                }
            },
            onDismiss = { viewModel.setShowAddDialog(false) },
            isAddingAccount = uiState.isAddingAccount,
            addAccountError = uiState.addAccountError,
            onClearAddAccountError = { viewModel.clearAddAccountError() }
        )
    }


    if (accountToDelete != null) {
        AlertDialog(
            onDismissRequest = { accountToDelete = null },
            title = {
                Text(
                    text = "Remove Cloud Account",
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to remove \"${accountToDelete?.accountName}\" (${accountToDelete?.email}) from cloud storage?",
                    color = TextSecondary,
                    fontSize = 15.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val id = accountToDelete?.id
                        if (id != null) viewModel.removeAccount(id)
                        accountToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350), contentColor = Color.White)
                ) {
                    Text("REMOVE")
                }
            },
            dismissButton = {
                TextButton(onClick = { accountToDelete = null }) {
                    Text("CANCEL", color = TextSecondary)
                }
            },
            containerColor = DarkCard
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (uiState.isReorderMode) "Edit Cloud Drives" else "Cloud",
                        color = TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu", tint = TextPrimary)
                    }
                },
                actions = {
                    // Pencil button to trigger Edit / Reorder / Delete Mode; Checkmark to finish
                    IconButton(onClick = { viewModel.toggleReorderMode() }) {
                        Icon(
                            imageVector = if (uiState.isReorderMode) Icons.Default.Check else Icons.Default.Edit,
                            contentDescription = if (uiState.isReorderMode) "Done" else "Edit",
                            tint = if (uiState.isReorderMode) TealPrimary else TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        },
        containerColor = DarkBackground
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkBackground)
                .padding(paddingValues)
        ) {
            // Connected Cloud Accounts
            itemsIndexed(uiState.accounts, key = { _, it -> it.id }) { index, account ->
                CloudAccountRow(
                    account = account,
                    isReorderMode = uiState.isReorderMode,
                    canMoveUp = index > 0,
                    canMoveDown = index < uiState.accounts.size - 1,
                    onMoveUp = { viewModel.moveAccountUp(index) },
                    onMoveDown = { viewModel.moveAccountDown(index) },
                    onDelete = { accountToDelete = account },
                    onClick = {
                        if (!uiState.isReorderMode) {
                            onNavigateToCloudBrowser(account.id, account.accountName)
                        }
                    }
                )
                HorizontalDivider(color = Color(0xFF1E1E1E), thickness = 0.5.dp)
            }

            // Row: "+ Add a cloud location" (matching Screenshot 234540)
            item {
                if (!uiState.isReorderMode) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setShowAddDialog(true) }
                            .padding(vertical = 20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add cloud location",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Add a cloud location",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CloudAccountRow(
    account: CloudAccount,
    isReorderMode: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Official Brand Logo / Icon
        CloudProviderIcon(provider = account.provider)

        Spacer(modifier = Modifier.width(16.dp))

        // Account Details
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = account.accountName,
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = account.email,
                color = TextSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Edit Controls (Delete button + Up/Down/Drag handles in Edit mode)
        if (isReorderMode) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Delete Account Button (Red trash icon)
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete account",
                        tint = Color(0xFFEF5350),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                IconButton(
                    onClick = onMoveUp,
                    enabled = canMoveUp,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = "Move Up",
                        tint = if (canMoveUp) TextPrimary else TextSecondary.copy(alpha = 0.3f)
                    )
                }
                IconButton(
                    onClick = onMoveDown,
                    enabled = canMoveDown,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "Move Down",
                        tint = if (canMoveDown) TextPrimary else TextSecondary.copy(alpha = 0.3f)
                    )
                }
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "Drag handle",
                    tint = TealPrimary,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(22.dp)
                )
            }
        }
    }
}

@Composable
fun GoogleDriveLogoIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sx = w / 100f
        val sy = h / 87f

        // 1. Blue Ribbon (Bottom)
        val bluePath = Path().apply {
            moveTo(16.7f * sx, 86.6f * sy)
            lineTo(83.3f * sx, 86.6f * sy)
            lineTo(66.7f * sx, 57.7f * sy)
            lineTo(0f * sx, 57.7f * sy)
            close()
        }
        drawPath(bluePath, color = Color(0xFF4285F4))

        // 2. Yellow Ribbon (Top-Right)
        val yellowPath = Path().apply {
            moveTo(66.7f * sx, 0f * sy)
            lineTo(33.3f * sx, 0f * sy)
            lineTo(66.7f * sx, 57.7f * sy)
            lineTo(100f * sx, 57.7f * sy)
            close()
        }
        drawPath(yellowPath, color = Color(0xFFFFBA00))

        // 3. Green Ribbon (Left)
        val greenPath = Path().apply {
            moveTo(33.3f * sx, 0f * sy)
            lineTo(0f * sx, 57.7f * sy)
            lineTo(16.7f * sx, 86.6f * sy)
            lineTo(50f * sx, 28.9f * sy)
            close()
        }
        drawPath(greenPath, color = Color(0xFF0F9D58))
    }
}

@Composable
fun DropboxLogoIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sx = w / 24f
        val sy = h / 24f
        val color = Color(0xFF0061FF)

        // Top-Left Diamond
        val tl = Path().apply {
            moveTo(6f * sx, 3f * sy)
            lineTo(12f * sx, 7f * sy)
            lineTo(6f * sx, 11f * sy)
            lineTo(0f * sx, 7f * sy)
            close()
        }
        drawPath(tl, color)

        // Top-Right Diamond
        val tr = Path().apply {
            moveTo(18f * sx, 3f * sy)
            lineTo(24f * sx, 7f * sy)
            lineTo(18f * sx, 11f * sy)
            lineTo(12f * sx, 7f * sy)
            close()
        }
        drawPath(tr, color)

        // Mid-Left Diamond
        val ml = Path().apply {
            moveTo(0f * sx, 13f * sy)
            lineTo(6f * sx, 9f * sy)
            lineTo(12f * sx, 13f * sy)
            lineTo(6f * sx, 17f * sy)
            close()
        }
        drawPath(ml, color)

        // Mid-Right Diamond
        val mr = Path().apply {
            moveTo(12f * sx, 13f * sy)
            lineTo(18f * sx, 9f * sy)
            lineTo(24f * sx, 13f * sy)
            lineTo(18f * sx, 17f * sy)
            close()
        }
        drawPath(mr, color)

        // Bottom Flap
        val bottom = Path().apply {
            moveTo(6f * sx, 18f * sy)
            lineTo(12f * sx, 14f * sy)
            lineTo(18f * sx, 18f * sy)
            lineTo(12f * sx, 22f * sy)
            close()
        }
        drawPath(bottom, color)
    }
}

@Composable
fun CloudProviderIcon(provider: CloudProvider, modifier: Modifier = Modifier) {
    when (provider) {
        CloudProvider.GOOGLE_DRIVE -> {
            Box(
                modifier = modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E2024)),
                contentAlignment = Alignment.Center
            ) {
                GoogleDriveLogoIcon(modifier = Modifier.size(26.dp))
            }
        }
        CloudProvider.DROPBOX -> {
            Box(
                modifier = modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E2024)),
                contentAlignment = Alignment.Center
            ) {
                DropboxLogoIcon(modifier = Modifier.size(26.dp))
            }
        }
        CloudProvider.MEGA -> {
            Box(
                modifier = modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFD9272E)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "M",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        else -> {
            Box(
                modifier = modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(DarkCard),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Cloud,
                    contentDescription = "Cloud",
                    tint = TextSecondary,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}
