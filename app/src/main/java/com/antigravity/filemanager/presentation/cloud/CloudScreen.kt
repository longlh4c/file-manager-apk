package com.antigravity.filemanager.presentation.cloud

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.Job
import com.antigravity.filemanager.domain.model.CloudAccount
import com.antigravity.filemanager.domain.model.CloudProvider
import com.antigravity.filemanager.presentation.components.AddCloudDialog
import com.antigravity.filemanager.presentation.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CloudScreen(
    onNavigateBack: () -> Unit,
    onNavigateToCloudBrowser: (accountId: String, accountName: String) -> Unit = { _, _ -> },
    onDualPanelToggle: (() -> Unit)? = null,
    isDualPanelActive: Boolean = false,
    onCloseDualPanel: (() -> Unit)? = null,
    viewModel: CloudViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var accountToDelete by remember { mutableStateOf<CloudAccount?>(null) }
    var draggedAccountId by remember { mutableStateOf<String?>(null) }
    var dragJob by remember { mutableStateOf<Job?>(null) }
    val dragOffsetY = remember { Animatable(0f) }
    var rawDragOffset by remember { mutableFloatStateOf(0f) }
    var itemHeightPx by remember { mutableStateOf(0f) }
    val coroutineScope = rememberCoroutineScope()

    BackHandler {
        if (uiState.isReorderMode) {
            viewModel.cancelReorder()
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
            onClearAddAccountError = { viewModel.clearAddAccountError() },
            validateTeraBoxSession = { viewModel.validateTeraBoxSession(it) }
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
                    text = "Are you sure you want to remove \"${accountToDelete?.accountName}\"? This only disconnects the account from Owl File + without deleting your files.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        accountToDelete?.let { viewModel.removeAccount(it.id) }
                        accountToDelete = null
                    }
                ) {
                    Text(
                        text = "Remove",
                        color = Color(0xFFEF5350),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { accountToDelete = null }) {
                    Text(text = "Cancel", color = TextSecondary)
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
                        fontSize = if (isDualPanelActive) 17.sp else 20.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (uiState.isReorderMode) {
                            viewModel.cancelReorder()
                        } else if (onCloseDualPanel != null) {
                            onCloseDualPanel()
                        } else if (onDualPanelToggle != null) {
                            onDualPanelToggle()
                        } else {
                            onNavigateBack()
                        }
                    }) {
                        Icon(
                            imageVector = if (uiState.isReorderMode) {
                                Icons.AutoMirrored.Filled.ArrowBack
                            } else if (onCloseDualPanel != null) {
                                Icons.Default.Close
                            } else {
                                if (isDualPanelActive) Icons.Default.VerticalSplit else Icons.Default.Menu
                            },
                            contentDescription = if (uiState.isReorderMode) "Cancel" else (if (onCloseDualPanel != null) "Close Dual Panel" else (if (isDualPanelActive) "Close Dual Panel" else "Dual Panel")),
                            tint = if (!uiState.isReorderMode && isDualPanelActive && onCloseDualPanel == null) TealPrimary else TextPrimary
                        )
                    }
                },
                actions = {
                    if (uiState.isReorderMode) {
                        // Checkmark button: Confirm & Save new order to database
                        IconButton(onClick = { viewModel.confirmReorder() }) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Confirm",
                                tint = TealPrimary
                            )
                        }
                    } else {
                        // Pencil button: Enter reorder mode
                        IconButton(onClick = { viewModel.enterReorderMode() }) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit",
                                tint = TextPrimary
                            )
                        }
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
                val isDragging = draggedAccountId == account.id
                val elevation by animateDpAsState(
                    targetValue = if (isDragging) 8.dp else 0.dp,
                    animationSpec = tween(durationMillis = 150),
                    label = "dragElevation"
                )
                val scale by animateFloatAsState(
                    targetValue = if (isDragging) 1.02f else 1.0f,
                    animationSpec = tween(durationMillis = 150),
                    label = "dragScale"
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            // The dragged item must NOT have animateItemPlacement, otherwise
                            // its spring animation and the touch drag translation fight each other,
                            // causing violent jitter. Non-dragged items smoothly glide out of the way!
                            if (isDragging) Modifier
                            else Modifier.animateItemPlacement(
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            )
                        )
                        .zIndex(if (isDragging) 10f else 0f)
                        .graphicsLayer {
                            translationY = if (isDragging) dragOffsetY.value else 0f
                            scaleX = scale
                            scaleY = scale
                            shadowElevation = elevation.toPx()
                        }
                        .background(
                            if (isDragging) DarkCardSecondary else Color.Transparent,
                            RoundedCornerShape(8.dp)
                        )
                        .onGloballyPositioned { coordinates ->
                            if (coordinates.size.height > 0) {
                                itemHeightPx = coordinates.size.height.toFloat()
                            }
                        }
                ) {
                    CloudAccountRow(
                        account = account,
                        isReorderMode = uiState.isReorderMode,
                        isDragging = isDragging,
                        canMoveUp = index > 0,
                        canMoveDown = index < uiState.accounts.size - 1,
                        onMoveUp = { viewModel.moveAccountUp(index) },
                        onMoveDown = { viewModel.moveAccountDown(index) },
                        onDragStart = {
                            dragJob?.cancel()
                            rawDragOffset = 0f
                            draggedAccountId = account.id
                            dragJob = coroutineScope.launch { dragOffsetY.snapTo(0f) }
                        },
                        onDrag = { dragAmount ->
                            if (draggedAccountId != account.id) return@CloudAccountRow
                            val currentIdx = uiState.accounts.indexOfFirst { it.id == account.id }
                            if (currentIdx == -1) return@CloudAccountRow
                            rawDragOffset += dragAmount
                            val height = if (itemHeightPx > 0f) itemHeightPx else 180f
                            val threshold = height * 0.45f

                            if (rawDragOffset > threshold && currentIdx < uiState.accounts.size - 1) {
                                viewModel.moveAccountDown(currentIdx)
                                rawDragOffset -= height
                            } else if (rawDragOffset < -threshold && currentIdx > 0) {
                                viewModel.moveAccountUp(currentIdx)
                                rawDragOffset += height
                            }
                            dragJob?.cancel()
                            dragJob = coroutineScope.launch {
                                dragOffsetY.snapTo(rawDragOffset)
                            }
                        },
                        onDragEnd = {
                            if (draggedAccountId == account.id) {
                                dragJob?.cancel()
                                dragJob = coroutineScope.launch {
                                    dragOffsetY.animateTo(
                                        0f,
                                        spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessMedium
                                        )
                                    )
                                    draggedAccountId = null
                                    rawDragOffset = 0f
                                }
                            }
                        },
                        onDelete = { accountToDelete = account },
                        onClick = {
                            if (!uiState.isReorderMode) {
                                onNavigateToCloudBrowser(account.id, account.accountName)
                            }
                        }
                    )
                }
                if (index < uiState.accounts.size - 1) {
                    HorizontalDivider(
                        color = if (isDragging) Color.Transparent else DividerDark,
                        thickness = 0.5.dp
                    )
                }
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
    isDragging: Boolean = false,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDragStart: () -> Unit = {},
    onDrag: (Float) -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDelete: () -> Unit,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isReorderMode) { onClick() }
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
                        tint = PastelCoral,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(2.dp))

                IconButton(
                    onClick = onMoveUp,
                    enabled = canMoveUp,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = "Move Up",
                        tint = if (canMoveUp) Color(0xFF90CAF9) else TextSecondary.copy(alpha = 0.25f)
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
                        tint = if (canMoveDown) Color(0xFF90CAF9) else TextSecondary.copy(alpha = 0.25f)
                    )
                }

                // Drag Handle (ONLY DRAG TO REORDER, NO CLICK, NO POPUP MENU)
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .pointerInput(account.id) {
                            detectVerticalDragGestures(
                                onDragStart = { onDragStart() },
                                onDragEnd = { onDragEnd() },
                                onDragCancel = { onDragEnd() },
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    onDrag(dragAmount)
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "Drag to reorder",
                        tint = if (isDragging) TealAccent else TealPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
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
fun CloudProviderIcon(
    provider: CloudProvider,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp
) {
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        when (provider) {
            CloudProvider.GOOGLE_DRIVE -> {
                GoogleDriveLogoIcon(modifier = Modifier.fillMaxSize(0.85f))
            }
            CloudProvider.DROPBOX -> {
                DropboxLogoIcon(modifier = Modifier.fillMaxSize())
            }
            CloudProvider.MEGA -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(Color(0xFFD9272E)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "M",
                        color = Color.White,
                        fontSize = (size.value * 0.5f).sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            CloudProvider.TERABOX -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(Color(0xFF0084FF)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "T",
                        color = Color.White,
                        fontSize = (size.value * 0.5f).sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            else -> {
                Icon(
                    imageVector = Icons.Default.Cloud,
                    contentDescription = "Cloud",
                    tint = TealPrimary,
                    modifier = Modifier.fillMaxSize(0.85f)
                )
            }
        }
    }
}

