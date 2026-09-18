package com.antigravity.filemanager.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.filemanager.domain.model.CategorySummary
import com.antigravity.filemanager.domain.model.CategoryType
import com.antigravity.filemanager.presentation.theme.*

@Composable
fun CategoryCard(
    summary: CategorySummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Icon Container Box
        Box(
            modifier = Modifier
                .size(66.dp)
                .clip(CircleShape)
                .background(getCategoryBgColor(summary.type)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = getCategoryIcon(summary.type),
                contentDescription = summary.title,
                tint = getCategoryIconTint(summary.type),
                modifier = Modifier.size(34.dp)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Title
        Text(
            text = summary.title,
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // Subtitle (size and count)
        val displaySub = summary.formattedDisplay
        if (displaySub.isNotEmpty()) {
            Text(
                text = displaySub,
                color = TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

private fun getCategoryIcon(type: CategoryType): ImageVector = when (type) {
    CategoryType.MAIN_STORAGE -> Icons.Default.Storage
    CategoryType.DOWNLOADS -> Icons.Default.FolderZip
    CategoryType.STORAGE_ANALYSIS -> Icons.Default.PieChart
    CategoryType.IMAGES -> Icons.Default.PhotoLibrary
    CategoryType.AUDIO -> Icons.Default.MusicNote
    CategoryType.VIDEOS -> Icons.Default.Movie
    CategoryType.DOCUMENTS -> Icons.Default.Description
    CategoryType.CLOUD -> Icons.Default.Cloud
    CategoryType.ACCESS_FROM_NETWORK -> Icons.Default.Devices
    CategoryType.RECYCLE_BIN -> Icons.Default.DeleteOutline
}

private fun getCategoryBgColor(type: CategoryType): Color =
    CategoryThemePalette.current.getCategoryBg(type)

private fun getCategoryBorderColor(type: CategoryType): Color =
    CategoryThemePalette.current.getCategoryBorder(type)

private fun getCategoryIconTint(type: CategoryType): Color =
    CategoryThemePalette.current.getCategoryTint(type)
