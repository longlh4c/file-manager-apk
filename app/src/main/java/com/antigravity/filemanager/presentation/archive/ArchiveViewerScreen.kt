package com.antigravity.filemanager.presentation.archive

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.antigravity.filemanager.domain.model.FileItem
import com.antigravity.filemanager.domain.model.FileSortOption
import com.antigravity.filemanager.presentation.components.ArchivePasswordDialog
import com.antigravity.filemanager.presentation.components.FileListItem
import com.antigravity.filemanager.presentation.theme.DarkBackground
import com.antigravity.filemanager.presentation.theme.DarkCard
import com.antigravity.filemanager.presentation.theme.SelectionTopBarBg
import com.antigravity.filemanager.presentation.theme.TealPrimary
import com.antigravity.filemanager.presentation.theme.TextPrimary
import com.antigravity.filemanager.presentation.theme.TextSecondary
import com.antigravity.filemanager.utils.FileOpener

/** Browse a zip/7z/rar without extracting it; see [ArchiveViewerViewModel]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveViewerScreen(
    onNavigateBack: () -> Unit,
    onOpenFile: (FileItem, FileSortOption, String?) -> Unit,
    viewModel: ArchiveViewerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    BackHandler {
        when {
            uiState.isSelectionMode -> viewModel.clearSelection()
            !viewModel.navigateUp() -> onNavigateBack()
        }
    }

    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    LaunchedEffect(uiState.fileToOpen) {
        uiState.fileToOpen?.let {
            viewModel.onFileOpened()
            onOpenFile(it, FileSortOption.BY_NAME_ASC, null)
        }
    }

    if (uiState.needsPassword) {
        ArchivePasswordDialog(
            archivePath = uiState.archivePath,
            errorMessage = uiState.passwordError,
            onConfirm = { viewModel.submitPassword(it) },
            onDismiss = onNavigateBack
        )
    }

    uiState.workingLabel?.let { label ->
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = TealPrimary, strokeWidth = 3.dp)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("$label…", color = TextPrimary)
                }
            },
            containerColor = DarkCard
        )
    }

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            if (uiState.isSelectionMode) {
                TopAppBar(
                    title = { Text("${uiState.selectedPaths.size} selected", color = TextPrimary) },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear selection", tint = TextPrimary)
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.selectAll() }) {
                            Icon(Icons.Default.SelectAll, contentDescription = "Select all", tint = TextPrimary)
                        }
                        IconButton(onClick = { viewModel.extract(all = false) }) {
                            Icon(Icons.Default.Unarchive, contentDescription = "Extract selected", tint = TealPrimary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = SelectionTopBarBg)
                )
            } else {
                TopAppBar(
                    title = {
                        Text(uiState.archiveName, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { viewModel.extract(all = true) },
                            enabled = !uiState.isLoading && uiState.error == null
                        ) {
                            Icon(Icons.Default.Unarchive, contentDescription = "Extract all", tint = TealPrimary)
                        }
                        IconButton(onClick = {
                            FileOpener.openWith(context, FileItem(id = uiState.archivePath, name = uiState.archiveName, path = uiState.archivePath))
                        }) {
                            Icon(Icons.Default.OpenInNew, contentDescription = "Open with another app", tint = TextPrimary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ArchiveBreadcrumbs(
                archiveName = uiState.archiveName,
                currentDir = uiState.currentDir,
                onNavigate = { viewModel.goTo(it) }
            )
            when {
                uiState.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = TealPrimary)
                }
                uiState.error != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(uiState.error!!, color = TextSecondary, textAlign = TextAlign.Center)
                }
                uiState.items.isEmpty() && !uiState.needsPassword -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("This folder is empty", color = TextSecondary)
                }
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(uiState.items, key = { it.path }) { item ->
                        FileListItem(
                            file = item,
                            isSelectionMode = uiState.isSelectionMode,
                            isSelected = item.path in uiState.selectedPaths,
                            onClick = { viewModel.onItemClick(item) },
                            onLongClick = { viewModel.toggleSelection(item) }
                        )
                    }
                }
            }
        }
    }
}

/** "archive.zip › docs › deep", each segment tappable to jump back to it. */
@Composable
private fun ArchiveBreadcrumbs(archiveName: String, currentDir: String, onNavigate: (String) -> Unit) {
    val segments = if (currentDir.isEmpty()) emptyList() else currentDir.split('/')
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Crumb(archiveName, isCurrent = segments.isEmpty()) { onNavigate("") }
        segments.forEachIndexed { index, name ->
            Text("›", color = TextSecondary, fontSize = 14.sp)
            val target = segments.take(index + 1).joinToString("/")
            Crumb(name, isCurrent = index == segments.lastIndex) { onNavigate(target) }
        }
    }
}

@Composable
private fun Crumb(label: String, isCurrent: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (isCurrent) TextPrimary else TealPrimary,
        fontSize = 14.sp,
        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        modifier = Modifier
            .clickable(enabled = !isCurrent, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp)
    )
}
