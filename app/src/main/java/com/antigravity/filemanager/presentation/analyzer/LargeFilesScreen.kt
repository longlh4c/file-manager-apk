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
import com.antigravity.filemanager.domain.model.LargeFileItem
import com.antigravity.filemanager.presentation.components.*
import com.antigravity.filemanager.presentation.theme.*
import com.antigravity.filemanager.utils.FileOpener
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LargeFilesScreen(
    onNavigateBack: () -> Unit,
    viewModel: StorageAnalysisViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var selectedPaths by remember { mutableStateOf(setOf<String>()) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    var showMoreMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var itemToRename by remember { mutableStateOf<FileItem?>(null) }
    var showCompressDialog by remember { mutableStateOf(false) }
    var showPropertiesDialog by remember { mutableStateOf(false) }
    var itemForProperties by remember { mutableStateOf<FileItem?>(null) }

    val largeFiles = uiState.data.largeFiles
    val filteredFiles = remember(largeFiles, searchQuery) {
        if (searchQuery.isBlank()) largeFiles else largeFiles.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    BackHandler {
        if (selectedPaths.isNotEmpty()) {
            selectedPaths = emptySet()
        } else if (isSearchActive) {
            isSearchActive = false
            searchQuery = ""
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
        val parentDir = selectedPaths.firstOrNull()?.let { File(it).parent } ?: android.os.Environment.getExternalStorageDirectory().absolutePath
        TextInputDialog(
            title = "Compress to Zip",
            initialValue = "Archive.zip",
            confirmButtonText = "COMPRESS",
            onConfirm = { zipName ->
                viewModel.compress(selectedPaths.toList(), zipName, parentDir) {
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
                            text = "${selectedPaths.size}/${filteredFiles.size}",
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
                            selectedPaths = filteredFiles.map { it.path }.toSet()
                        }) {
                            Icon(Icons.Default.GridView, contentDescription = "Select All", tint = Color.White)
                        }
                        IconButton(onClick = {
                            val all = filteredFiles.map { it.path }.toSet()
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
                            placeholder = { Text("Search large files...", color = TextSecondary) },
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
                    title = "Large files",
                    showBackButton = true,
                    onNavigationClick = onNavigateBack,
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
                                    val f = filteredFiles.find { it.path == firstPath }
                                    if (f != null) {
                                        itemToRename = FileItem(
                                            id = f.path,
                                            name = f.name,
                                            path = f.path,
                                            size = f.sizeBytes
                                        )
                                        showRenameDialog = true
                                    }
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
                                            val targetDir = firstPath?.let { File(it).parent } ?: android.os.Environment.getExternalStorageDirectory().absolutePath
                                            selectedPaths = emptySet()
                                            viewModel.extract(list, targetDir)
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
                                        val f = filteredFiles.find { it.path == firstPath }
                                        if (f != null) {
                                            itemForProperties = FileItem(
                                                id = f.path,
                                                name = f.name,
                                                path = f.path,
                                                size = f.sizeBytes
                                            )
                                            showPropertiesDialog = true
                                        }
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
            // Large files Breadcrumb (Pie chart icon > Large files) matching Screenshot 1:31
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
                Text(
                    text = "Large files",
                    color = TealPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            HorizontalDivider(color = Color(0xFF222222), thickness = 0.5.dp)

            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = TealPrimary)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filteredFiles, key = { it.path }) { item ->
                        val isSelected = selectedPaths.contains(item.path)
                        LargeFileDetailedRow(
                            item = item,
                            isSelected = isSelected,
                            onClick = {
                                if (selectedPaths.isNotEmpty()) {
                                    selectedPaths = if (isSelected) selectedPaths - item.path else selectedPaths + item.path
                                } else {
                                    val fileItem = FileItem(
                                        id = item.path,
                                        name = item.name,
                                        path = item.path,
                                        size = item.sizeBytes
                                    )
                                    FileOpener.openFile(context, fileItem)
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
private fun LargeFileDetailedRow(
    item: LargeFileItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onCheckboxToggle: () -> Unit
) {
    val rowBg = if (isSelected) Color(0xFF00695C).copy(alpha = 0.6f) else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail / File Icon with striped icon matching Screenshot 1:31
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF232B32)),
            contentAlignment = Alignment.Center
        ) {
            val ext = item.name.substringAfterLast('.', "").lowercase()
            val videoExts = setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "3gp", "ts", "m4v")
            val imgExts = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic")
            when {
                ext in imgExts || ext in videoExts -> {
                    coil.compose.AsyncImage(
                        model = item.path,
                        contentDescription = item.name,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                ext in listOf("iso", "img", "bin") -> {
                    // Striped Disc/ISO Icon matching Screenshot 1:31
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(4.dp)
                            .clip(RoundedCornerShape(4.dp))
                    ) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth().background(Color(0xFFE53935)))
                        Box(modifier = Modifier.weight(1f).fillMaxWidth().background(Color(0xFF43A047)))
                        Box(modifier = Modifier.weight(1f).fillMaxWidth().background(Color(0xFF1E88E5)))
                    }
                }
                ext in listOf("zip", "rar", "7z") -> {
                    Icon(Icons.Default.FolderZip, contentDescription = null, tint = Color(0xFFFFB300), modifier = Modifier.size(28.dp))
                }
                else -> {
                    Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(26.dp))
                }
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        // Title, Parent Path & Size + Date matching Screenshot 1:31
        Column(modifier = Modifier.weight(1f)) {
            // File Name
            Text(
                text = item.name,
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))

            // Subtitle 1: Directory path
            Text(
                text = item.relativeDir,
                color = TextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))

            // Subtitle 2: Size & Date row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.formattedSize,
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                Text(
                    text = item.formattedDate,
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Checkbox on the right (matching Screenshot 1:31)
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
