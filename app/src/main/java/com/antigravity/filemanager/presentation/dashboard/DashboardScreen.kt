package com.antigravity.filemanager.presentation.dashboard

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.antigravity.filemanager.domain.model.CategoryType
import com.antigravity.filemanager.presentation.components.CategoryCard
import com.antigravity.filemanager.presentation.components.CloudDownloadProgressDialog
import com.antigravity.filemanager.presentation.components.FileManagerTopBar
import com.antigravity.filemanager.presentation.components.PasteBottomBar
import com.antigravity.filemanager.presentation.components.PullToRefreshWrapper
import com.antigravity.filemanager.presentation.theme.DarkBackground
import com.antigravity.filemanager.presentation.theme.TealPrimary
import com.antigravity.filemanager.presentation.theme.TextPrimary

@Composable
fun DashboardScreen(
    onNavigateToCategory: (CategoryType) -> Unit,
    onNavigateToBrowser: (path: String, title: String) -> Unit,
    onNavigateToTrash: () -> Unit,
    onNavigateToStorageAnalysis: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            FileManagerTopBar(
                title = "File Manager +",
                showBackButton = false,
                onNavigationClick = { /* Open Navigation Drawer */ },
                actions = {
                    IconButton(onClick = { viewModel.setShowBookmarksDialog(true) }) {
                        Icon(
                            imageVector = Icons.Default.Bookmark,
                            contentDescription = "Bookmarks",
                            tint = TextPrimary
                        )
                    }
                }
            )
        },
        bottomBar = {
            if (uiState.clipboardState.hasItems) {
                PasteBottomBar(
                    itemCount = uiState.clipboardState.itemCount,
                    onPaste = { viewModel.pasteToMainStorage() },
                    onCancel = { viewModel.clearClipboard() }
                )
            }
        },
        containerColor = DarkBackground
    ) { paddingValues ->
        PullToRefreshWrapper(
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxSize()
                .background(DarkBackground)
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(
                uiState.categories.filterNot { it.type == CategoryType.CLOUD || it.type == CategoryType.ACCESS_FROM_NETWORK },
                key = { it.type.name }
            ) { summary ->
                CategoryCard(
                    summary = summary,
                    onClick = {
                        when (summary.type) {
                            CategoryType.MAIN_STORAGE -> {
                                val root = Environment.getExternalStorageDirectory().absolutePath
                                onNavigateToBrowser(root, "Main storage")
                            }
                            CategoryType.DOWNLOADS -> {
                                val downloadPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
                                onNavigateToBrowser(downloadPath, "Download")
                            }
                            CategoryType.STORAGE_ANALYSIS -> onNavigateToStorageAnalysis()
                            CategoryType.IMAGES -> onNavigateToCategory(CategoryType.IMAGES)
                            CategoryType.AUDIO -> onNavigateToCategory(CategoryType.AUDIO)
                            CategoryType.VIDEOS -> onNavigateToCategory(CategoryType.VIDEOS)
                            CategoryType.DOCUMENTS -> onNavigateToCategory(CategoryType.DOCUMENTS)
                            CategoryType.CLOUD -> {}
                            CategoryType.ACCESS_FROM_NETWORK -> {}
                            CategoryType.RECYCLE_BIN -> onNavigateToTrash()
                        }
                    }
                )
            }
        }
        }
    }

    if (uiState.overwriteConflicts.isNotEmpty()) {
        com.antigravity.filemanager.presentation.components.OverwriteConflictDialog(
            conflicts = uiState.overwriteConflicts,
            onConfirm = { overwriteNames, skipNames -> viewModel.resolveOverwriteConflict(overwriteNames, skipNames) },
            onCancel = { viewModel.cancelOverwriteConflict() }
        )
    }

    if (uiState.downloadProgress != null) {
        CloudDownloadProgressDialog(
            progress = uiState.downloadProgress!!,
            onCancel = { viewModel.cancelTransfer() }
        )
    }

    if (uiState.showBookmarksDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.setShowBookmarksDialog(false) },
            title = { Text("Bookmarks", fontWeight = FontWeight.Medium) },
            text = {
                if (uiState.bookmarks.isEmpty()) {
                    Text("No bookmarked folders yet")
                } else {
                    LazyColumn {
                        items(uiState.bookmarks, key = { it.path }) { bookmark ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.setShowBookmarksDialog(false)
                                        onNavigateToBrowser(bookmark.path, bookmark.name)
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = TealPrimary
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(bookmark.name, fontWeight = FontWeight.Medium)
                                    Text(bookmark.path, fontSize = 12.sp)
                                }
                                IconButton(onClick = { viewModel.removeBookmark(bookmark.path) }) {
                                    Icon(imageVector = Icons.Default.Delete, contentDescription = "Remove bookmark")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { viewModel.setShowBookmarksDialog(false) }) {
                    Text("Close")
                }
            }
        )
    }
}
