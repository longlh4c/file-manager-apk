package com.antigravity.filemanager.presentation.recent

import com.antigravity.filemanager.domain.model.FileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class RecentFilesTest {

    private fun file(name: String, time: Long = 0L) =
        FileItem(id = "/f/$name", name = name, path = "/f/$name", lastModified = time, extension = name.substringAfterLast('.', ""))

    @Test
    fun typeFilterSortsFilesByExtension() {
        assertTrue(RecentTypeFilter.IMAGES.matches(file("a.JPG")))
        assertTrue(RecentTypeFilter.VIDEOS.matches(file("clip.mp4")))
        assertTrue(RecentTypeFilter.DOCUMENTS.matches(file("report.pdf")))
        assertTrue(RecentTypeFilter.OTHER.matches(file("app.apk")))
        assertFalse(RecentTypeFilter.AUDIO.matches(file("a.jpg")))
        assertTrue(RecentTypeFilter.ALL.matches(file("anything.xyz")))
    }

    @Test
    fun filesAreGroupedByDayNewestFirst() {
        val now = Calendar.getInstance().apply { set(2026, Calendar.SEPTEMBER, 27, 15, 0, 0) }.timeInMillis
        val hour = 60 * 60 * 1000L
        val files = listOf(
            file("a.jpg", now - hour),          // today
            file("b.jpg", now - 20 * hour),     // yesterday (27th 15:00 - 20h = 26th 19:00)
            file("c.jpg", now - 3 * 24 * hour), // this week
            file("d.jpg", now - 30 * 24 * hour) // older
        )

        val groups = groupByDay(files, now)

        assertEquals(listOf("Today", "Yesterday", "This week", "Older"), groups.map { it.first })
        assertEquals(listOf("a.jpg"), groups[0].second.map { it.name })
        assertEquals(listOf("d.jpg"), groups[3].second.map { it.name })
    }
}
