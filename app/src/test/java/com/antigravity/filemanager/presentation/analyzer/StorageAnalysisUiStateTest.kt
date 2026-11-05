package com.antigravity.filemanager.presentation.analyzer

import com.antigravity.filemanager.domain.model.DuplicateGroup
import com.antigravity.filemanager.domain.model.FileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class StorageAnalysisUiStateTest {

    @Test
    fun defaultUiStateDoesNotContainMockData() {
        val defaultState = StorageAnalysisUiState()

        // Verify volume info is zeroed by default instead of 241 GB / 256 GB (which showed 91%)
        assertEquals(0L, defaultState.data.volumeInfo.totalBytes)
        assertEquals(0L, defaultState.data.volumeInfo.usedBytes)
        assertEquals(0L, defaultState.data.volumeInfo.freeBytes)

        // Verify other properties are initial defaults
        assertFalse(defaultState.isLoading)
        assertNull(defaultState.toastMessage)
        assertEquals(0L, defaultState.data.breakdown.imagesBytes)
        assertEquals(0L, defaultState.data.breakdown.videosBytes)
        assertEquals(0L, defaultState.data.recycleBinBytes)
        assertEquals(emptyList<com.antigravity.filemanager.domain.model.LargeFileItem>(), defaultState.data.largeFiles)
        assertEquals(emptyList<DuplicateGroup>(), defaultState.data.duplicateFileGroups)
    }
}
