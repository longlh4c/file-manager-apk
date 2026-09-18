package com.antigravity.filemanager.presentation.theme

import androidx.compose.ui.graphics.Color
import com.antigravity.filemanager.domain.model.CategoryType

enum class CategoryThemePalette(val displayName: String) {
    // Option 1: Hiện đại & Nổi bật (Vibrant & High-Contrast)
    VIBRANT("Vibrant"),

    // Option 2: Pastel Dịu Mắt (Soft & Minimal Pastel)
    PASTEL("Pastel"),

    // Option 3: Đồng bộ tông Teal (Monochrome Teal)
    TEAL("Teal");

    fun getCategoryBg(type: CategoryType): Color = when (this) {
        VIBRANT -> when (type) {
            CategoryType.MAIN_STORAGE -> Color(0xFF1A2536)
            CategoryType.DOWNLOADS -> Color(0xFF362512)
            CategoryType.STORAGE_ANALYSIS -> Color(0xFF122A27)
            CategoryType.IMAGES -> Color(0xFF31183D)
            CategoryType.AUDIO -> Color(0xFF0D282B)
            CategoryType.VIDEOS -> Color(0xFF38161B)
            CategoryType.DOCUMENTS -> Color(0xFF16284A)
            CategoryType.CLOUD -> Color(0xFF142B38)
            CategoryType.ACCESS_FROM_NETWORK -> Color(0xFF10262B)
            CategoryType.RECYCLE_BIN -> Color(0xFF251C38)
        }
        PASTEL -> when (type) {
            CategoryType.MAIN_STORAGE -> Color(0xFFB0BEC5)        // Slate Grey Pastel
            CategoryType.DOWNLOADS -> Color(0xFFFFE082)           // Amber Peach Pastel
            CategoryType.STORAGE_ANALYSIS -> Color(0xFF80CBC4)    // Mint Teal Pastel
            CategoryType.IMAGES -> Color(0xFFCE93D8)              // Lavender Purple Pastel
            CategoryType.AUDIO -> Color(0xFF80DEEA)               // Aqua Cyan Pastel
            CategoryType.VIDEOS -> Color(0xFFFFAB91)              // Soft Coral Pastel
            CategoryType.DOCUMENTS -> Color(0xFF90CAF9)           // Sky Blue Pastel
            CategoryType.CLOUD -> Color(0xFF81D4FA)               // Light Blue Pastel
            CategoryType.ACCESS_FROM_NETWORK -> Color(0xFF80CBC4) // Teal Pastel
            CategoryType.RECYCLE_BIN -> Color(0xFFB39DDB)         // Lilac Pastel
        }
        TEAL -> when (type) {
            CategoryType.MAIN_STORAGE -> Color(0xFF112225)
            CategoryType.DOWNLOADS -> Color(0xFF11262B)
            CategoryType.STORAGE_ANALYSIS -> Color(0xFF0E2328)
            CategoryType.IMAGES -> Color(0xFF12282D)
            CategoryType.AUDIO -> Color(0xFF0F2626)
            CategoryType.VIDEOS -> Color(0xFF10282C)
            CategoryType.DOCUMENTS -> Color(0xFF142B30)
            CategoryType.CLOUD -> Color(0xFF10272E)
            CategoryType.ACCESS_FROM_NETWORK -> Color(0xFF0E262A)
            CategoryType.RECYCLE_BIN -> Color(0xFF132226)
        }
    }

