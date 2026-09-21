package com.antigravity.filemanager.presentation.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow

/**
 * Remembers where each folder was scrolled to (first visible item + offset), keyed by path.
 *
 * Folder browsers swap the contents of one list when navigating instead of opening a new screen,
 * so they share a single scroll state: scrolling deep into a subfolder and pressing back left the
 * parent list scrolled to the same depth. Keep one instance in the browser's ViewModel (it outlives
 * the screen, e.g. while an image viewer is open on top) and use [rememberFolderListState] /
 * [rememberFolderGridState] around the list.
 */
class FolderScrollMemory {
    private val positions = HashMap<String, Pair<Int, Int>>()

    @Synchronized fun get(path: String): Pair<Int, Int>? = positions[path]

    @Synchronized fun save(path: String, index: Int, offset: Int) {
        positions[path] = index to offset
    }

    /** Called when entering a folder, so it opens at the top rather than where it was last left. */
    @Synchronized fun forget(path: String) {
        positions.remove(path)
    }
}

/**
 * Wrap the list in `key(path) { ... }` and take its state from here: a fresh state per folder,
 * starting where that folder was last left (or at the top for one not visited), and continuously
 * saving its position. If the list is first shown while the folder is still loading (the rows on
 * screen belong to the previous folder), restoring waits until loading finishes.
 */
@Composable
fun rememberFolderListState(
    memory: FolderScrollMemory,
    path: String,
    isLoading: Boolean,
    record: Boolean = true
): LazyListState {
    val saved = remember(path) { if (isLoading) null else memory.get(path) }
    val state = rememberLazyListState(saved?.first ?: 0, saved?.second ?: 0)
    FolderScrollEffects(
        memory = memory,
        path = path,
        isLoading = isLoading,
        record = record,
        position = { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset },
        scrollTo = { index, offset -> state.scrollToItem(index, offset) }
    )
    return state
}

@Composable
fun rememberFolderGridState(
    memory: FolderScrollMemory,
    path: String,
    isLoading: Boolean,
    record: Boolean = true
): LazyGridState {
    val saved = remember(path) { if (isLoading) null else memory.get(path) }
    val state = rememberLazyGridState(saved?.first ?: 0, saved?.second ?: 0)
    FolderScrollEffects(
        memory = memory,
        path = path,
        isLoading = isLoading,
        record = record,
        position = { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset },
        scrollTo = { index, offset -> state.scrollToItem(index, offset) }
    )
    return state
}

@Composable
private fun FolderScrollEffects(
    memory: FolderScrollMemory,
    path: String,
    isLoading: Boolean,
    record: Boolean,
    position: () -> Pair<Int, Int>,
    scrollTo: suspend (Int, Int) -> Unit
) {
    // Positions are only meaningful once the list shows this folder's own rows: either it was
    // created after loading finished (restored through the initial state), or it is restored here.
    var restored by remember(path) { mutableStateOf(!isLoading) }

    LaunchedEffect(path, isLoading) {
        if (!isLoading && !restored) {
            memory.get(path)?.let { (index, offset) -> scrollTo(index, offset) }
            restored = true
        }
    }

    LaunchedEffect(path, record) {
        snapshotFlow { Triple(restored, position().first, position().second) }
            .collect { (ready, index, offset) ->
                if (ready && record) memory.save(path, index, offset)
            }
    }
}
