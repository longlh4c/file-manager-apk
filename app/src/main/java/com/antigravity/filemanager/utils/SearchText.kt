package com.antigravity.filemanager.utils

import java.text.Normalizer
import java.util.Locale

private val combiningMarks = Regex("\\p{Mn}+")

/** [text] lowercased with its accents removed ("Thảo" -> "thao", "Đà Nẵng" -> "da nang"), so a
 * search typed without accents still finds accented names. */
fun foldForSearch(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(combiningMarks, "")
        // Đ/đ is its own letter rather than D plus a mark, so NFD leaves it alone.
        .replace('đ', 'd').replace('Đ', 'D')
        .lowercase(Locale.ROOT)

/** True when [name] contains [query], ignoring case and accents on both sides. */
fun matchesSearch(name: String, query: String): Boolean =
    foldForSearch(name).contains(foldForSearch(query))
