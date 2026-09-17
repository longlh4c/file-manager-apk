package com.antigravity.filemanager.presentation.components

/** Default filename offered in the "Compress to Zip" dialog:
 * - exactly one item selected (a folder or a single file) → that item's own name
 * - more than one item selected → the generic "Archive"
 * Either way the dialog itself (via TextInputDialog's selectNameWithoutExtension) highlights
 * everything before the ".zip" this appends, so retyping never has to first delete it. */
fun defaultZipFileName(selectedCount: Int, singleSelectedName: String?): String {
    val baseName = if (selectedCount == 1 && singleSelectedName != null) singleSelectedName else "Archive"
    return "$baseName.zip"
}
