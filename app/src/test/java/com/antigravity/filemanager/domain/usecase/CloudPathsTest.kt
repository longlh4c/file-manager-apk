package com.antigravity.filemanager.domain.usecase

import com.antigravity.filemanager.domain.model.CloudProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudPathsTest {

    @Test
    fun isInCloudFolderMatchesDirectChildrenOnly() {
        assertTrue(isInCloudFolder("/a.txt", "/"))
        assertTrue(isInCloudFolder("/a.txt", ""))
        assertTrue(isInCloudFolder("/Docs/a.txt", "/Docs"))
        assertTrue(isInCloudFolder("/Docs/a.txt", "/Docs/"))
        assertFalse(isInCloudFolder("/Docs/sub/a.txt", "/Docs"))
        assertFalse(isInCloudFolder("/Docs/a.txt", "/"))
    }

    @Test
    fun cloudWriteDirSendsOnlyGoogleDriveRootToMyDrive() {
        assertEquals("/My Drive", cloudWriteDir(CloudProvider.GOOGLE_DRIVE, "/"))
        assertEquals("/My Drive", cloudWriteDir(CloudProvider.GOOGLE_DRIVE, ""))
        assertEquals("/My Drive/Docs", cloudWriteDir(CloudProvider.GOOGLE_DRIVE, "/My Drive/Docs"))
        assertEquals("/", cloudWriteDir(CloudProvider.DROPBOX, "/"))
        assertEquals("/", cloudWriteDir(CloudProvider.MEGA, "/"))
        assertEquals("/", cloudWriteDir(null, "/"))
    }

    @Test
    fun isCloudFolderOrInsideCoversTheFolderAndItsSubtreeButNotSiblings() {
        assertTrue(isCloudFolderOrInside("/X", "/X"))
        assertTrue(isCloudFolderOrInside("/X/Y/Z", "/X"))
        assertTrue(isCloudFolderOrInside("/X/", "/X"))
        assertFalse(isCloudFolderOrInside("/X 2", "/X"))
        assertFalse(isCloudFolderOrInside("/XY", "/X"))
        assertFalse(isCloudFolderOrInside("/", "/X"))
    }
}
