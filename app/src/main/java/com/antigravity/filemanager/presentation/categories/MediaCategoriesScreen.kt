package com.antigravity.filemanager.presentation.categories

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.antigravity.filemanager.domain.model.CategoryType
import com.antigravity.filemanager.domain.model.FileItem
import com.antigravity.filemanager.domain.model.FileSortOption
import com.antigravity.filemanager.presentation.components.BottomBarActionItem
import com.antigravity.filemanager.presentation.components.*
import com.antigravity.filemanager.presentation.theme.*
import com.antigravity.filemanager.utils.FileOpener
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaCategoriesScreen(
    onNavigateBack: () -> Unit,
    onOpenFile: (FileItem, FileSortOption, String?) -> Unit = { _, _, _ -> },
    viewModel: CategoriesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val category = uiState.categoryType
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

    // Intercept back button
    BackHandler {
        if (uiState.isSelectionMode) {
            viewModel.clearSelection()
        } else if (uiState.isSearchActive) {
            viewModel.setSearchActive(false)
        } else if (!viewModel.navigateBack()) {
            onNavigateBack()
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

    // The New Folder button that used to set showNewFolderDialog=true was removed from this
    // screen's top bar (see the comment where it used to be, further down) — nothing sets this
    // flag anymore, so the dialog it would show is dropped too.

    if (uiState.showPropertiesDialog) {
        SelectionPropertiesDialog(
            items = uiState.propertiesItems,
            totalSize = uiState.propertiesTotalSize,
            isComputing = false,
            onDismiss = { viewModel.dismissPropertiesDialog() }
        )
    }

    if (uiState.showRenameDialog && uiState.itemForRename != null) {
        TextInputDialog(
            title = "Rename",
            initialValue = uiState.itemForRename!!.name,
            onConfirm = { viewModel.renameFile(it) },
            onDismiss = { viewModel.setShowRenameDialog(null) }
        )
    }

    if (uiState.showCompressDialog) {
        TextInputDialog(
            title = "Compress to Zip",
            initialValue = "Archive.zip",
            confirmButtonText = "COMPRESS",
            onConfirm = { viewModel.compressSelected(it) },
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

    if (uiState.showDeleteDialog) {
        DeleteConfirmDialog(
            itemCount = uiState.selectedPaths.size,
            onConfirm = { moveToTrash ->
                viewModel.deleteSelected(moveToTrash)
                viewModel.setShowDeleteDialog(false)
            },
            onDismiss = { viewModel.setShowDeleteDialog(false) }
        )
    }

    val title = when (category) {
        CategoryType.IMAGES -> "Images"
        CategoryType.AUDIO -> "Audio"
        CategoryType.VIDEOS -> "Videos"
        CategoryType.DOCUMENTS -> "Documents"
        CategoryType.DOWNLOADS -> "Downloads"
        else -> "Category"
    }

    val currentTitle = if (uiState.currentSubfolderName.isNotEmpty()) uiState.currentSubfolderName else title

    val filteredFolders = remember(uiState.folders, uiState.searchQuery) {
        if (uiState.searchQuery.isBlank()) uiState.folders
        else uiState.folders.filter { it.name.contains(uiState.searchQuery, ignoreCase = true) }
    }

    val filteredFiles = remember(uiState.subfolderFiles, uiState.searchQuery) {
        if (uiState.searchQuery.isBlank()) uiState.subfolderFiles
        else uiState.subfolderFiles.filter { it.name.contains(uiState.searchQuery, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            if (uiState.isSelectionMode) {
                // Top Selection Bar
                TopAppBar(
                    title = {
                        Text(
                            text = "${uiState.selectedPaths.size}/${uiState.subfolderFiles.size.coerceAtLeast(uiState.folders.size)}",
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
                            modifier = Modifier.fillMaxWidth()
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
                    title = currentTitle,
                    showBackButton = true,
                    onNavigationClick = {
                        if (!viewModel.navigateBack()) {
                            onNavigateBack()
                        }
                    },
                    actions = {
                        // 1. Search Button
                        IconButton(onClick = { viewModel.setSearchActive(true) }) {
                            Icon(Icons.Default.Search, contentDescription = "Search", tint = TextPrimary)
                        }

                        // New Folder was removed from this screen: a category subfolder
                        // (Images > Pictures, say) deliberately shows a flat, file-only listing
                        // with every subfolder hidden (see filterFilesForCategory) to avoid
                        // duplicating what's already broken out as its own bucket. A folder
                        // created here would never actually appear in this view, which read as
                        // "New Folder doesn't work" — it's not that it silently failed, there was
                        // just nowhere for it to show up.

                        // Sort + View buttons (shared with FileBrowserScreen).
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
                        BottomBarActionItem(
                            icon = Icons.Default.ContentCopy,
                            label = "Copy",
                            tint = TextPrimary,
                            onClick = { viewModel.copySelected() },
                            modifier = Modifier.weight(1f)
                        )

                        BottomBarActionItem(
                            icon = Icons.Default.DriveFileMove,
                            label = "Move",
                            tint = TextPrimary,
                            onClick = { viewModel.cutSelected() },
                            modifier = Modifier.weight(1f)
                        )

                        if (uiState.selectedPaths.size == 1) {
                            BottomBarActionItem(
                                icon = Icons.Default.Edit,
                                label = "Rename",
                                tint = TextPrimary,
                                onClick = {
                                    val firstPath = uiState.selectedPaths.firstOrNull()
                                    val item = uiState.subfolderFiles.find { it.path == firstPath }
                                        ?: uiState.folders.find { it.path == firstPath }?.let { folder ->
                                            FileItem(
                                                id = folder.id,
                                                name = folder.name,
                                                path = folder.path,
                                                isDirectory = true,
                                                itemCount = folder.itemCount,
                                                size = folder.totalSizeBytes,
                                                lastModified = folder.lastModified,
                                                thumbnailUri = folder.latestThumbnailUri
                                            )
                                        }
                                    viewModel.setShowRenameDialog(item)
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        BottomBarActionItem(
                            icon = Icons.Default.Delete,
                            label = "Delete",
                            tint = Color(0xFFEF5350),
                            onClick = { viewModel.setShowDeleteDialog(true) },
                            modifier = Modifier.weight(1f)
                        )

                        // More Button with Popup Menu (including Copy/Move to cloud)
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
                                DropdownMenuItem(
                                    text = { Text("Copy to", color = TextPrimary) },
                                    leadingIcon = { Icon(Icons.Default.CloudUpload, contentDescription = null, tint = TealPrimary) },
                                    onClick = {
                                        showMoreMenu = false
                                        viewModel.openCopyToCloudDialog(isMove = false)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Move to", color = TextPrimary) },
                                    leadingIcon = { Icon(Icons.Default.CloudSync, contentDescription = null, tint = TealPrimary) },
                                    onClick = {
                                        showMoreMenu = false
                                        viewModel.openCopyToCloudDialog(isMove = true)
                                    }
                                )
                                HorizontalDivider(color = Color(0xFF2E2E2E), thickness = 0.5.dp)
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
                                DropdownMenuItem(
                                    text = { Text("Compress", color = TextPrimary) },
                                    leadingIcon = { Icon(Icons.Default.FolderZip, contentDescription = null, tint = TextPrimary) },
                                    onClick = {
                                        showMoreMenu = false
                                        viewModel.setShowCompressDialog(true)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Open with", color = TextPrimary) },
                                    leadingIcon = { Icon(Icons.Default.OpenInBrowser, contentDescription = null, tint = TextPrimary) },
                                    onClick = {
                                        showMoreMenu = false
                                        val firstPath = uiState.selectedPaths.firstOrNull()
                                        val file = uiState.subfolderFiles.find { it.path == firstPath }
                                        if (file != null) FileOpener.openWith(context, file)
                                    }
                                )
                                // Was only ever looking up the FIRST selected path, silently
                                // ignoring the rest of a multi-selection.
                                DropdownMenuItem(
                                    text = { Text("Properties", color = TextPrimary) },
                                    leadingIcon = { Icon(Icons.Default.Info, contentDescription = null, tint = TextPrimary) },
                                    onClick = {
                                        showMoreMenu = false
                                        val selectedPaths = uiState.selectedPaths
                                        val fromFiles = uiState.subfolderFiles.filter { it.path in selectedPaths }
                                        val fromFolders = uiState.folders.filter { it.path in selectedPaths }.map { folder ->
                                            FileItem(
                                                id = folder.id,
                                                name = folder.name,
                                                path = folder.path,
                                                isDirectory = true,
                                                itemCount = folder.itemCount,
                                                size = folder.totalSizeBytes,
                                                lastModified = folder.lastModified,
                                                thumbnailUri = folder.latestThumbnailUri
                                            )
                                        }
                                        viewModel.showPropertiesForSelection(fromFiles + fromFolders)
                                    }
                                )
                            }
                        }
                    }
                }
            } else if (uiState.clipboardPaths.isNotEmpty()) {
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
            // Multi-level Breadcrumb bar
            BreadcrumbBar(
                categoryType = category,
                pathSegments = uiState.pathSegments,
                onHomeClick = onNavigateBack,
                onCategoryClick = { viewModel.loadFolders() },
                onSegmentClick = { index -> viewModel.navigateToSegment(index) }
            )

            PullToRefreshWrapper(
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.fillMaxSize()
            ) {
            if (uiState.folderHistory.isNotEmpty()) {
                if (filteredFiles.isEmpty() && !uiState.isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "No files found",
                            color = TextSecondary,
                            fontSize = 15.sp
                        )
                    }
                } else {
                    // Nested folder contents, with ViewMode support
                    when (viewMode) {
                        ViewMode.GRID -> {
                            LazyVerticalGrid(
                                // Adaptive so column count follows actual screen width instead
                                // of a hardcoded number — see FileBrowserScreen's identical grid
                                // for the full rationale.
                                columns = GridCells.Adaptive(minSize = 100.dp),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 4.dp, vertical = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
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
                                                    viewModel.openSubfolder(file.path, file.name)
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
                                                    viewModel.openSubfolder(file.path, file.name)
                                                } else {
                                                    onOpenFile(file, uiState.sortOption, null)
                                                }
                                            }
                                        },
                                        onLongClick = {
                                            viewModel.toggleFileSelection(file.path)
                                        }
                                    )
                                    HorizontalDivider(color = Color(0xFF202020), thickness = 0.5.dp)
                                }
                            }
                        }
                        else -> {
                            // Standard List view
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
                                                    viewModel.openSubfolder(file.path, file.name)
                                                } else {
                                                    onOpenFile(file, uiState.sortOption, null)
                                                }
                                            }
                                        },
                                        onLongClick = {
                                            viewModel.toggleFileSelection(file.path)
                                        }
                                    )
                                    HorizontalDivider(color = Color(0xFF202020), thickness = 0.5.dp)
                                }
                            }
                        }
                    }
                }
            } else {
                // Root category folders view for Audio, Images, Videos, Documents, Downloads. Was
                // branching on `category` alone (Audio always list, everything else always grid)
                // so the View option in the sort/view sheet had no effect at all here — only the
                // subfolder file listing below respected it. Now it's keyed off viewMode like
                // every other listing in the app, with `category` only picking the right
                // thumbnail fallback icon/badge for the list rows.
                val (badgeIcon, badgeColor) = when (category) {
                    CategoryType.AUDIO -> Icons.Default.MusicNote to Color(0xFF26A69A)
                    CategoryType.VIDEOS -> Icons.Default.Movie to Color(0xFFEF5350)
                    CategoryType.IMAGES -> Icons.Default.Image to Color(0xFFBA68C8)
                    CategoryType.DOCUMENTS -> Icons.Default.Article to Color(0xFF42A5F5)
                    else -> Icons.Default.Folder to Color(0xFFFFB74D)
                }
                if (viewMode == ViewMode.GRID) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 100.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(filteredFolders, key = { it.id }) { folder ->
                            MediaFolderCard(
                                folder = folder,
                                isSelected = uiState.selectedPaths.contains(folder.path),
                                onClick = {
                                    if (uiState.isSelectionMode) {
                                        viewModel.toggleFileSelection(folder.path)
                                    } else {
                                        viewModel.openSubfolder(folder.path, folder.name)
                                    }
                                },
                                onLongClick = { viewModel.toggleFileSelection(folder.path) }
                            )
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(filteredFolders, key = { it.id }) { folder ->
                            AudioListItem(
                                folder = folder,
                                badgeIcon = badgeIcon,
                                badgeColor = badgeColor,
                                isSelected = uiState.selectedPaths.contains(folder.path),
                                onClick = {
                                    if (uiState.isSelectionMode) {
                                        viewModel.toggleFileSelection(folder.path)
                                    } else {
                                        viewModel.openSubfolder(folder.path, folder.name)
                                    }
                                },
                                onLongClick = { viewModel.toggleFileSelection(folder.path) }
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
