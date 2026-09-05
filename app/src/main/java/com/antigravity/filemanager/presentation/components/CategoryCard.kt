package com.antigravity.filemanager.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
            .padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Icon Container Box
        Box(
            modifier = Modifier
                .size(78.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(getCategoryBgColor(summary.type))
                .border(1.2.dp, getCategoryBorderColor(summary.type), RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = getCategoryIcon(summary.type),
                contentDescription = summary.title,
                tint = getCategoryIconTint(summary.type),
                modifier = Modifier.size(44.dp)
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
    CategoryType.DOCUMENTS -> Icons.Default.Article
    CategoryType.NEW_FILES -> Icons.Default.NewReleases
    CategoryType.CLOUD -> Icons.Default.Cloud
    CategoryType.ACCESS_FROM_NETWORK -> Icons.Default.Devices
    CategoryType.RECYCLE_BIN -> Icons.Default.DeleteOutline
}

private fun getCategoryBgColor(type: CategoryType): Color = when (type) {
    CategoryType.MAIN_STORAGE -> Color(0xFF2E3440)
    CategoryType.DOWNLOADS -> Color(0xFF423826)
    CategoryType.STORAGE_ANALYSIS -> Color(0xFF232D36)
    CategoryType.IMAGES -> Color(0xFF3B2544)
    CategoryType.AUDIO -> Color(0xFF1E3A3A)
    CategoryType.VIDEOS -> Color(0xFF452226)
    CategoryType.DOCUMENTS -> Color(0xFF1E2F47)
    CategoryType.NEW_FILES -> Color(0xFF203524)
    CategoryType.CLOUD -> Color(0xFF1B384F)
    CategoryType.ACCESS_FROM_NETWORK -> Color(0xFF263238)
    CategoryType.RECYCLE_BIN -> Color(0xFF2C2438)
}

private fun getCategoryBorderColor(type: CategoryType): Color = when (type) {
    CategoryType.MAIN_STORAGE -> Color(0xFF4C566A)
    CategoryType.DOWNLOADS -> Color(0xFFFFB74D)
    CategoryType.STORAGE_ANALYSIS -> Color(0xFF78909C)
    CategoryType.IMAGES -> Color(0xFFBA68C8)
    CategoryType.AUDIO -> Color(0xFF4DB6AC)
    CategoryType.VIDEOS -> Color(0xFFEF5350)
    CategoryType.DOCUMENTS -> Color(0xFF42A5F5)
    CategoryType.NEW_FILES -> Color(0xFF66BB6A)
    CategoryType.CLOUD -> Color(0xFF29B6F6)
    CategoryType.ACCESS_FROM_NETWORK -> Color(0xFF78909C)
    CategoryType.RECYCLE_BIN -> Color(0xFF9575CD)
}

private fun getCategoryIconTint(type: CategoryType): Color = when (type) {
    CategoryType.MAIN_STORAGE -> Color(0xFFECEFF4)
    CategoryType.DOWNLOADS -> Color(0xFFFFCC80)
    CategoryType.STORAGE_ANALYSIS -> Color(0xFFB0BEC5)
    CategoryType.IMAGES -> Color(0xFFE1BEE7)
    CategoryType.AUDIO -> Color(0xFF80CBC4)
    CategoryType.VIDEOS -> Color(0xFFFFCDD2)
    CategoryType.DOCUMENTS -> Color(0xFF90CAF9)
    CategoryType.NEW_FILES -> Color(0xFFA5D6A7)
    CategoryType.CLOUD -> Color(0xFF81D4FA)
    CategoryType.ACCESS_FROM_NETWORK -> Color(0xFFB0BEC5)
    CategoryType.RECYCLE_BIN -> Color(0xFFD1C4E9)
}
