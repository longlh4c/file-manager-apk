package com.antigravity.filemanager.presentation.components

/** Default filename offered in the "Compress to Zip" dialog:
 * - exactly one item selected (a folder or a single file) → that item's own name
 * - more than one item selected → the generic "Archive"
 * Either way the dialog itself (via TextInputDialog's selectNameWithoutExtension) highlights
 * everything before the ".zip" this appends, so retyping never has to first delete it. */
fun defaultArchiveBaseName(selectedCount: Int, singleSelectedName: String?): String {
    val rawName = if (selectedCount == 1 && singleSelectedName != null) singleSelectedName else "Archive"
    val dotIndex = rawName.lastIndexOf('.')
    return if (dotIndex > 0) rawName.substring(0, dotIndex) else rawName
}

fun defaultZipFileName(selectedCount: Int, singleSelectedName: String?): String {
    val baseName = if (selectedCount == 1 && singleSelectedName != null) singleSelectedName else "Archive"
    return "$baseName.zip"
}

