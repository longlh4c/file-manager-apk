package com.antigravity.filemanager.presentation.analyzer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.antigravity.filemanager.domain.model.FileItem
import com.antigravity.filemanager.domain.model.LargeFileItem
import com.antigravity.filemanager.presentation.components.FileManagerTopBar
import com.antigravity.filemanager.presentation.theme.*

@Composable
fun StorageAnalysisScreen(
    onNavigateBack: () -> Unit,
    onNavigateToStorageBreakdown: (String) -> Unit = {},
    onNavigateToLargeFiles: () -> Unit = {},
    onNavigateToRecycleBin: () -> Unit = {},
    onNavigateToDuplicateFiles: () -> Unit = {},
    viewModel: StorageAnalysisViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val data = uiState.data

    Scaffold(
        topBar = {
            FileManagerTopBar(
                title = "Storage Analysis",
                showBackButton = true,
                onNavigationClick = onNavigateBack,
                onMenuClick = { /* More options */ }
            )
        },
        containerColor = DarkBackground
    ) { paddingValues ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DarkBackground)
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = TealPrimary)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DarkBackground)
                    .padding(paddingValues)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
            // CARD 1: Main Storage Breakdown (matching Screenshot 000608)
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkCard),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val root = android.os.Environment.getExternalStorageDirectory().absolutePath
                            onNavigateToStorageBreakdown(root)
                        }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Main storage ",
                                color = TextPrimary,
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Normal
                            )
                            Text(
                                text = "${data.volumeInfo.formattedFree} free",
                                color = TealPrimary,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Normal
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Large percentage
                        Text(
                            text = "${data.volumeInfo.usedPercentageInt}%",
                            color = TextPrimary,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Progress Bar
                        LinearProgressIndicator(
                            progress = { data.volumeInfo.usedPercentage },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = TealPrimary,
                            trackColor = Color(0xFF333333),
                        )

                        Spacer(modifier = Modifier.height(18.dp))

                        // 2-Column Categories Breakdown Table
                        Row(modifier = Modifier.fillMaxWidth()) {
                            // Column 1
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                BreakdownRow(
                                    icon = Icons.Default.PhotoLibrary,
                                    iconColor = Color(0xFFBA68C8),
                                    label = "Images",
                                    size = FileItem.formatBytes(data.breakdown.imagesBytes)
                                )
                                BreakdownRow(
                                    icon = Icons.Default.Movie,
                                    iconColor = Color(0xFFEF5350),
                                    label = "Videos",
                                    size = FileItem.formatBytes(data.breakdown.videosBytes)
                                )
                                BreakdownRow(
                                    icon = Icons.Default.FolderZip,
                                    iconColor = Color(0xFF81C784),
                                    label = "Archives",
                                    size = FileItem.formatBytes(data.breakdown.archivesBytes)
                                )
                            }

                            Spacer(modifier = Modifier.width(16.dp))

                            // Column 2
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                BreakdownRow(
                                    icon = Icons.Default.MusicNote,
                                    iconColor = Color(0xFF4DB6AC),
                                    label = "Audio",
                                    size = FileItem.formatBytes(data.breakdown.audioBytes)
                                )
                                BreakdownRow(
                                    icon = Icons.Default.Description,
                                    iconColor = Color(0xFF42A5F5),
                                    label = "Documents",
                                    size = FileItem.formatBytes(data.breakdown.documentsBytes)
                                )
                                BreakdownRow(
                                    icon = Icons.Default.MoreHoriz,
                                    iconColor = Color(0xFF9E9E9E),
                                    label = "Others",
                                    size = FileItem.formatBytes(data.breakdown.othersBytes)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = Color(0xFF2E2E2E), thickness = 0.5.dp)

                        // MORE Button
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val root = android.os.Environment.getExternalStorageDirectory().absolutePath
                                    onNavigateToStorageBreakdown(root)
                                }
                                .padding(top = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "MORE",
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.8.sp
                            )
                        }
                    }
                }
            }

            // CARD 2: Large Files (Files > 10 MB) (matching Screenshot 000608)
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkCard),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToLargeFiles() }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Large files ",
                                color = TextPrimary,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Normal
                            )
                            Text(
                                text = FileItem.formatBytes(data.largeFilesTotalBytes),
                                color = TealPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Normal
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Files larger than 10 MB",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // Large Files List
                        data.largeFiles.take(3).forEach { file ->
                            LargeFileRow(item = file)
                            Spacer(modifier = Modifier.height(10.dp))
                        }

                        HorizontalDivider(color = Color(0xFF2E2E2E), thickness = 0.5.dp)

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNavigateToLargeFiles() }
                                .padding(top = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "MORE",
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.8.sp
                            )
                        }
                    }
                }
            }

            // CARD 3: Recycle Bin (matching Screenshot 000608)
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkCard),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToRecycleBin() }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Recycle Bin ",
                                color = TextPrimary,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Normal
                            )
                            Text(
                                text = FileItem.formatBytes(data.recycleBinBytes),
                                color = TealPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Normal
                            )
                        }

                        if (data.recycleBinSampleItem != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFFBA68C8)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Image, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = data.recycleBinSampleItem.name,
                                        color = TextPrimary,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = data.recycleBinSampleItem.path
                                            .substringBeforeLast('/', "/")
                                            .removePrefix(android.os.Environment.getExternalStorageDirectory().absolutePath)
                                            .ifEmpty { "/" },
                                        color = TextSecondary,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = data.recycleBinSampleItem.formattedSize,
                                    color = Color(0xFF81C784),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        HorizontalDivider(color = Color(0xFF2E2E2E), thickness = 0.5.dp)

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNavigateToRecycleBin() }
                                .padding(top = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "MORE",
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.8.sp
                            )
                        }
                    }
                }
            }

            // CARD 4: Duplicate files — duplicates anywhere on device storage. Always shown,
            // even with zero results, same as the Recycle Bin / Large files cards above.
            item {
                DuplicatesCard(
                    title = "Duplicate files",
                    totalBytes = data.duplicateFilesBytes,
                    groups = data.duplicateFileGroups,
                    onClick = onNavigateToDuplicateFiles
                )
            }
        }
    }
}
}

