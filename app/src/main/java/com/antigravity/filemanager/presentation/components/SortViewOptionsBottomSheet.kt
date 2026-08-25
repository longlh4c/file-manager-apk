package com.antigravity.filemanager.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.filemanager.domain.model.FileSortOption
import com.antigravity.filemanager.presentation.theme.*

enum class ViewMode {
    LIST,
    GRID,
    DETAILED_LIST
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortViewOptionsBottomSheet(
    currentSort: FileSortOption,
    currentViewMode: ViewMode = ViewMode.LIST,
    showHiddenFiles: Boolean,
    onSortChange: (FileSortOption, Boolean) -> Unit,
    onViewModeChange: (ViewMode, Boolean) -> Unit = { _, _ -> },
    onShowHiddenFilesChange: (Boolean, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var applyToAllFolders by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }

    val sortLabel = when (currentSort) {
        FileSortOption.BY_NAME_ASC -> "Name ▲"
        FileSortOption.BY_NAME_DESC -> "Name ▼"
        FileSortOption.BY_DATE_DESC -> "Date ▼"
        FileSortOption.BY_DATE_ASC -> "Date ▲"
        FileSortOption.BY_SIZE_DESC -> "Size ▼"
        FileSortOption.BY_SIZE_ASC -> "Size ▲"
        FileSortOption.BY_TYPE -> "Type"
    }

    if (showSortDialog) {
        AlertDialog(
            onDismissRequest = { showSortDialog = false },
            title = { Text("Sort by", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    listOf(
                        FileSortOption.BY_DATE_DESC to "Date (Newest first) ▼",
                        FileSortOption.BY_DATE_ASC to "Date (Oldest first) ▲",
                        FileSortOption.BY_NAME_ASC to "Name (A to Z) ▲",
                        FileSortOption.BY_NAME_DESC to "Name (Z to A) ▼",
                        FileSortOption.BY_SIZE_DESC to "Size (Largest first) ▼",
                        FileSortOption.BY_SIZE_ASC to "Size (Smallest first) ▲",
                        FileSortOption.BY_TYPE to "Type"
                    ).forEach { (option, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSortChange(option, applyToAllFolders)
                                    showSortDialog = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = label,
                                color = if (currentSort == option) TealPrimary else TextPrimary,
                                fontWeight = if (currentSort == option) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 16.sp
                            )
                            RadioButton(
                                selected = currentSort == option,
                                onClick = {
                                    onSortChange(option, applyToAllFolders)
                                    showSortDialog = false
                                },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = TealPrimary,
                                    unselectedColor = TextSecondary
                                )
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSortDialog = false }) {
                    Text("CANCEL", color = TealPrimary)
                }
            },
            containerColor = Color(0xFF2C2C2C)
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF212121),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        dragHandle = {
            Surface(
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .size(width = 36.dp, height = 4.dp),
                color = Color(0xFF555555),
                shape = RoundedCornerShape(2.dp)
            ) {}
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // Apply to all folders (Top row matching Screenshot 1:26)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { applyToAllFolders = !applyToAllFolders }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Apply to all folders",
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Normal
                )
                Checkbox(
                    checked = applyToAllFolders,
                    onCheckedChange = { applyToAllFolders = it },
                    colors = CheckboxDefaults.colors(
                        checkedColor = TealPrimary,
                        uncheckedColor = TextSecondary,
                        checkmarkColor = PureBlack
                    )
                )
            }

            HorizontalDivider(color = Color(0xFF333333), thickness = 1.dp)
            Spacer(modifier = Modifier.height(14.dp))

            // VIEW Section (Matching Screenshot 1:26)
            Text(
                text = "View",
                color = TealAccent,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))

            // View modes row with Radio Indicator Icons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // List Mode
                ViewModeRadioItem(
                    icon = Icons.Default.FormatListBulleted,
                    label = "List",
                    isSelected = currentViewMode == ViewMode.LIST,
                    onClick = { onViewModeChange(ViewMode.LIST, applyToAllFolders) }
                )

                // Grid Mode
                ViewModeRadioItem(
                    icon = Icons.Default.GridView,
                    label = "Grid",
                    isSelected = currentViewMode == ViewMode.GRID,
                    onClick = { onViewModeChange(ViewMode.GRID, applyToAllFolders) }
                )

                // Detailed List Mode
                ViewModeRadioItem(
                    icon = Icons.Default.Subject,
                    label = "Detailed",
                    isSelected = currentViewMode == ViewMode.DETAILED_LIST,
                    onClick = { onViewModeChange(ViewMode.DETAILED_LIST, applyToAllFolders) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = Color(0xFF333333), thickness = 1.dp)
            Spacer(modifier = Modifier.height(14.dp))

            // SORT Section (Matching Screenshot 1:26)
            Text(
                text = "Sort",
                color = TealAccent,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showSortDialog = true }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = sortLabel,
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Normal
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = Color(0xFF333333), thickness = 1.dp)
            Spacer(modifier = Modifier.height(14.dp))

            // OTHERS Section (Matching Screenshot 1:26)
            Text(
                text = "Others",
                color = TealAccent,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onShowHiddenFilesChange(!showHiddenFiles, applyToAllFolders) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Show hidden files",
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Normal
                )
                Checkbox(
                    checked = showHiddenFiles,
                    onCheckedChange = { onShowHiddenFilesChange(it, applyToAllFolders) },
                    colors = CheckboxDefaults.colors(
                        checkedColor = TealPrimary,
                        uncheckedColor = TextSecondary,
                        checkmarkColor = PureBlack
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ViewModeRadioItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clickable { onClick() }
            .padding(vertical = 4.dp)
    ) {
        // Radio dot
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(if (isSelected) TealPrimary else Color.Transparent)
                .padding(2.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF212121))
                )
            } else {
                Surface(
                    shape = CircleShape,
                    color = Color.Transparent,
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, TextSecondary),
                    modifier = Modifier.size(18.dp)
                ) {}
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isSelected) TealPrimary else TextSecondary,
            modifier = Modifier.size(22.dp)
        )
    }
}
