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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.antigravity.filemanager.domain.model.FileItem
import com.antigravity.filemanager.presentation.components.*
import com.antigravity.filemanager.presentation.theme.*
import com.antigravity.filemanager.utils.FileOpener
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageFolderBreakdownScreen(
    onNavigateBack: () -> Unit,
    initialPath: String = android.os.Environment.getExternalStorageDirectory().absolutePath,
    viewModel: StorageAnalysisViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var currentPath by remember { mutableStateOf(initialPath) }
    var pathHistory by remember { mutableStateOf(listOf(initialPath)) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var selectedPaths by remember { mutableStateOf(setOf<String>()) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Starts true (not false): LaunchedEffect's body only starts running after the first
    // composition commits, so a false default here let that first frame render with
    // folderItems still empty and isScanning=false — briefly showing the "empty folder" icon
    // even when the folder isn't empty, before the actual scan below had a chance to run.
    var isScanning by remember { mutableStateOf(true) }
    var folderItems by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var itemToRename by remember { mutableStateOf<FileItem?>(null) }
    var showCompressDialog by remember { mutableStateOf(false) }
    var showPropertiesDialog by remember { mutableStateOf(false) }
    var itemForProperties by remember { mutableStateOf<FileItem?>(null) }

    val rootPath = android.os.Environment.getExternalStorageDirectory().absolutePath

    LaunchedEffect(currentPath, uiState.mutationTick) {
        isScanning = true
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val dir = File(currentPath)
                val children = dir.listFiles() ?: emptyArray()
                val list = children.map { f ->
                    // Fetch each directory's children once and reuse it for both the size
                    // computation and the item count, instead of calling listFiles() twice.
                    val childList = if (f.isDirectory) f.listFiles() else null
                    val size = if (f.isDirectory) calculateDirSize(childList) else f.length()
                    val itemCount = childList?.size ?: 0
                    FileItem(
                        id = f.absolutePath,
                        name = f.name,
                        path = f.absolutePath,
                        size = size,
                        isDirectory = f.isDirectory,
                        itemCount = itemCount,
                        extension = if (f.isDirectory) "" else f.extension.lowercase(Locale.getDefault()),
                        folderBadgeType = getFolderBadgeForName(f.name)
                    )
                }
                folderItems = list.sortedByDescending { it.size }
            } catch (e: Exception) {
                folderItems = emptyList()
            }
        }
        kotlinx.coroutines.delay(200)
        isScanning = false
    }

    val totalDirBytes = remember(folderItems) {
        val total = folderItems.sumOf { it.size }
        if (total > 0L) total else 1L
    }

    val filteredItems = remember(folderItems, searchQuery) {
        if (searchQuery.isBlank()) folderItems else folderItems.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    BackHandler {
        if (selectedPaths.isNotEmpty()) {
            selectedPaths = emptySet()
        } else if (isSearchActive) {
            isSearchActive = false
            searchQuery = ""
        } else if (pathHistory.size > 1) {
            val newHistory = pathHistory.dropLast(1)
            pathHistory = newHistory
            currentPath = newHistory.last()
        } else {
            onNavigateBack()
        }
    }

    if (showDeleteDialog) {
        DeleteConfirmDialog(
            itemCount = selectedPaths.size,
            onConfirm = {
                viewModel.deleteSelected(selectedPaths.toList()) {
                    selectedPaths = emptySet()
                }
                showDeleteDialog = false
            },
            onDismiss = { showDeleteDialog = false }
        )
    }

    if (showRenameDialog && itemToRename != null) {
        TextInputDialog(
            title = "Rename",
            initialValue = itemToRename!!.name,
            confirmButtonText = "OK",
            selectNameWithoutExtension = !itemToRename!!.isDirectory,
            onConfirm = { newName ->
                viewModel.rename(itemToRename!!.path, newName) {
                    selectedPaths = emptySet()
                }
                showRenameDialog = false
                itemToRename = null
            },
            onDismiss = {
                showRenameDialog = false
                itemToRename = null
            }
        )
    }

    if (showCompressDialog) {
        TextInputDialog(
            title = "Compress to Zip",
            initialValue = "Archive.zip",
            confirmButtonText = "COMPRESS",
            onConfirm = { zipName ->
                viewModel.compress(selectedPaths.toList(), zipName, currentPath) {
                    selectedPaths = emptySet()
                }
                showCompressDialog = false
            },
            onDismiss = { showCompressDialog = false }
        )
    }

    if (uiState.pendingOverwriteZipPath != null) {
        OverwriteFileConfirmDialog(
            fileName = File(uiState.pendingOverwriteZipPath!!).name,
            onConfirm = { viewModel.confirmCompressOverwrite() },
            onDismiss = { viewModel.cancelCompressOverwrite() }
        )
    }

    if (uiState.transferProgress != null) {
        CloudDownloadProgressDialog(
            progress = uiState.transferProgress!!,
            onCancel = { viewModel.cancelTransfer() }
        )
    }

    if (showPropertiesDialog && itemForProperties != null) {
        PropertiesDialog(
            file = itemForProperties!!,
            onDismiss = {
                showPropertiesDialog = false
                itemForProperties = null
            }
        )
    }

    Scaffold(
        topBar = {
            if (selectedPaths.isNotEmpty()) {
                TopAppBar(
                    title = {
                        Text(
                            text = "${selectedPaths.size}/${filteredItems.size}",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { selectedPaths = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            selectedPaths = filteredItems.map { it.path }.toSet()
                        }) {
                            Icon(Icons.Default.GridView, contentDescription = "Select All", tint = Color.White)
                        }
                        IconButton(onClick = {
                            val all = filteredItems.map { it.path }.toSet()
                            selectedPaths = all - selectedPaths
                        }) {
                            Icon(Icons.Default.SelectAll, contentDescription = "Invert Selection", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF004D40)
                    )
                )
            } else if (isSearchActive) {
                TopAppBar(
                    title = {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search folders...", color = TextSecondary) },
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
                        IconButton(onClick = {
                            isSearchActive = false
                            searchQuery = ""
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                        }
                    },
                    actions = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextPrimary)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
                )
            } else {
                FileManagerTopBar(
                    title = if (currentPath == rootPath) "Main storage" else File(currentPath).name,
                    showBackButton = true,
                    onNavigationClick = {
                        if (pathHistory.size > 1) {
                            val newHistory = pathHistory.dropLast(1)
                            pathHistory = newHistory
                            currentPath = newHistory.last()
                        } else {
                            onNavigateBack()
                        }
                    },
                    onSearchClick = { isSearchActive = true },
                    onMenuClick = { /* Menu */ }
                )
            }
        },
        bottomBar = {
            if (selectedPaths.isNotEmpty()) {
                Surface(
                    color = DarkCard,
                    tonalElevation = 8.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(bottom = 6.dp, top = 2.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BottomBarActionItem(
                            icon = Icons.Default.ContentCopy,
                            label = "Copy",
                            tint = TextPrimary,
                            onClick = {
                                viewModel.copySelected(selectedPaths.toList())
                                selectedPaths = emptySet()
                            },
                            modifier = Modifier.weight(1f)
                        )
                        BottomBarActionItem(
                            icon = Icons.Default.DriveFileMove,
                            label = "Move",
                            tint = TextPrimary,
                            onClick = {
                                viewModel.cutSelected(selectedPaths.toList())
                                selectedPaths = emptySet()
                            },
                            modifier = Modifier.weight(1f)
                        )
                        if (selectedPaths.size == 1) {
                            BottomBarActionItem(
                                icon = Icons.Default.Edit,
                                label = "Rename",
                                tint = TextPrimary,
                                onClick = {
                                    val firstPath = selectedPaths.firstOrNull()
                                    itemToRename = folderItems.find { it.path == firstPath }
                                    showRenameDialog = true
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        BottomBarActionItem(
                            icon = Icons.Default.Delete,
                            label = "Delete",
                            tint = Color(0xFFEF5350),
                            onClick = { showDeleteDialog = true },
                            modifier = Modifier.weight(1f)
                        )
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
                                val firstPath = selectedPaths.firstOrNull()
                                val selectedItem = folderItems.find { it.path == firstPath }
                                if (selectedPaths.size == 1 && selectedItem?.isDirectory == true) {
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
                                val hasArchive = selectedPaths.any { path ->
                                    val ext = path.substringAfterLast('.', "").lowercase()
                                    ext in setOf("zip", "rar", "7z", "tar", "gz")
                                }
                                if (hasArchive) {
                                    DropdownMenuItem(
                                        text = { Text("Extract here", color = TextPrimary) },
                                        leadingIcon = { Icon(Icons.Default.Unarchive, contentDescription = null, tint = TealPrimary) },
                                        onClick = {
                                            showMoreMenu = false
                                            val list = selectedPaths.toList()
                                            selectedPaths = emptySet()
                                            viewModel.extract(list, currentPath)
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Compress", color = TextPrimary) },
                                    leadingIcon = { Icon(Icons.Default.FolderZip, contentDescription = null, tint = TextPrimary) },
                                    onClick = {
                                        showMoreMenu = false
                                        showCompressDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Properties", color = TextPrimary) },
                                    leadingIcon = { Icon(Icons.Default.Info, contentDescription = null, tint = TextPrimary) },
                                    onClick = {
                                        showMoreMenu = false
                                        itemForProperties = folderItems.find { it.path == firstPath }
                                        showPropertiesDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Share", color = TextPrimary) },
                                    leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, tint = TextPrimary) },
                                    onClick = {
                                        showMoreMenu = false
                                        FileOpener.shareFiles(context, selectedPaths.toList())
                                    }
                                )
                            }
                        }
                    }
                }
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
            // Storage Breadcrumb (Pie chart icon > Storage Icon > Folders) matching Screenshot 1:30
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkBackground)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Pie Chart Icon in Cyan/Teal
                Icon(
                    imageVector = Icons.Default.PieChart,
                    contentDescription = "Analysis",
                    tint = Color(0xFF00BCD4),
                    modifier = Modifier
                        .size(24.dp)
                        .clickable { onNavigateBack() }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))

                // Hard Drive / Storage Volume Icon
                Icon(
                    imageVector = Icons.Default.Storage,
                    contentDescription = "Storage",
                    tint = TextSecondary,
                    modifier = Modifier
                        .size(22.dp)
                        .clickable {
                            currentPath = rootPath
                            pathHistory = listOf(rootPath)
                        }
                )

                if (currentPath != rootPath) {
                    val relativeSegments = currentPath.removePrefix(rootPath).trimStart('/').split('/')
                    relativeSegments.forEachIndexed { idx, segment ->
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = segment,
                            color = if (idx == relativeSegments.size - 1) TealPrimary else TextSecondary,
                            fontSize = 14.sp,
                            fontWeight = if (idx == relativeSegments.size - 1) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.clickable {
                                val targetPath = "$rootPath/${relativeSegments.take(idx + 1).joinToString("/")}"
                                currentPath = targetPath
                                pathHistory = pathHistory.takeWhile { it != targetPath } + targetPath
                            }
                        )
                    }
                }
            }

            HorizontalDivider(color = Color(0xFF222222), thickness = 0.5.dp)

            if (isScanning || uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = TealPrimary)
                }
            } else if (filteredItems.isEmpty()) {
                EmptyFolderState()
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filteredItems, key = { it.path }) { item ->
                        val percentage = (item.size.toDouble() / totalDirBytes.toDouble()) * 100.0
                        val formattedPercent = String.format(Locale.getDefault(), "%.2f%%", percentage)
                        val isSelected = selectedPaths.contains(item.path)

                        StorageFolderRow(
                            item = item,
                            percentage = percentage,
                            formattedPercent = formattedPercent,
                            isSelected = isSelected,
                            onFolderClick = {
                                if (selectedPaths.isNotEmpty()) {
                                    selectedPaths = if (isSelected) selectedPaths - item.path else selectedPaths + item.path
                                } else {
                                    if (item.isDirectory) {
                                        currentPath = item.path
                                        pathHistory = pathHistory + item.path
                                    } else {
                                        FileOpener.openFile(context, item)
                                    }
                                }
                            },
                            onCheckboxToggle = {
                                selectedPaths = if (isSelected) selectedPaths - item.path else selectedPaths + item.path
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
private fun StorageFolderRow(
    item: FileItem,
    percentage: Double,
    formattedPercent: String,
    isSelected: Boolean,
    onFolderClick: () -> Unit,
    onCheckboxToggle: () -> Unit
) {
    val rowBg = if (isSelected) Color(0xFF00695C).copy(alpha = 0.6f) else Color.Transparent

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .clickable { onFolderClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Folder icon for real directories; a proper file-type icon (video/image/pdf/etc.)
            // for files — this row previously always rendered a folder icon here, so files like
            // videos or photos on this screen were indistinguishable from actual subfolders.
            if (item.isDirectory) {
                FolderIconWithBadge(badgeType = item.folderBadgeType)
            } else {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(DarkCard),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = getFileIcon(item),
                        contentDescription = null,
                        tint = getFileIconColor(item),
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))

            // Title & Subtitle with proportional progress bar in the background
            Column(modifier = Modifier.weight(1f)) {
                // Name
                Text(
                    text = item.name,
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))

                // Items count and Formatted Size e.g. "3 items (42,50 GB)"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                if (percentage > 10.0) Color(0xFF1E88E5).copy(alpha = 0.6f)
                                else if (percentage > 2.0) Color(0xFF26A69A).copy(alpha = 0.5f)
                                else Color.Transparent
                            )
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = if (item.isDirectory) "${item.itemCount} items (${item.formattedSize})" else item.formattedSize,
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            // Percentage on the right (matching Screenshot 1:30)
            Text(
                text = formattedPercent,
                color = TextSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                modifier = Modifier.padding(horizontal = 12.dp)
            )

            // Checkbox on the right
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onCheckboxToggle() },
                colors = CheckboxDefaults.colors(
                    checkedColor = TealPrimary,
                    uncheckedColor = TextSecondary,
                    checkmarkColor = PureBlack
                )
            )
        }
    }
}

private fun calculateDirSize(files: Array<File>?): Long {
    if (files == null) return 0L
    var size = 0L
    try {
        for (f in files) {
            size += if (f.isDirectory) {
                // Limit recursion depth to 2 levels for speed
                var subSize = 0L
                val subFiles = f.listFiles() ?: emptyArray()
                for (sf in subFiles) {
                    subSize += if (sf.isFile) sf.length() else 0L
                }
                subSize
            } else {
                f.length()
            }
        }
    } catch (e: Exception) {
        return 0L
    }
    return size
}

private fun getFolderBadgeForName(name: String): com.antigravity.filemanager.domain.model.FolderBadgeType {
    return when (name.lowercase(Locale.getDefault())) {
        "dcim", "camera" -> com.antigravity.filemanager.domain.model.FolderBadgeType.CAMERA
        "documents", "doc" -> com.antigravity.filemanager.domain.model.FolderBadgeType.DOCUMENTS
        "download", "downloads" -> com.antigravity.filemanager.domain.model.FolderBadgeType.DOWNLOAD
        "movies", "video", "videos" -> com.antigravity.filemanager.domain.model.FolderBadgeType.MOVIES
        "music", "audio", "mi music" -> com.antigravity.filemanager.domain.model.FolderBadgeType.MUSIC
        else -> com.antigravity.filemanager.domain.model.FolderBadgeType.STANDARD
    }
}