@Composable
private fun DuplicatesCard(
    title: String,
    totalBytes: Long,
    groups: List<com.antigravity.filemanager.domain.model.DuplicateGroup>,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$title ",
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Normal
                )
                Text(
                    text = FileItem.formatBytes(totalBytes),
                    color = TealPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Normal
                )
            }
            Spacer(modifier = Modifier.height(14.dp))

            if (groups.isEmpty()) {
                Text(
                    text = "No duplicate files found",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(14.dp))
            }

            groups.take(2).forEach { group ->
                val item = group.items.firstOrNull { !it.isOriginal } ?: group.items.first()
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF2C3E50)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.FileCopy, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.name,
                            color = TextPrimary,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = item.relativeDir,
                            color = TextSecondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = item.formattedSize,
                        color = Color(0xFF81C784),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            HorizontalDivider(color = Color(0xFF2E2E2E), thickness = 0.5.dp)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onClick() }
                    .padding(top = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "MORE",
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp
                )
            }
        }
    }
}

@Composable
private fun BreakdownRow(
    icon: ImageVector,
    iconColor: Color,
    label: String,
    size: String
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(imageVector = icon, contentDescription = label, tint = iconColor, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(text = size, color = TextSecondary, fontSize = 13.sp)
    }
}

@Composable
private fun LargeFileRow(item: LargeFileItem) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF2C3E50)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Archive, contentDescription = null, tint = Color(0xFFF39C12), modifier = Modifier.size(22.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                color = TextPrimary,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.relativeDir,
                color = TextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = item.formattedSize,
            color = Color(0xFF81C784),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
