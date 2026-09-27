package com.antigravity.filemanager.presentation.viewers

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.antigravity.filemanager.domain.model.FileItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The image or video a viewer is showing, so the folder it was opened from can scroll to it on
 * the way back: swiping through twenty photos and pressing Back used to land on the one first
 * tapped, far from the one just looked at. Holds a local file's absolute path or a cloud item's
 * remote path — the same `path` the folder's FileItems carry.
 */
object LastViewedMedia {
    private val _path = MutableStateFlow<String?>(null)
    val path: StateFlow<String?> = _path.asStateFlow()

    fun set(path: String) {
        _path.value = path
    }

    /** Called once a folder has scrolled to [path], so it isn't repeated. */
    fun consume(path: String) {
        _path.compareAndSet(path, null)
    }
}

/** Scrolls [state] to the last viewed media item when it is in [files] but not fully on screen. */
@Composable
fun ScrollToLastViewed(files: List<FileItem>, state: LazyListState, isLoading: Boolean = false) {
    val viewed by LastViewedMedia.path.collectAsState()
    LaunchedEffect(viewed, files, isLoading) {
        // Once loaded: a folder restoring its own saved position after loading would undo it.
        if (isLoading) return@LaunchedEffect
        val path = viewed ?: return@LaunchedEffect
        val index = files.indexOfFirst { it.path == path }
        if (index < 0) return@LaunchedEffect
        val info = state.layoutInfo
        val shown = info.visibleItemsInfo.firstOrNull { it.index == index }
        val fullyShown = shown != null && shown.offset >= info.viewportStartOffset &&
            shown.offset + shown.size <= info.viewportEndOffset
        if (!fullyShown) {
            // Centered rather than at the very top, so what was around it stays in view too.
            state.scrollToItem((index - info.visibleItemsInfo.size / 2).coerceAtLeast(0))
        }
        LastViewedMedia.consume(path)
    }
}

/** [ScrollToLastViewed] for a grid. */
@Composable
fun ScrollToLastViewed(files: List<FileItem>, state: LazyGridState, isLoading: Boolean = false) {
    val viewed by LastViewedMedia.path.collectAsState()
    LaunchedEffect(viewed, files, isLoading) {
        // Once loaded: a folder restoring its own saved position after loading would undo it.
        if (isLoading) return@LaunchedEffect
        val path = viewed ?: return@LaunchedEffect
        val index = files.indexOfFirst { it.path == path }
        if (index < 0) return@LaunchedEffect
        val info = state.layoutInfo
        val shown = info.visibleItemsInfo.firstOrNull { it.index == index }
        val fullyShown = shown != null && shown.offset.y >= info.viewportStartOffset &&
            shown.offset.y + shown.size.height <= info.viewportEndOffset
        if (!fullyShown) {
            state.scrollToItem((index - info.visibleItemsInfo.size / 2).coerceAtLeast(0))
        }
        LastViewedMedia.consume(path)
    }
}
