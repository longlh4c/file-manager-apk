package com.antigravity.filemanager.utils

import com.antigravity.filemanager.domain.model.FileItem
import com.antigravity.filemanager.domain.model.FileSortOption
import java.util.Locale

/**
 * Sorts a list of [FileItem]s according to the specified [FileSortOption].
 * Folders (directories) are placed first, and all items are sorted with a case-insensitive
 * secondary tie-breaker on file name to guarantee a stable sort order.
 */
fun sortFileItems(files: List<FileItem>, sortOption: FileSortOption): List<FileItem> {
    if (files.size <= 1) return files
    val locale = Locale.getDefault()
    return when (sortOption) {
        FileSortOption.BY_NAME_ASC -> files.sortedWith(
            compareBy<FileItem> { !it.isDirectory }.thenBy { it.name.lowercase(locale) }
        )
        FileSortOption.BY_NAME_DESC -> files.sortedWith(
            compareBy<FileItem> { !it.isDirectory }.thenByDescending { it.name.lowercase(locale) }
        )
        FileSortOption.BY_DATE_DESC -> files.sortedWith(
            compareBy<FileItem> { !it.isDirectory }.thenByDescending { it.lastModified }.thenBy { it.name.lowercase(locale) }
        )
        FileSortOption.BY_DATE_ASC -> files.sortedWith(
            compareBy<FileItem> { !it.isDirectory }.thenBy { it.lastModified }.thenBy { it.name.lowercase(locale) }
        )
        FileSortOption.BY_SIZE_DESC -> files.sortedWith(
            compareBy<FileItem> { !it.isDirectory }.thenByDescending { it.size }.thenBy { it.name.lowercase(locale) }
        )
        FileSortOption.BY_SIZE_ASC -> files.sortedWith(
            compareBy<FileItem> { !it.isDirectory }.thenBy { it.size }.thenBy { it.name.lowercase(locale) }
        )
        FileSortOption.BY_TYPE -> files.sortedWith(
            compareBy<FileItem> { !it.isDirectory }
                .thenBy { it.extension.lowercase(locale) }
                .thenBy { it.name.lowercase(locale) }
        )
    }
}
