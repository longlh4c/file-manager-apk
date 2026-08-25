package com.antigravity.filemanager.presentation.trash

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.antigravity.filemanager.presentation.components.FileManagerTopBar
import com.antigravity.filemanager.presentation.theme.*

@Composable
fun RecycleBinScreen(
    onNavigateBack: () -> Unit,
    viewModel: RecycleBinViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    if (uiState.showEmptyConfirm) {
        AlertDialog(
            onDismissRequest = { viewModel.setShowEmptyConfirm(false) },
            title = { Text(text = "Empty Recycle Bin", color = TextPrimary) },
            text = { Text(text = "All items in the Recycle Bin will be permanently deleted. This action cannot be undone.", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = { viewModel.emptyTrash() }) {
                    Text(text = "EMPTY TRASH", color = Color(0xFFEF5350))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.setShowEmptyConfirm(false) }) {
                    Text(text = "CANCEL", color = TextSecondary)
                }
            },
            containerColor = DarkCard
        )
    }

    Scaffold(
        topBar = {
            FileManagerTopBar(
                title = "Recycle Bin (${uiState.formattedTotalSize})",
                showBackButton = true,
                onNavigationClick = onNavigateBack,
                actions = {
                    if (uiState.items.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setShowEmptyConfirm(true) }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Empty Trash", tint = Color(0xFFEF5350))
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (uiState.selectedIds.isNotEmpty()) {
                Surface(
                    color = DarkCard,
                    tonalElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { viewModel.restoreSelected() },
                            colors = ButtonDefaults.buttonColors(containerColor = TealPrimary)
                        ) {
                            Icon(Icons.Default.Restore, contentDescription = null, tint = PureBlack)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("RESTORE (${uiState.selectedIds.size})", color = PureBlack)
                        }
                        Button(
                            onClick = { viewModel.deleteSelectedPermanently() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350))
                        ) {
                            Icon(Icons.Default.DeleteForever, contentDescription = null, tint = TextPrimary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("DELETE", color = TextPrimary)
                        }
                    }
                }
            }
        },
        containerColor = DarkBackground
    ) { paddingValues ->
        if (uiState.items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = "Recycle Bin is empty", color = TextSecondary, fontSize = 16.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DarkBackground)
                    .padding(paddingValues)
            ) {
                items(uiState.items, key = { it.id }) { item ->
                    val isSelected = uiState.selectedIds.contains(item.id)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.toggleItemSelection(item.id) }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { viewModel.toggleItemSelection(item.id) },
                            colors = CheckboxDefaults.colors(checkedColor = TealPrimary, checkmarkColor = PureBlack)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.fileName,
                                color = TextPrimary,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Original: ${item.originalPath} • ${item.formattedSize}",
                                color = TextSecondary,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    HorizontalDivider(color = Color(0xFF202020), thickness = 0.5.dp)
                }
            }
        }
    }
}
