package com.antigravity.filemanager.presentation.cloud

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.ui.viewinterop.AndroidView
import com.antigravity.filemanager.domain.model.CloudProvider
import com.antigravity.filemanager.domain.model.FileItem
import com.antigravity.filemanager.domain.model.FileSortOption
import com.antigravity.filemanager.presentation.components.*
import com.antigravity.filemanager.presentation.theme.*
import com.antigravity.filemanager.presentation.viewers.CloudViewerSession

private val CLOUD_VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "3gp", "ts", "m4v")
private val CLOUD_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "svg", "raw", "dng")
private val CLOUD_STREAMABLE_EXTENSIONS = CLOUD_VIDEO_EXTENSIONS + CLOUD_IMAGE_EXTENSIONS

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudExplorerScreen(
    accountId: String,
    title: String,
    onNavigateToCloudList: () -> Unit = {},
    onOpenFile: (FileItem, FileSortOption, String?) -> Unit = { _, _, _ -> },
    viewModel: CloudExplorerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showSortMenu by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }

    if (showCreateFolderDialog) {
        com.antigravity.filemanager.presentation.components.TextInputDialog(
            title = "New Folder",
            initialValue = "",
            confirmButtonText = "CREATE",
            onConfirm = { name ->
                viewModel.createFolder(name)
                showCreateFolderDialog = false
            },
            onDismiss = { showCreateFolderDialog = false }
        )
    }


    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    BackHandler {
        if (uiState.isSelectionMode) {
            viewModel.clearSelection()
        } else if (uiState.isSearchActive) {
            viewModel.setSearchActive(false)
        } else if (!viewModel.navigateBack()) {
            onNavigateToCloudList()
        }
    }

    if (uiState.showPropertiesDialog && uiState.itemForProperties != null) {
        PropertiesDialog(
            file = uiState.itemForProperties!!,
            onDismiss = { viewModel.showProperties(null) }
        )
    }

    if (uiState.showRenameDialog && uiState.itemToRename != null) {
        TextInputDialog(
            title = "Rename",
            initialValue = uiState.itemToRename?.name ?: "",
            confirmButtonText = "RENAME",
            onConfirm = { newName -> viewModel.renameSelected(newName) },
            onDismiss = { viewModel.setShowRenameDialog(null) }
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
            onConfirm = { moveToTrash -> viewModel.deleteSelected(moveToTrash) },
            onDismiss = { viewModel.setShowDeleteDialog(false) },
            showMoveToTrashOption = !uiState.isInsideTrashView,
            title = if (uiState.isInsideTrashView) "Delete Permanently" else "Delete",
            message = if (uiState.isInsideTrashView)
                "Permanently delete ${uiState.selectedPaths.size} item(s)? This cannot be undone."
            else
                "Are you sure you want to delete ${uiState.selectedPaths.size} item(s)?"
        )
    }

    if (uiState.showEmptyTrashDialog) {
        DeleteConfirmDialog(
            itemCount = uiState.files.size,
            onConfirm = { viewModel.emptyTrash() },
            onDismiss = { viewModel.setShowEmptyTrashDialog(false) },
            showMoveToTrashOption = false,
            title = "Empty Trash",
            message = "Permanently delete all ${uiState.files.size} item(s) in Trash? This cannot be undone."
        )
    }

    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(uiState.isSearchActive) {
        if (uiState.isSearchActive) {
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    // Search results come recursively from the ViewModel (this folder + every subfolder), not a
    // plain filter of the current listing — see CloudExplorerViewModel.onSearchQueryChanged.
    val filteredFiles = if (uiState.searchQuery.isBlank()) uiState.files else uiState.searchResults

    Scaffold(
        topBar = {
            if (uiState.isSelectionMode) {
                TopAppBar(
                    title = {
                        Text(
                            text = "${uiState.selectedPaths.size}/${filteredFiles.size}",
                            color = TextPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = TextPrimary)
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.selectAll() }) {
                            Icon(Icons.Default.GridView, contentDescription = "Select All", tint = TextPrimary)
                        }
                        IconButton(onClick = { viewModel.invertSelection() }) {
                            Icon(Icons.Default.SwapHoriz, contentDescription = "Invert Selection", tint = TextPrimary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkCard)
                )
            } else if (uiState.isSearchActive) {
                TopAppBar(
                    title = {
                        TextField(
                            value = uiState.searchQuery,
                            onValueChange = { viewModel.onSearchQueryChanged(it) },
                            placeholder = { Text("Search cloud files...", color = TextSecondary) },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                cursorColor = TealPrimary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocusRequester)
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.setSearchActive(false) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search", tint = TextPrimary)
                        }
                    },
                    actions = {
                        if (uiState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear", tint = TextSecondary)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkCard)
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = uiState.title,
                                color = TextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            uiState.account?.email?.let {
                                Text(
                                    text = it,
                                    color = TextSecondary,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (!viewModel.navigateBack()) {
                                onNavigateToCloudList()
                            }
                        }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = TextPrimary
                            )
                        }
                    },
                    actions = {
                        if (uiState.isInsideTrashView && uiState.files.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setShowEmptyTrashDialog(true) }) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = "Empty Trash", tint = TextPrimary)
                            }
                        }
                        IconButton(onClick = { viewModel.setSearchActive(true) }) {
                            Icon(Icons.Default.Search, contentDescription = "Search", tint = TextPrimary)
                        }
                        // Same "New Folder" placement as Local's FileManagerTopBar (Search, New
                        // Folder, Sort/More) — hidden inside Trash/Rubbish Bin, where creating a
                        // folder makes no sense (see the empty-state message right below it).
                        if (!uiState.isInsideTrashView) {
                            IconButton(onClick = { showCreateFolderDialog = true }) {
                                Icon(Icons.Default.CreateNewFolder, contentDescription = "New Folder", tint = TextPrimary)
                            }
                        }
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = TextPrimary)
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false },
                            modifier = Modifier.background(DarkCard)
                        ) {
                            // Same display order as Local's SortViewOptionsBottomSheet — Date
                            // first, then Name, then Size, then Type — not FileSortOption's own
                            // declaration order.
                            listOf(
                                FileSortOption.BY_DATE_DESC,
                                FileSortOption.BY_DATE_ASC,
                                FileSortOption.BY_NAME_ASC,
                                FileSortOption.BY_NAME_DESC,
                                FileSortOption.BY_SIZE_DESC,
                                FileSortOption.BY_SIZE_ASC,
                                FileSortOption.BY_TYPE
                            ).forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = when (option) {
                                                FileSortOption.BY_NAME_ASC -> "Sort by Name (A to Z)"
                                                FileSortOption.BY_NAME_DESC -> "Sort by Name (Z to A)"
                                                FileSortOption.BY_DATE_DESC -> "Sort by Date (Newest first)"
                                                FileSortOption.BY_DATE_ASC -> "Sort by Date (Oldest first)"
                                                FileSortOption.BY_SIZE_DESC -> "Sort by Size (Largest first)"
                                                FileSortOption.BY_SIZE_ASC -> "Sort by Size (Smallest first)"
                                                FileSortOption.BY_TYPE -> "Sort by Type"
                                            },
                                            color = if (uiState.sortOption == option) TealPrimary else TextPrimary,
                                            fontWeight = if (uiState.sortOption == option) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = {
                                        viewModel.onSortChanged(option)
                                        showSortMenu = false
                                    }
                                )
                            }
                            HorizontalDivider(color = Color(0xFF333333))
                            DropdownMenuItem(
                                text = { Text("Refresh", color = TextPrimary) },
                                onClick = {
                                    viewModel.refresh(isManual = true)
                                    showSortMenu = false
                                }
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
                )
            }
        },
        bottomBar = {
            if (uiState.isSelectionMode) {
                Surface(
                    color = DarkCard,
                    tonalElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (uiState.isInsideTrashView) {
                            BottomBarActionItem(
                                icon = Icons.Default.RestoreFromTrash,
                                label = "Restore",
                                tint = TextPrimary,
                                onClick = { viewModel.restoreSelected() },
                                modifier = Modifier.weight(1f)
                            )
                            BottomBarActionItem(
                                icon = Icons.Default.DeleteForever,
                                label = "Delete Permanently",
                                tint = Color(0xFFEF5350),
                                onClick = { viewModel.setShowDeleteDialog(true) },
                                modifier = Modifier.weight(1f)
                            )
                            BottomBarActionItem(
                                icon = Icons.Default.Close,
                                label = "Cancel",
                                tint = TextSecondary,
                                onClick = { viewModel.clearSelection() },
                                modifier = Modifier.weight(1f)
                            )
                        } else {
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
                                        val firstId = uiState.selectedPaths.firstOrNull()
                                        val item = filteredFiles.find { it.id == firstId || it.path == firstId }
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
                            if (uiState.selectedPaths.size == 1) {
                                BottomBarActionItem(
                                    icon = Icons.Default.Info,
                                    label = "Properties",
                                    tint = TextPrimary,
                                    onClick = {
                                        val firstId = uiState.selectedPaths.firstOrNull()
                                        val item = filteredFiles.find { it.id == firstId || it.path == firstId }
                                        viewModel.showProperties(item)
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            BottomBarActionItem(
                                icon = Icons.Default.Close,
                                label = "Cancel",
                                tint = TextSecondary,
                                onClick = { viewModel.clearSelection() },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            } else if (uiState.clipboardPaths.isNotEmpty() && uiState.downloadProgress == null) {
                // Hidden while a transfer is actually running (downloadProgress != null) — the
                // clipboard itself only clears at the very end of paste(), so without this check
                // "Paste Here" sat there the whole time a copy/move was in flight, alongside the
                // progress bar for that same operation, as if nothing had been tapped yet.
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
            // Cloud Breadcrumb Navigation & Free Space Bar (matching screenshots)
            Row(

                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkBackground)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Scrollable Breadcrumb segments
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Cloud Home Icon -> back to the list of connected cloud accounts
                    Icon(
                        imageVector = Icons.Default.Cloud,
                        contentDescription = "Cloud accounts",
                        tint = TealPrimary,
                        modifier = Modifier
                            .size(24.dp)
                            .clickable { onNavigateToCloudList() }
                    )

                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(18.dp)
                    )

                    // Cloud Provider Icon -> jumps to this account's root (path segment 0),
                    // same as tapping "Root" in the breadcrumb — distinct from the generic Cloud
                    // icon above, which exits to the cloud accounts list entirely.
                    Box(
                        modifier = Modifier
                            .clickable { viewModel.navigateToSegment(0) }
                            .padding(horizontal = 2.dp)
                    ) {

                        when (uiState.account?.provider) {
                            CloudProvider.GOOGLE_DRIVE -> {
                                GoogleDriveLogoIcon(modifier = Modifier.size(20.dp))
                            }
                            CloudProvider.DROPBOX -> {
                                DropboxLogoIcon(modifier = Modifier.size(20.dp))
                            }
                            CloudProvider.MEGA -> {
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFD9272E)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "M",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            else -> {
                                Icon(
                                    imageVector = Icons.Default.Cloud,
                                    contentDescription = "Cloud",
                                    tint = TealPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }

                    // Nested subfolder segments
                    uiState.pathSegments.drop(1).forEachIndexed { index, segment ->
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .size(18.dp)
                        )
                        Text(
                            text = segment,
                            color = if (index == uiState.pathSegments.size - 2) TextPrimary else TextSecondary,
                            fontSize = 14.sp,
                            fontWeight = if (index == uiState.pathSegments.size - 2) FontWeight.Medium else FontWeight.Normal,
                            modifier = Modifier
                                .clickable { viewModel.navigateToSegment(index + 1) }
                                .padding(horizontal = 2.dp, vertical = 2.dp)
                        )
                    }
                }

                // Right: Storage Capacity Display (compact form, e.g. "36.14/50 GB Used")
                val quotaStorageText = remember(uiState.account, uiState.title) {
                    uiState.account?.formattedQuotaText ?: when {
                        uiState.title.contains("Google", ignoreCase = true) -> "0/15 GB Used"
                        uiState.title.contains("Dropbox", ignoreCase = true) -> "0/2 GB Used"
                        uiState.title.contains("Mega", ignoreCase = true) -> "36.14/50 GB Used"
                        else -> "0/15 GB Used"
                    }
                }

                Text(
                    text = quotaStorageText,
                    color = Color(0xFF58A6FF),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }


            if (uiState.isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = TealPrimary
                )
            }

            PullToRefreshWrapper(
                onRefresh = { viewModel.refresh(isManual = true) },
                modifier = Modifier.fillMaxSize()
            ) {
                if (filteredFiles.isEmpty() && !uiState.isLoading && !uiState.isSearching) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Image(
                                painter = androidx.compose.ui.res.painterResource(com.antigravity.filemanager.R.drawable.empty_folder_owl),
                                contentDescription = null,
                                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(TextSecondary),
                                modifier = Modifier.size(140.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = if (uiState.isInsideTrashView) "Trash is empty" else "This cloud folder is empty",
                                color = TextSecondary,
                                fontSize = 16.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(filteredFiles, key = { it.id }) { file ->
                            FileListItem(
                                file = file,
                                // Keyed by id, not path: MEGA (and its Rubbish Bin in particular)
                                // allows multiple siblings with the identical name, and this
                                // app's display "path" for a cloud item is built from its name —
                                // two same-named siblings compute the exact same path string, so
                                // selecting one by path used to select both.
                                isSelected = uiState.selectedPaths.contains(file.id),
                                isSelectionMode = uiState.isSelectionMode,
                                onClick = {
                                    // Dropbox's Trash is built from DeletedMetadata, which carries
                                    // no file/folder type at all (see DropboxApiClient.listTrash) —
                                    // every entry gets marked isDirectory=false regardless of what
                                    // it really was, and none of them are downloadable content
                                    // (deleted items aren't retrievable via the normal file-content
                                    // API). Tapping one used to always try to "open" it as a file,
                                    // failing with a confusing "not found" for what could easily be
                                    // a deleted folder. MEGA's Rubbish Bin and Google Drive's Trash
                                    // are real listings with accurate types/IDs and stay tap-to-open
                                    // as normal.
                                    val isUnopenableDropboxTrash = uiState.isInsideTrashView &&
                                        uiState.account?.provider == com.antigravity.filemanager.domain.model.CloudProvider.DROPBOX
                                    if (uiState.isSelectionMode || isUnopenableDropboxTrash) {
                                        viewModel.toggleSelection(file.id)
                                    } else {
                                        if (file.isDirectory) {
                                            viewModel.openFolder(file)
                                        } else {
                                            CloudViewerSession.set(accountId, filteredFiles)
                                            val openWith: (FileItem, (FileItem) -> Unit) -> Unit =
                                                if (file.extension.lowercase() in CLOUD_STREAMABLE_EXTENSIONS) viewModel::openMediaStream else viewModel::openFile
                                            openWith(file) { readyItem ->
                                                onOpenFile(readyItem, uiState.sortOption, accountId)
                                            }
                                        }
                                    }
                                },
                                onLongClick = {
                                    viewModel.toggleSelection(file.id)
                                },
                                onVisible = { viewModel.requestThumbnail(it) },
                                showPath = uiState.searchQuery.isNotBlank()
                            )
                        }
                    }
                }
            }
        }
    }
}



