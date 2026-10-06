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

    @Test
    fun googleWorkspaceDocsFormattingDisplaysDocTypeInsteadOfZeroBytes() {
        val sheetItem = com.antigravity.filemanager.domain.model.FileItem(
            id = "sheet123",
            name = "Báo giá màn hình Asus",
            path = "/My Drive/Báo giá màn hình Asus",
            size = 0L,
            mimeType = "application/vnd.google-apps.spreadsheet",
            extension = "gsheet",
            webViewLink = "https://docs.google.com/spreadsheets/d/sheet123/edit"
        )
        assertTrue(sheetItem.isGoogleWorkspaceDoc)
        assertEquals("Google Sheets", sheetItem.formattedSize)

        val docItem = com.antigravity.filemanager.domain.model.FileItem(
            id = "doc123",
            name = "Báo cáo tháng 8",
            path = "/My Drive/Báo cáo tháng 8",
            size = 0L,
            mimeType = "application/vnd.google-apps.document",
            extension = "gdoc"
        )
        assertTrue(docItem.isGoogleWorkspaceDoc)
        assertEquals("Google Docs", docItem.formattedSize)

        val shortcutItem = com.antigravity.filemanager.domain.model.FileItem(
            id = "sc123",
            name = "Shortcut to Sheet",
            path = "/My Drive/Shortcut to Sheet",
            size = 0L,
            mimeType = "application/vnd.google-apps.shortcut",
            extension = "gshortcut"
        )
        assertTrue(shortcutItem.isGoogleWorkspaceDoc)
        assertEquals("Shortcut", shortcutItem.formattedSize)

        // Normal file with 0B size should still show "0 B"
        val emptyTextFile = com.antigravity.filemanager.domain.model.FileItem(
            id = "txt123",
            name = "empty.txt",
            path = "/My Drive/empty.txt",
            size = 0L,
            mimeType = "text/plain",
            extension = "txt"
        )
        assertFalse(emptyTextFile.isGoogleWorkspaceDoc)
        assertEquals("0 B", emptyTextFile.formattedSize)
    }
}
