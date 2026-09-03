package com.antigravity.filemanager.presentation.analyzer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.antigravity.filemanager.domain.model.DuplicateFileEntry
import com.antigravity.filemanager.domain.model.DuplicateGroup
import com.antigravity.filemanager.domain.model.FileItem
import com.antigravity.filemanager.presentation.components.DeleteConfirmDialog
import com.antigravity.filemanager.presentation.components.FileManagerTopBar
import com.antigravity.filemanager.presentation.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicateFilesScreen(
    onNavigateBack: () -> Unit,
    viewModel: StorageAnalysisViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val title = "Duplicate files"
    val groups = uiState.data.duplicateFileGroups

    var selectedPaths by remember { mutableStateOf(setOf<String>()) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    BackHandler {
        if (selectedPaths.isNotEmpty()) selectedPaths = emptySet() else onNavigateBack()
    }

    if (showDeleteDialog) {
        DeleteConfirmDialog(
            itemCount = selectedPaths.size,
            onConfirm = {
                viewModel.deleteDuplicates(selectedPaths.toList()) { selectedPaths = emptySet() }
                showDeleteDialog = false
            },
            onDismiss = { showDeleteDialog = false }
        )
    }

    Scaffold(
        topBar = {
            if (selectedPaths.isNotEmpty()) {
                TopAppBar(
                    title = {
                        Text(text = "${selectedPaths.size} selected", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                    },
                    navigationIcon = {
                        IconButton(onClick = { selectedPaths = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                    },
                    actions = {
                        // Duplicate finders conventionally default the selection to "everything
                        // except the earliest copy of each group" — this is that in one tap,
                        // rather than making the user hand-check every non-original row.
                        IconButton(onClick = {
                            selectedPaths = groups.flatMap { g -> g.items.filterNot { it.isOriginal } }.map { it.path }.toSet()
                        }) {
                            Icon(Icons.Default.DoneAll, contentDescription = "Select all duplicates (keep originals)", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF004D40))
                )
            } else {
                FileManagerTopBar(
                    title = title,
                    showBackButton = true,
                    onNavigationClick = onNavigateBack,
                    onMenuClick = { /* Menu */ }
                )
            }
        },
        bottomBar = {
            if (selectedPaths.isNotEmpty()) {
                Surface(
                    color = DarkCard,
                    tonalElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 6.dp, top = 2.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Row(
                            modifier = Modifier.clickable { showDeleteDialog = true },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFEF5350))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Delete ${selectedPaths.size} file(s)", color = Color(0xFFEF5350), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        },
        containerColor = DarkBackground
    ) { paddingValues ->
        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = TealPrimary)
            }
        } else if (groups.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                Text("No duplicates found", color = TextSecondary, fontSize = 15.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().background(DarkBackground).padding(paddingValues),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                groups.forEach { group ->
                    item(key = "header_${group.key}") {
                        Text(
                            text = group.items.firstOrNull { it.isOriginal }?.name ?: group.items.first().name,
                            color = TealPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(group.items, key = { it.path }) { entry ->
                        val isSelected = selectedPaths.contains(entry.path)
                        DuplicateEntryRow(
                            entry = entry,
                            isSelected = isSelected,
                            onClick = {
                                selectedPaths = if (isSelected) selectedPaths - entry.path else selectedPaths + entry.path
                            }
                        )
                        HorizontalDivider(color = Color(0xFF202020), thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DuplicateEntryRow(
    entry: DuplicateFileEntry,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val rowBg = if (isSelected) Color(0xFF00695C).copy(alpha = 0.6f) else Color.Transparent

    Row(
        modifier = Modifier.fillMaxWidth().background(rowBg).clickable { onClick() }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(46.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF232B32)),
            contentAlignment = Alignment.Center
        ) {
            val ext = entry.name.substringAfterLast('.', "").lowercase()
            val imgExts = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic")
            val videoExts = setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "3gp")
            if (ext in imgExts || ext in videoExts) {
                coil.compose.AsyncImage(
                    model = entry.path,
                    contentDescription = entry.name,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(26.dp))
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.name,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (entry.isOriginal) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(TealPrimary.copy(alpha = 0.2f)).padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("Original", color = TealPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = entry.relativeDir, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.height(2.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = entry.formattedSize, color = TextSecondary, fontSize = 13.sp)
                Text(text = entry.formattedDate, color = TextSecondary, fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Checkbox(
            checked = isSelected,
            onCheckedChange = { onClick() },
            colors = CheckboxDefaults.colors(checkedColor = TealPrimary, uncheckedColor = TextSecondary, checkmarkColor = PureBlack)
        )
    }
}
