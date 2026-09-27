package com.antigravity.filemanager.presentation.recent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.antigravity.filemanager.domain.model.FileItem
import com.antigravity.filemanager.domain.model.FileSortOption
import com.antigravity.filemanager.presentation.components.BottomBarActionItem
import com.antigravity.filemanager.presentation.components.DeleteConfirmDialog
import com.antigravity.filemanager.presentation.components.FileListItem
import com.antigravity.filemanager.presentation.components.FileManagerTopBar
import com.antigravity.filemanager.presentation.components.verticalScrollbar
import com.antigravity.filemanager.presentation.theme.DarkBackground
import com.antigravity.filemanager.presentation.theme.DarkCard
import com.antigravity.filemanager.presentation.theme.PastelCoral
import com.antigravity.filemanager.presentation.theme.TealPrimary
import com.antigravity.filemanager.presentation.theme.TextPrimary
import com.antigravity.filemanager.presentation.theme.TextSecondary
import com.antigravity.filemanager.presentation.theme.TextTertiary
import com.antigravity.filemanager.utils.FileOpener
import java.io.File
import java.util.Calendar

/**
 * Recent: files opened in the app (Opened), and files newly added to the device (Added), newest
 * first and grouped by day.
 */
@Composable
fun RecentFilesScreen(
    onNavigateBack: () -> Unit,
    onOpenFile: (FileItem, FileSortOption, String?) -> Unit,
    onOpenFolder: (path: String, title: String) -> Unit,
    viewModel: RecentFilesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }
    androidx.activity.compose.BackHandler(enabled = uiState.isSelectionMode) { viewModel.clearSelection() }

    if (uiState.showClearConfirm) {
        AlertDialog(
            onDismissRequest = { viewModel.setShowClearConfirm(false) },
            title = { Text("Clear history?", color = TextPrimary) },
            text = { Text("Every file is removed from Recent > Opened. The files themselves are not deleted.", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearHistory() }) {
                    Text("CLEAR", color = PastelCoral, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.setShowClearConfirm(false) }) { Text("CANCEL", color = TextSecondary) }
            },
            containerColor = DarkCard
        )
    }
    if (uiState.showDeleteDialog) {
        DeleteConfirmDialog(
            itemCount = uiState.selectedPaths.size,
            onConfirm = { moveToTrash -> viewModel.deleteSelected(moveToTrash) },
            onDismiss = { viewModel.setShowDeleteDialog(false) }
        )
    }

    val visible = uiState.visibleFiles
    Scaffold(
        topBar = {
            if (uiState.isSelectionMode) {
                FileManagerTopBar(
                    title = "${uiState.selectedPaths.size} selected",
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear selection", tint = TextPrimary)
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.selectAll() }) {
                            Icon(Icons.Default.SelectAll, contentDescription = "Select all", tint = TextPrimary)
                        }
                    }
                )
            } else {
                FileManagerTopBar(
                    title = "Recent",
                    showBackButton = true,
                    onNavigationClick = onNavigateBack,
                    actions = {
                        if (uiState.tab == RecentTab.OPENED && uiState.opened.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setShowClearConfirm(true) }) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = "Clear history", tint = Color(0xFFFFAB91))
                            }
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (uiState.isSelectionMode) {
                val selected = uiState.selectedPaths.toList()
                Surface(color = DarkCard, tonalElevation = 8.dp, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        if (selected.size == 1) {
                            BottomBarActionItem(
                                icon = Icons.Default.FolderOpen,
                                label = "Show in folder",
                                tint = TextPrimary,
                                onClick = {
                                    val folder = File(selected.first()).parentFile
                                    viewModel.clearSelection()
                                    if (folder != null) onOpenFolder(folder.absolutePath, folder.name)
                                }
                            )
                        }
                        BottomBarActionItem(
                            icon = Icons.Default.Share,
                            label = "Share",
                            tint = TextPrimary,
                            onClick = { FileOpener.shareFiles(context, selected) }
                        )
                        if (uiState.tab == RecentTab.OPENED) {
                            BottomBarActionItem(
                                icon = Icons.Default.PlaylistRemove,
                                label = "Remove",
                                tint = TextPrimary,
                                onClick = { viewModel.removeSelectedFromHistory() }
                            )
                        }
                        BottomBarActionItem(
                            icon = Icons.Default.Delete,
                            label = "Delete",
                            tint = PastelCoral,
                            onClick = { viewModel.setShowDeleteDialog(true) }
                        )
                    }
                }
            }
        },
        containerColor = DarkBackground
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(
                selectedTabIndex = uiState.tab.ordinal,
                containerColor = DarkBackground,
                contentColor = TealPrimary,
                indicator = { positions ->
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(positions[uiState.tab.ordinal]),
                        color = TealPrimary
                    )
                }
            ) {
                RecentTab.entries.forEach { tab ->
                    Tab(
                        selected = uiState.tab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = {
                            Text(
                                tab.label,
                                color = if (uiState.tab == tab) TealPrimary else TextSecondary,
                                fontWeight = if (uiState.tab == tab) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(RecentTypeFilter.entries) { filter ->
                    FilterChip(
                        selected = uiState.filter == filter,
                        onClick = { viewModel.setFilter(filter) },
                        label = { Text(filter.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = DarkBackground,
                            labelColor = TextSecondary,
                            selectedContainerColor = TealPrimary.copy(alpha = 0.2f),
                            selectedLabelColor = TealPrimary
                        )
                    )
                }
            }

            when {
                uiState.isLoading && visible.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = TealPrimary)
                }
                visible.isEmpty() -> EmptyRecent(uiState.tab, uiState.filter)
                else -> {
                    // A fresh position per tab and filter: each is its own list.
                    val listState = remember(uiState.tab, uiState.filter) { androidx.compose.foundation.lazy.LazyListState() }
                    val groups = remember(visible) { groupByDay(visible) }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().verticalScrollbar(listState)
                    ) {
                        groups.forEach { (label, files) ->
                            item(key = "header_$label") {
                                Text(
                                    text = label,
                                    color = TealPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp)
                                )
                            }
                            items(files, key = { it.path }) { file ->
                                FileListItem(
                                    file = file,
                                    isSelectionMode = uiState.isSelectionMode,
                                    isSelected = file.path in uiState.selectedPaths,
                                    onClick = {
                                        if (uiState.isSelectionMode) viewModel.toggleSelection(file.path)
                                        else onOpenFile(file, FileSortOption.BY_NAME_ASC, null)
                                    },
                                    onLongClick = { viewModel.toggleSelection(file.path) },
                                    // Recent files come from anywhere on the device: say where.
                                    showPath = true
                                )
                                HorizontalDivider(color = Color(0xFF202020), thickness = 0.5.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyRecent(tab: RecentTab, filter: RecentTypeFilter) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Icon(Icons.Default.History, contentDescription = null, tint = TextTertiary, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(12.dp))
            Text(
                text = when {
                    filter != RecentTypeFilter.ALL -> "No ${filter.label.lowercase()} here"
                    tab == RecentTab.OPENED -> "Files you open will show up here"
                    else -> "No new files on this device"
                },
                color = TextSecondary,
                fontSize = 15.sp
            )
        }
    }
}

/** [files] (newest first) split into Today / Yesterday / This week / Older by their date. */
internal fun groupByDay(files: List<FileItem>, now: Long = System.currentTimeMillis()): List<Pair<String, List<FileItem>>> {
    val today = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val day = 24L * 60 * 60 * 1000
    fun labelOf(time: Long) = when {
        time >= today -> "Today"
        time >= today - day -> "Yesterday"
        time >= today - 6 * day -> "This week"
        else -> "Older"
    }
    return files.groupBy { labelOf(it.lastModified) }.toList()
}