    fun getCategoryBorder(type: CategoryType): Color = when (this) {
        VIBRANT -> when (type) {
            CategoryType.MAIN_STORAGE -> Color(0xFF3B82F6)
            CategoryType.DOWNLOADS -> Color(0xFFF59E0B)
            CategoryType.STORAGE_ANALYSIS -> Color(0xFF10B981)
            CategoryType.IMAGES -> Color(0xFFC084FC)
            CategoryType.AUDIO -> Color(0xFF14B8A6)
            CategoryType.VIDEOS -> Color(0xFFF43F5E)
            CategoryType.DOCUMENTS -> Color(0xFF38BDF8)
            CategoryType.CLOUD -> Color(0xFF0EA5E9)
            CategoryType.ACCESS_FROM_NETWORK -> Color(0xFF1DE9B6)
            CategoryType.RECYCLE_BIN -> Color(0xFFA855F7)
        }
        PASTEL -> Color.Transparent
        TEAL -> when (type) {
            CategoryType.MAIN_STORAGE -> Color(0xFF26A69A)
            CategoryType.DOWNLOADS -> Color(0xFF00B4D8)
            CategoryType.STORAGE_ANALYSIS -> Color(0xFF48CAE4)
            CategoryType.IMAGES -> Color(0xFF0096C7)
            CategoryType.AUDIO -> Color(0xFF4DB6AC)
            CategoryType.VIDEOS -> Color(0xFF0077B6)
            CategoryType.DOCUMENTS -> Color(0xFF023E8A)
            CategoryType.CLOUD -> Color(0xFF0096C7)
            CategoryType.ACCESS_FROM_NETWORK -> Color(0xFF00F5D4)
            CategoryType.RECYCLE_BIN -> Color(0xFF52B788)
        }
    }

    fun getCategoryTint(type: CategoryType): Color = when (this) {
        VIBRANT -> when (type) {
            CategoryType.MAIN_STORAGE -> Color(0xFF60A5FA)
            CategoryType.DOWNLOADS -> Color(0xFFFBBF24)
            CategoryType.STORAGE_ANALYSIS -> Color(0xFF34D399)
            CategoryType.IMAGES -> Color(0xFFE879F9)
            CategoryType.AUDIO -> Color(0xFF2DD4BF)
            CategoryType.VIDEOS -> Color(0xFFFB7185)
            CategoryType.DOCUMENTS -> Color(0xFF7DD3FC)
            CategoryType.CLOUD -> Color(0xFF38BDF8)
            CategoryType.ACCESS_FROM_NETWORK -> Color(0xFF64FFDA)
            CategoryType.RECYCLE_BIN -> Color(0xFFC084FC)
        }
        PASTEL -> Color(0xFF1C1E24) // Clean dark icon on pastel circle badge
        TEAL -> when (type) {
            CategoryType.MAIN_STORAGE -> Color(0xFF80CBC4)
            CategoryType.DOWNLOADS -> Color(0xFF90E0EF)
            CategoryType.STORAGE_ANALYSIS -> Color(0xFFADE8F4)
            CategoryType.IMAGES -> Color(0xFF48CAE4)
            CategoryType.AUDIO -> Color(0xFF80CBC4)
            CategoryType.VIDEOS -> Color(0xFF00B4D8)
            CategoryType.DOCUMENTS -> Color(0xFF0096C7)
            CategoryType.CLOUD -> Color(0xFF90E0EF)
            CategoryType.ACCESS_FROM_NETWORK -> Color(0xFF70F8E3)
            CategoryType.RECYCLE_BIN -> Color(0xFF74C69D)
        }
    }

    fun getFtpServerBg(): Color = when (this) {
        VIBRANT -> Color(0xFF10262B)
        PASTEL -> Color(0xFF162224)
        TEAL -> Color(0xFF0E262A)
    }

    fun getFtpServerBorder(): Color = when (this) {
        VIBRANT -> Color(0xFF1DE9B6)
        PASTEL -> Color(0xFF4DB6AC)
        TEAL -> Color(0xFF00F5D4)
    }

    fun getFtpServerIcon(): Color = when (this) {
        VIBRANT -> Color(0xFF64FFDA)
        PASTEL -> Color(0xFF80CBC4)
        TEAL -> Color(0xFF70F8E3)
    }

    fun getFtpCardBorder(): Color = when (this) {
        VIBRANT -> Color(0xFF1E3D45)
        PASTEL -> Color(0xFF4DB6AC)
        TEAL -> Color(0xFF1B383E)
    }

    fun getFtpButtonBg(isRunning: Boolean): Color = when (this) {
        VIBRANT -> if (isRunning) Color(0xFFEF5350) else Color(0xFF00E676)
        PASTEL -> if (isRunning) Color(0xFFFFAB91) else Color(0xFF4DB6AC)
        TEAL -> if (isRunning) Color(0xFFEF5350) else Color(0xFF00F5D4)
    }

    @Suppress("UNUSED_PARAMETER")
    fun getFtpButtonText(isRunning: Boolean): Color = Color(0xFF121212)

    companion object {
        var current: CategoryThemePalette = PASTEL
    }
}
