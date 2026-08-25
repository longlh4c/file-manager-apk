package com.antigravity.filemanager.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.filemanager.domain.model.CategoryType
import com.antigravity.filemanager.presentation.theme.DarkBackground
import com.antigravity.filemanager.presentation.theme.TextPrimary
import com.antigravity.filemanager.presentation.theme.TextSecondary

@Composable
fun BreadcrumbBar(
    categoryType: CategoryType? = null,
    pathSegments: List<String> = emptyList(),
    storageUsedBadge: String? = null,
    onHomeClick: () -> Unit = {},
    onCategoryClick: () -> Unit = {},
    onSegmentClick: (Int) -> Unit = {},
    onStorageBadgeClick: (() -> Unit)? = null
) {
    val scrollState = rememberScrollState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkBackground)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(scrollState),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Red Home Icon (matching screenshots 4, 5, 6, 000552)
            Icon(
                imageVector = Icons.Default.Home,
                contentDescription = "Home",
                tint = Color(0xFFEF5350),
                modifier = Modifier
                    .size(28.dp)
                    .clickable { onHomeClick() }
            )

            if (categoryType != null) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Separator",
                    tint = TextSecondary,
                    modifier = Modifier
                        .padding(horizontal = 6.dp)
                        .size(20.dp)
                )

                val (icon, tint) = when (categoryType) {
                    CategoryType.IMAGES -> Pair(Icons.Default.PhotoLibrary, Color(0xFFBA68C8))
                    CategoryType.AUDIO -> Pair(Icons.Default.MusicNote, Color(0xFF4DB6AC))
                    CategoryType.VIDEOS -> Pair(Icons.Default.Movie, Color(0xFFE57373))
                    CategoryType.DOCUMENTS -> Pair(Icons.Default.Description, Color(0xFF64B5F6))
                    CategoryType.DOWNLOADS -> Pair(Icons.Default.Download, Color(0xFFFFB74D))
                    CategoryType.MAIN_STORAGE -> Pair(Icons.Default.Storage, Color(0xFFCFD8DC))
                    else -> Pair(Icons.Default.Folder, Color(0xFFFFB74D))
                }

                Icon(
                    imageVector = icon,
                    contentDescription = "Category",
                    tint = tint,
                    modifier = Modifier
                        .size(26.dp)
                        .clickable { onCategoryClick() }
                )
            }

            pathSegments.forEachIndexed { index, segment ->
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Separator",
                    tint = TextSecondary,
                    modifier = Modifier
                        .padding(horizontal = 6.dp)
                        .size(20.dp)
                )

                Text(
                    text = segment,
                    color = if (index == pathSegments.lastIndex) TextPrimary else TextSecondary,
                    fontSize = 15.sp,
                    modifier = Modifier.clickable { onSegmentClick(index) }
                )
            }
        }

        // 94% USED button on the right (matching Screenshot 000552)
        if (storageUsedBadge != null && onStorageBadgeClick != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .border(1.dp, Color(0xFF555555), RoundedCornerShape(4.dp))
                    .background(Color(0xFF222222))
                    .clickable { onStorageBadgeClick() }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PieChart,
                    contentDescription = "Storage used",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = storageUsedBadge,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
