package com.antigravity.filemanager.utils

import com.antigravity.filemanager.domain.model.FileItem
import com.antigravity.filemanager.domain.model.FileSortOption
import org.junit.Assert.assertEquals
import org.junit.Test

class SortUtilsTest {

    private val folderB = FileItem(id = "1", name = "beta_dir", path = "/beta_dir", isDirectory = true, lastModified = 1000L, size = 0L)
    private val folderA = FileItem(id = "2", name = "alpha_dir", path = "/alpha_dir", isDirectory = true, lastModified = 2000L, size = 0L)
    private val fileZ = FileItem(id = "3", name = "zebra.png", path = "/zebra.png", isDirectory = false, lastModified = 3000L, size = 100L, extension = "png")
    private val fileA = FileItem(id = "4", name = "apple.jpg", path = "/apple.jpg", isDirectory = false, lastModified = 1500L, size = 500L, extension = "jpg")
    private val fileM = FileItem(id = "5", name = "mango.txt", path = "/mango.txt", isDirectory = false, lastModified = 2500L, size = 200L, extension = "txt")

    private val allItems = listOf(fileZ, folderB, fileA, folderA, fileM)

    @Test
    fun sortByNameAscendingPlacesFoldersFirstThenAlphabetical() {
        val sorted = sortFileItems(allItems, FileSortOption.BY_NAME_ASC)
        val names = sorted.map { it.name }
        assertEquals(listOf("alpha_dir", "beta_dir", "apple.jpg", "mango.txt", "zebra.png"), names)
    }

    @Test
    fun sortByNameDescendingPlacesFoldersFirstThenReverseAlphabetical() {
        val sorted = sortFileItems(allItems, FileSortOption.BY_NAME_DESC)
        val names = sorted.map { it.name }
        assertEquals(listOf("beta_dir", "alpha_dir", "zebra.png", "mango.txt", "apple.jpg"), names)
    }

    @Test
    fun sortByDateDescendingPlacesFoldersFirstThenNewestFirst() {
        val sorted = sortFileItems(allItems, FileSortOption.BY_DATE_DESC)
        val names = sorted.map { it.name }
        // Folders: alpha_dir (2000), beta_dir (1000)
        // Files: zebra.png (3000), mango.txt (2500), apple.jpg (1500)
        assertEquals(listOf("alpha_dir", "beta_dir", "zebra.png", "mango.txt", "apple.jpg"), names)
    }

    @Test
    fun sortByDateAscendingPlacesFoldersFirstThenOldestFirst() {
        val sorted = sortFileItems(allItems, FileSortOption.BY_DATE_ASC)
        val names = sorted.map { it.name }
        // Folders: beta_dir (1000), alpha_dir (2000)
        // Files: apple.jpg (1500), mango.txt (2500), zebra.png (3000)
        assertEquals(listOf("beta_dir", "alpha_dir", "apple.jpg", "mango.txt", "zebra.png"), names)
    }

    @Test
    fun sortBySizeDescendingPlacesFoldersFirstThenLargestFirst() {
        val sorted = sortFileItems(allItems, FileSortOption.BY_SIZE_DESC)
        val names = sorted.map { it.name }
        // Folders: both size 0, tie-breaker name: alpha_dir, beta_dir
        // Files: apple.jpg (500), mango.txt (200), zebra.png (100)
        assertEquals(listOf("alpha_dir", "beta_dir", "apple.jpg", "mango.txt", "zebra.png"), names)
    }

    @Test
    fun sortByTypePlacesFoldersFirstThenExtensionAlphabetical() {
        val sorted = sortFileItems(allItems, FileSortOption.BY_TYPE)
        val names = sorted.map { it.name }
        // Folders: alpha_dir, beta_dir
        // Files: apple.jpg (.jpg), zebra.png (.png), mango.txt (.txt)
        assertEquals(listOf("alpha_dir", "beta_dir", "apple.jpg", "zebra.png", "mango.txt"), names)
    }
}
