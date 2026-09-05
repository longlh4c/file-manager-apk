package com.antigravity.filemanager.presentation.browser

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.antigravity.filemanager.domain.model.CategoryType
import com.antigravity.filemanager.domain.model.FileItem
import com.antigravity.filemanager.domain.model.FileSortOption
import com.antigravity.filemanager.presentation.components.*
import com.antigravity.filemanager.presentation.theme.DarkBackground
import com.antigravity.filemanager.presentation.theme.DarkCard
import com.antigravity.filemanager.presentation.theme.TealPrimary
import com.antigravity.filemanager.presentation.theme.TextPrimary
import com.antigravity.filemanager.presentation.theme.TextSecondary
import com.antigravity.filemanager.utils.FileOpener
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    onNavigateBack: () -> Unit,
    onOpenFile: (FileItem, FileSortOption, String?) -> Unit = { _, _, _ -> },
    onNavigateToStorageAnalysis: () -> Unit = {},
    viewModel: FileBrowserViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showMoreMenu by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    val viewMode = uiState.viewMode

    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(uiState.isSearchActive) {
        if (uiState.isSearchActive) {
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    // Handle back button
    BackHandler {
        if (uiState.isSelectionMode) {
            viewModel.clearSelection()
        } else if (uiState.isSearchActive) {
            viewModel.setSearchActive(false)
        } else {
            val rootPath = android.os.Environment.getExternalStorageDirectory().absolutePath
            val boundary = uiState.rootBoundaryPath.ifEmpty { rootPath }
            if (uiState.currentPath != boundary && uiState.currentPath.isNotEmpty()) {
                val parent = File(uiState.currentPath).parent
                if (parent != null && parent.startsWith(boundary)) {
                    viewModel.loadDirectory(parent)
                } else {
                    onNavigateBack()
                }
            } else {
                onNavigateBack()
            }
        }
    }


    // The combined View/Sort/Show-hidden-files bottom sheet was replaced by dedicated top-bar
    // Sort and View buttons (View just toggles List↔Grid directly, no menu) — see the actions
    // block below. Only "Sort by" still needs its own dialog; "Show hidden files" moved into the
    // small top-bar More menu next to them.
    if (showSortDialog) {
        SortByDialog(
            currentSort = uiState.sortOption,
            onSortChange = { sort, applyToAll -> viewModel.onSortChanged(sort, applyToAll) },
            onDismiss = { showSortDialog = false }
        )
    }

    // Dialogs
    if (uiState.showCloudDestinationDialog) {
        SelectCloudDestinationDialog(
            isMoveOperation = uiState.isCloudMoveOperation,
            itemCount = uiState.selectedPaths.size,
            accounts = uiState.cloudAccounts,
            onSelectLocal = { viewModel.onSelectLocalDestination() },
            onSelectAccount = { account ->
                viewModel.onSelectCloudAccountForTransfer(account)
            },
            onDismiss = { viewModel.dismissCloudDestinationDialog() }
        )
    }

    if (uiState.cloudFolderPickerAccount != null) {
        CloudFolderPickerDialog(
            accountName = uiState.cloudFolderPickerAccount?.accountName ?: "",
            segments = uiState.cloudFolderPickerSegments,
            folders = uiState.cloudFolderPickerFolders,
            isLoading = uiState.cloudFolderPickerLoading,
            onFolderClick = { viewModel.openCloudFolderPickerFolder(it) },
            onSegmentClick = { viewModel.navigateCloudFolderPickerToSegment(it) },
            onConfirm = { viewModel.confirmCloudFolderPickerDestination() },
            onDismiss = { viewModel.dismissCloudFolderPicker() }
        )
    }

    if (uiState.showLocalFolderPicker) {
        CloudFolderPickerDialog(
            accountName = "This device",
            segments = uiState.localFolderPickerSegments,
            folders = uiState.localFolderPickerFolders,
            isLoading = uiState.localFolderPickerLoading,
            onFolderClick = { viewModel.openLocalFolderPickerFolder(it) },
            onSegmentClick = { viewModel.navigateLocalFolderPickerToSegment(it) },
            onConfirm = { viewModel.confirmLocalFolderPickerDestination() },
            onDismiss = { viewModel.dismissLocalFolderPicker() }
        )
    }

    if (uiState.showNewFolderDialog) {
        TextInputDialog(
            title = "New Folder",
            confirmButtonText = "CREATE",
            onConfirm = { viewModel.createFolder(it) },
            onDismiss = { viewModel.setShowNewFolderDialog(false) }
        )
    }

    if (uiState.overwriteConflicts.isNotEmpty()) {
        OverwriteConflictDialog(
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

    if (uiState.bookmarkConfirmationMessage != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.dismissBookmarkConfirmation() },
            title = { Text("Bookmark Added") },
            text = { Text(uiState.bookmarkConfirmationMessage ?: "") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { viewModel.dismissBookmarkConfirmation() }) {
                    Text("OK")
                }
            }
        )
    }

    if (uiState.showRenameDialog && uiState.itemToRename != null) {
        TextInputDialog(
            title = "Rename",
            initialValue = uiState.itemToRename?.name ?: "",
            confirmButtonText = "RENAME",
            onConfirm = { viewModel.renameItem(it) },
            onDismiss = { viewModel.setShowRenameDialog(null) }
        )
    }

    if (uiState.showCompressDialog) {
        TextInputDialog(
            title = "Compress to Zip",
            initialValue = "Archive.zip",
            confirmButtonText = "COMPRESS",
            onConfirm = { viewModel.zipSelected(it) },
            onDismiss = { viewModel.setShowCompressDialog(false) }
        )
    }

    if (uiState.pendingOverwriteZipPath != null) {
        OverwriteFileConfirmDialog(
            fileName = File(uiState.pendingOverwriteZipPath!!).name,
            onConfirm = { viewModel.confirmCompressOverwrite() },
            onDismiss = { viewModel.cancelCompressOverwrite() }
        )
    }

    if (uiState.showDeleteDialog) {
        DeleteConfirmDialog(
            itemCount = uiState.selectedPaths.size,
            onConfirm = { moveToTrash ->
                viewModel.deleteSelected(moveToTrash)
            },
            onDismiss = { viewModel.setShowDeleteDialog(false) }
        )
    }

    if (uiState.showPropertiesDialog) {
        SelectionPropertiesDialog(
            items = uiState.propertiesItems,
            totalSize = uiState.propertiesTotalSize,
            isComputing = uiState.propertiesIsComputing,
            onDismiss = { viewModel.dismissPropertiesDialog() }
        )
    }

    val rootPath = android.os.Environment.getExternalStorageDirectory().absolutePath
    val boundary = uiState.rootBoundaryPath.ifEmpty { rootPath }
    val relativePath = uiState.currentPath.removePrefix(boundary).trimStart('/')
    val pathSegments = if (relativePath.isEmpty()) emptyList() else relativePath.split('/')

    // Search results come recursively from the ViewModel (this folder + every subfolder), not a
    // plain filter of the current listing — see FileBrowserViewModel.onSearchQueryChanged.
    val filteredFiles = if (uiState.searchQuery.isBlank()) uiState.files else uiState.searchResults

    Scaffold(
        topBar = {
            if (uiState.isSelectionMode) {
                // Top Selection Bar with Count
                TopAppBar(
                    title = {
                        Text(
                            text = "${uiState.selectedPaths.size}/${filteredFiles.size}",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.selectAll() }) {
                            Icon(Icons.Default.GridView, contentDescription = "Select All", tint = Color.White)
                        }
                        IconButton(onClick = { viewModel.invertSelection() }) {
                            Icon(Icons.Default.SwapHoriz, contentDescription = "Invert Selection", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF004D40)
                    )
                )
            } else if (uiState.isSearchActive) {
                // Interactive Search Bar
                TopAppBar(
                    title = {
                        TextField(
                            value = uiState.searchQuery,
                            onValueChange = { viewModel.onSearchQueryChanged(it) },
                            placeholder = { Text("Search files & folders...", color = TextSecondary) },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocusRequester)
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.setSearchActive(false) }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                        }
                    },
                    actions = {
                        if (uiState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextPrimary)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
                )
            } else {
                // Top Bar with 3 action buttons on top right (Search, New Folder, Sort/More)
                FileManagerTopBar(
                    title = uiState.title,
                    showBackButton = true,
                    onNavigationClick = {
                        if (uiState.currentPath != boundary && uiState.currentPath.isNotEmpty()) {
                            val parent = File(uiState.currentPath).parent
                            if (parent != null && parent.startsWith(boundary)) {
                                viewModel.loadDirectory(parent)
                            } else {
                                onNavigateBack()
                            }
                        } else {
                            onNavigateBack()
                        }
                    },
                    actions = {

                        // 1. Search Button
                        IconButton(onClick = { viewModel.setSearchActive(true) }) {
                            Icon(Icons.Default.Search, contentDescription = "Search", tint = TextPrimary)
                        }

                        // 2. New Folder Button
                        IconButton(onClick = { viewModel.setShowNewFolderDialog(true) }) {
                            Icon(Icons.Default.CreateNewFolder, contentDescription = "New Folder", tint = TextPrimary)
                        }

                        // 3-4. Sort + View buttons (shared with MediaCategoriesScreen).
                        SortAndViewTopBarActions(
                            viewMode = viewMode,
                            onSortClick = { showSortDialog = true },
                            onViewModeChange = { mode, applyToAll -> viewModel.onViewModeChanged(mode, applyToAll) }
                        )

                    }
                )
            }
        },
        bottomBar = {
            if (uiState.isSelectionMode) {
                // Bottom Action Bar with perfectly formatted action items (Zero text clipping)
                Surface(
                    color = DarkCard,
                    tonalElevation = 8.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. Copy
                        BottomBarActionItem(
                            icon = Icons.Default.ContentCopy,
                            label = "Copy",
                            tint = TextPrimary,
                            onClick = { viewModel.copySelected() },
                            modifier = Modifier.weight(1f)
                        )

                        // 2. Move
                        BottomBarActionItem(
                            icon = Icons.Default.DriveFileMove,
                            label = "Move",
                            tint = TextPrimary,
                            onClick = { viewModel.cutSelected() },
                            modifier = Modifier.weight(1f)
                        )

                        // 3. Rename (if 1 item selected)
                        if (uiState.selectedPaths.size == 1) {
                            BottomBarActionItem(
                                icon = Icons.Default.Edit,
                                label = "Rename",
                                tint = TextPrimary,
                                onClick = {
                                    val firstPath = uiState.selectedPaths.firstOrNull()
                                    val item = filteredFiles.find { it.path == firstPath }
                                    viewModel.setShowRenameDialog(item)
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // 4. Delete
                        BottomBarActionItem(
                            icon = Icons.Default.Delete,
                            label = "Delete",
                            tint = Color(0xFFEF5350),
                            onClick = { viewModel.setShowDeleteDialog(true) },
                            modifier = Modifier.weight(1f)
                        )

                        // 5. More (with Copy to cloud & Move to cloud)
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            BottomBarActionItem(
                                icon = Icons.Default.MoreVert,
                                label = "More",
                                tint = TextPrimary,
                                onClick = { showMoreMenu = true }
                            )

                            DropdownMenu(
                                expanded = showMoreMenu,
                                onDismissRequest = { showMoreMenu = false },
                                modifier = Modifier.background(DarkCard)
                            ) {
                                // Copy to
                                DropdownMenuItem(
                                    text = { Text("Copy to", color = TextPrimary) },
                                    leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, tint = TealPrimary) },
                                    onClick = {
                                        showMoreMenu = false
                                        viewModel.openCopyToCloudDialog(isMove = false)
                                    }
                                )
                                // Move to
                                DropdownMenuItem(
                                    text = { Text("Move to", color = TextPrimary) },
                                    leadingIcon = { Icon(Icons.Default.DriveFileMove, contentDescription = null, tint = TealPrimary) },
                                    onClick = {
                                        showMoreMenu = false
                                        viewModel.openCopyToCloudDialog(isMove = true)
                                    }
                                )
                                // Add/Remove Bookmark (only when exactly one folder is selected)
                                run {
                                    val firstPath = uiState.selectedPaths.firstOrNull()
                                    val selectedItem = filteredFiles.find { it.path == firstPath }
                                    if (uiState.selectedPaths.size == 1 && selectedItem?.isDirectory == true) {
                                        val isBookmarked = uiState.bookmarks.any { it.path == selectedItem.path }
                                        DropdownMenuItem(
                                            text = { Text(if (isBookmarked) "Remove Bookmark" else "Add Bookmark", color = TextPrimary) },
                                            leadingIcon = {
                                                Icon(
                                                    if (isBookmarked) Icons.Default.BookmarkRemove else Icons.Default.Bookmark,
                                                    contentDescription = null,
                                                    tint = TealPrimary
                                                )
                                            },
                                            onClick = {
                                                showMoreMenu = false
                                                if (isBookmarked) {
                                                    viewModel.removeBookmark(selectedItem.path)
                                                } else {
                                                    viewModel.addBookmark(selectedItem.path, selectedItem.name)
                                                }
                                            }
                                        )
                                    }
                                }
                                HorizontalDivider(color = Color(0xFF2E2E2E), thickness = 0.5.dp)
                                // Share
                                DropdownMenuItem(
                                    text = { Text("Share", color = TextPrimary) },
                                    leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, tint = TextPrimary) },
                                    onClick = {
                                        showMoreMenu = false
                                        FileOpener.shareFiles(context, uiState.selectedPaths.toList())
                                    }
                                )
                                val hasArchive = uiState.selectedPaths.any { path ->
                                    val ext = path.substringAfterLast('.', "").lowercase()
                                    ext in setOf("zip", "rar", "7z", "tar", "gz")
                                }
                                if (hasArchive) {
                                    DropdownMenuItem(
                                        text = { Text("Extract here", color = TextPrimary) },
                                        leadingIcon = { Icon(Icons.Default.Unarchive, contentDescription = null, tint = TealPrimary) },
                                        onClick = {
                                            showMoreMenu = false
                                            viewModel.extractSelected()
                                        }
                                    )
                                }
                                // Compress
                                DropdownMenuItem(
                                    text = { Text("Compress", color = TextPrimary) },
                                    leadingIcon = { Icon(Icons.Default.FolderZip, contentDescription = null, tint = TextPrimary) },
                                    onClick = {
                                        showMoreMenu = false
                                        viewModel.setShowCompressDialog(true)
                                    }
                                )
                                // Open with
                                DropdownMenuItem(
                                    text = { Text("Open with", color = TextPrimary) },
                                    leadingIcon = { Icon(Icons.Default.OpenInBrowser, contentDescription = null, tint = TextPrimary) },
                                    onClick = {
                                        showMoreMenu = false
                                        val firstPath = uiState.selectedPaths.firstOrNull()
                                        val file = filteredFiles.find { it.path == firstPath }
                                        if (file != null) FileOpener.openWith(context, file)
                                    }
                                )
                                // Properties — was only ever looking up the FIRST selected path,
                                // silently ignoring the rest of a multi-selection.
                                DropdownMenuItem(
                                    text = { Text("Properties", color = TextPrimary) },
                                    leadingIcon = { Icon(Icons.Default.Info, contentDescription = null, tint = TextPrimary) },
                                    onClick = {
                                        showMoreMenu = false
                                        val selectedPaths = uiState.selectedPaths
                                        val items = filteredFiles.filter { it.path in selectedPaths }
                                        viewModel.showPropertiesForSelection(items)
                                    }
                                )
                            }
                        }
                    }
                }
            } else if (uiState.clipboardPaths.isNotEmpty() && uiState.downloadProgress == null) {
                // See the matching comment in CloudExplorerScreen — clipboard only clears at the
                // very end of paste(), so this stayed visible alongside the progress bar for the
                // whole transfer without this check.
                PasteBottomBar(
                    itemCount = uiState.clipboardPaths.size,
                    onPaste = { viewModel.paste() },
                    onCancel = { viewModel.clearClipboard() }
                )
            }
        },
        containerColor = DarkBackground
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkBackground)
                .padding(paddingValues)
        ) {
            BreadcrumbBar(
                categoryType = uiState.categoryType,
                pathSegments = pathSegments,
                // Only Main storage links to Storage Analysis from here — Downloads is a
                // single-purpose folder view, and the "X% USED" badge/button there pointed at
                // the same whole-device analysis screen regardless, which didn't belong.
                storageUsedBadge = if (uiState.categoryType == com.antigravity.filemanager.domain.model.CategoryType.MAIN_STORAGE) {
                    uiState.storageUsedPercent?.let { "$it% USED" }
                } else null,
                onHomeClick = onNavigateBack,
                onCategoryClick = { viewModel.loadDirectory(boundary) },
                onSegmentClick = { index ->
                    val targetSegments = pathSegments.take(index + 1)
                    val targetPath = "$boundary/${targetSegments.joinToString("/")}"
                    viewModel.loadDirectory(targetPath)
                },
                onStorageBadgeClick = onNavigateToStorageAnalysis
            )


            // Switchable View Mode: LIST, GRID, DETAILED
            PullToRefreshWrapper(
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.fillMaxSize()
            ) {
            if (!uiState.isLoading && !uiState.isSearching && filteredFiles.isEmpty()) {
                EmptyFolderState()
            } else {
            when (viewMode) {
                ViewMode.GRID -> {
                    LazyVerticalGrid(
                        // Adaptive instead of a hardcoded column count: the number of columns
                        // now comes from how many 100dp-min cells actually fit the screen width,
                        // so a tablet gets more columns (smaller relative thumbnails) and a
                        // narrow phone gets fewer, rather than the same fixed count stretched or
                        // squeezed on every device.
                        columns = GridCells.Adaptive(minSize = 100.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filteredFiles, key = { it.path }) { file ->
                            FileGridCard(
                                file = file,
                                isSelectionMode = uiState.isSelectionMode,
                                isSelected = uiState.selectedPaths.contains(file.path),
                                onClick = {
                                    if (uiState.isSelectionMode) {
                                        viewModel.toggleFileSelection(file.path)
                                    } else {
                                        if (file.isDirectory) {
                                            viewModel.loadDirectory(file.path)
                                        } else {
                                            onOpenFile(file, uiState.sortOption, null)
                                        }
                                    }
                                },
                                onLongClick = {
                                    viewModel.toggleFileSelection(file.path)
                                }
                            )
                        }
                    }
                }
                ViewMode.DETAILED_LIST -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(filteredFiles, key = { it.path }) { file ->
                            FileDetailedListItem(
                                file = file,
                                isSelectionMode = uiState.isSelectionMode,
                                isSelected = uiState.selectedPaths.contains(file.path),
                                onClick = {
                                    if (uiState.isSelectionMode) {
                                        viewModel.toggleFileSelection(file.path)
                                    } else {
                                        if (file.isDirectory) {
                                            viewModel.loadDirectory(file.path)
                                        } else {
                                            onOpenFile(file, uiState.sortOption, null)
                                        }
                                    }
                                },
                                onLongClick = {
                                    viewModel.toggleFileSelection(file.path)
                                },
                                showPath = uiState.searchQuery.isNotBlank()
                            )
                            HorizontalDivider(color = Color(0xFF202020), thickness = 0.5.dp)
                        }
                    }
                }
                else -> {
                    // Standard List
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(filteredFiles, key = { it.path }) { file ->
                            FileListItem(
                                file = file,
                                isSelectionMode = uiState.isSelectionMode,
                                isSelected = uiState.selectedPaths.contains(file.path),
                                onClick = {
                                    if (uiState.isSelectionMode) {
                                        viewModel.toggleFileSelection(file.path)
                                    } else {
                                        if (file.isDirectory) {
                                            viewModel.loadDirectory(file.path)
                                        } else {
                                            onOpenFile(file, uiState.sortOption, null)
                                        }
                                    }
                                },
                                onLongClick = {
                                    viewModel.toggleFileSelection(file.path)
                                },
                                showPath = uiState.searchQuery.isNotBlank()
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
}
