package com.antigravity.filemanager.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTextTest {

    @Test
    fun unaccentedQueryFindsAccentedName() {
        assertTrue(matchesSearch("Thảo", "Thao"))
        assertTrue(matchesSearch("Ảnh cưới Thảo.jpg", "anh cuoi"))
        assertTrue(matchesSearch("Đà Nẵng 2026", "da nang"))
    }

    @Test
    fun accentedQueryStillMatchesAndCaseIsIgnored() {
        assertTrue(matchesSearch("THẢO", "thảo"))
        assertTrue(matchesSearch("Thao", "Thảo"))
    }

    @Test
    fun differentWordsDoNotMatch() {
        assertFalse(matchesSearch("Thảo", "Thanh"))
    }
}
