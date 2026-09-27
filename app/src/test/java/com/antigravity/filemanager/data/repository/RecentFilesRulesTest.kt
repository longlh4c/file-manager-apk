package com.antigravity.filemanager.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentFilesRulesTest {

    @Test
    fun onlyTheLatestImageAndVideoOfEachFolderAreKept() {
        val byRecency = listOf(
            "/sdcard/Pics/c.jpg",      // latest image in Pics: kept
            "/sdcard/Docs/report.pdf", // not media: kept
            "/sdcard/Pics/b.png",      // older image in Pics: dropped
            "/sdcard/Pics/clip.mp4",   // latest video in Pics: kept
            "/sdcard/Pics/Sub/d.jpg",  // another folder: kept
            "/sdcard/Docs/notes.txt",  // not media: kept
            "/sdcard/Pics/a.JPG",      // older image in Pics: dropped
            "/sdcard/Pics/old.mov"     // older video in Pics: dropped
        )

        val kept = RecentFilesRepository.keepLatestMediaPerFolder(byRecency) { it }

        assertEquals(
            listOf("/sdcard/Pics/c.jpg", "/sdcard/Docs/report.pdf", "/sdcard/Pics/clip.mp4", "/sdcard/Pics/Sub/d.jpg", "/sdcard/Docs/notes.txt"),
            kept
        )
    }
}
