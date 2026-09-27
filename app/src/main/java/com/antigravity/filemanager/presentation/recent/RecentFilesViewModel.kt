package com.antigravity.filemanager.presentation.recent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.filemanager.data.repository.RecentFilesRepository
import com.antigravity.filemanager.domain.model.FileItem
import com.antigravity.filemanager.domain.usecase.FileOperationsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

enum class RecentTab(val label: String) {
    /** Files opened in the app. */
    OPENED("Opened"),
    /** Files newly added to the device. */
    ADDED("Added")
}

enum class RecentTypeFilter(val label: String) {
    ALL("All"), IMAGES("Images"), VIDEOS("Videos"), AUDIO("Audio"), DOCUMENTS("Documents"), OTHER("Other");

    fun matches(file: FileItem): Boolean {
        val type = typeOf(file.extension.lowercase(Locale.ROOT))
        return this == ALL || this == type
    }

    companion object {
        private val images = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "svg", "raw", "dng")
        private val videos = setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "3gp", "ts", "m4v", "mpg", "mpeg")
        private val audio = setOf("mp3", "m4a", "wav", "flac", "aac", "ogg", "wma", "opus", "amr", "mid", "midi")
        private val documents = setOf(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "rtf", "odt", "ods", "odp",
            "md", "json", "xml", "html", "htm", "epub", "log"
        )

        fun typeOf(ext: String): RecentTypeFilter = when (ext) {
            in images -> IMAGES
            in videos -> VIDEOS
            in audio -> AUDIO
            in documents -> DOCUMENTS
            else -> OTHER
        }
    }
}

data class RecentUiState(
    val tab: RecentTab = RecentTab.OPENED,
    val opened: List<FileItem> = emptyList(),
    val added: List<FileItem> = emptyList(),
    val isLoadingOpened: Boolean = true,
    val isLoadingAdded: Boolean = true,
    val filter: RecentTypeFilter = RecentTypeFilter.ALL,
    val selectedPaths: Set<String> = emptySet(),
    val showClearConfirm: Boolean = false,
    val showDeleteDialog: Boolean = false,
    val isDeleting: Boolean = false,
    val toastMessage: String? = null
) {
    /** The current tab's files, narrowed to the chosen type. */
    val visibleFiles: List<FileItem>
        get() = (if (tab == RecentTab.OPENED) opened else added).filter { filter.matches(it) }

    val isLoading: Boolean get() = if (tab == RecentTab.OPENED) isLoadingOpened else isLoadingAdded
    val isSelectionMode: Boolean get() = selectedPaths.isNotEmpty()
}

@HiltViewModel
class RecentFilesViewModel @Inject constructor(
    private val recentFiles: RecentFilesRepository,
    private val fileOperationsUseCase: FileOperationsUseCase,
    private val mediaChangeSignal: com.antigravity.filemanager.data.local.observer.MediaChangeSignal
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecentUiState())
    val uiState: StateFlow<RecentUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            recentFiles.observeOpened().collect { files ->
                _uiState.update { it.copy(opened = files, isLoadingOpened = false).prunedSelection() }
            }
        }
        loadAdded()
        // A download finishing, a photo taken or a file deleted elsewhere changes "Added".
        viewModelScope.launch {
            mediaChangeSignal.changes.debounce(500).collect { loadAdded() }
        }
    }

    private var addedJob: Job? = null

    fun loadAdded() {
        val filter = _uiState.value.filter
        addedJob?.cancel()
        addedJob = viewModelScope.launch {
            // The type filter is applied while reading (see recentlyAdded), so a list per filter.
            val files = recentFiles.recentlyAdded { ext -> filter == RecentTypeFilter.ALL || RecentTypeFilter.typeOf(ext) == filter }
            _uiState.update { it.copy(added = files, isLoadingAdded = false).prunedSelection() }
        }
    }

    /** Drops selected paths no longer shown (deleted, or gone from the history). */
    private fun RecentUiState.prunedSelection(): RecentUiState {
        if (selectedPaths.isEmpty()) return this
        val shown = visibleFiles.mapTo(HashSet()) { it.path }
        return copy(selectedPaths = selectedPaths.filterTo(mutableSetOf()) { it in shown })
    }

    fun selectTab(tab: RecentTab) {
        _uiState.update { it.copy(tab = tab, selectedPaths = emptySet()) }
    }

    fun setFilter(filter: RecentTypeFilter) {
        if (filter == _uiState.value.filter) return
        _uiState.update { it.copy(filter = filter, selectedPaths = emptySet(), isLoadingAdded = true) }
        loadAdded()
    }

    fun toggleSelection(path: String) {
        _uiState.update {
            val selected = it.selectedPaths.toMutableSet()
            if (!selected.add(path)) selected.remove(path)
            it.copy(selectedPaths = selected)
        }
    }

    fun selectAll() {
        _uiState.update { it.copy(selectedPaths = it.visibleFiles.mapTo(mutableSetOf()) { f -> f.path }) }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedPaths = emptySet()) }
    }

    /** Takes the selected files out of the history; the files themselves are untouched. */
    fun removeSelectedFromHistory() {
        val paths = _uiState.value.selectedPaths
        viewModelScope.launch {
            recentFiles.remove(paths)
            _uiState.update { it.copy(selectedPaths = emptySet(), toastMessage = "Removed ${paths.size} from Recent") }
        }
    }

    fun setShowClearConfirm(show: Boolean) {
        _uiState.update { it.copy(showClearConfirm = show) }
    }

    fun clearHistory() {
        viewModelScope.launch {
            recentFiles.clear()
            _uiState.update { it.copy(showClearConfirm = false, selectedPaths = emptySet()) }
        }
    }

    fun setShowDeleteDialog(show: Boolean) {
        _uiState.update { it.copy(showDeleteDialog = show) }
    }

    /** Deletes the selected files themselves (to the Recycle Bin, or permanently). */
    fun deleteSelected(moveToRecycleBin: Boolean) {
        val paths = _uiState.value.selectedPaths.toList()
        _uiState.update { it.copy(showDeleteDialog = false, isDeleting = true) }
        viewModelScope.launch {
            val result = fileOperationsUseCase.delete(paths, moveToRecycleBin)
            val deleted = result.getOrNull()
            // The history drops them on its own once they're gone from disk.
            recentFiles.remove(paths.filterNot { java.io.File(it).exists() })
            _uiState.update {
                it.copy(
                    isDeleting = false,
                    selectedPaths = emptySet(),
                    toastMessage = when {
                        deleted == null -> "Delete failed: ${result.exceptionOrNull()?.message}"
                        deleted < paths.size -> "$deleted of ${paths.size} item(s) deleted"
                        else -> null
                    }
                )
            }
            loadAdded()
        }
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
