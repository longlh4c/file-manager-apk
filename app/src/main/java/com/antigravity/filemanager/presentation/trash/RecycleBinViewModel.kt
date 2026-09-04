package com.antigravity.filemanager.presentation.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.filemanager.domain.model.CloudTransferProgress
import com.antigravity.filemanager.domain.model.TrashItem
import com.antigravity.filemanager.domain.usecase.RecycleBinUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RecycleBinUiState(
    val items: List<TrashItem> = emptyList(),
    val selectedIds: Set<Long> = emptySet(),
    val totalSizeBytes: Long = 0L,
    val showEmptyConfirm: Boolean = false,
    val deleteProgress: CloudTransferProgress? = null,
    // Starts true: observeTrash()'s first emission is asynchronous (a Room Flow query), so
    // without this the very first composed frame renders with the default items=emptyList()
    // and briefly shows the "Recycle Bin is empty" icon even when the bin actually has items —
    // exactly the same race as FileBrowserViewModel.loadDirectory had. Flips false on the first
    // real emission, whatever it turns out to contain.
    val isLoading: Boolean = true
) {
    val formattedTotalSize: String
        get() = com.antigravity.filemanager.domain.model.FileItem.formatBytes(totalSizeBytes)
}

@HiltViewModel
class RecycleBinViewModel @Inject constructor(
    private val recycleBinUseCase: RecycleBinUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecycleBinUiState())
    val uiState: StateFlow<RecycleBinUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            recycleBinUseCase.observeTrash().collectLatest { list ->
                val total = recycleBinUseCase.getTotalSize()
                _uiState.value = _uiState.value.copy(items = list, totalSizeBytes = total, isLoading = false)
            }
        }
    }

    fun toggleItemSelection(id: Long) {
        val current = _uiState.value.selectedIds.toMutableSet()
        if (current.contains(id)) current.remove(id) else current.add(id)
        _uiState.value = _uiState.value.copy(selectedIds = current)
    }

    fun restoreSelected() {
        viewModelScope.launch {
            recycleBinUseCase.restore(_uiState.value.selectedIds.toList())
            _uiState.value = _uiState.value.copy(selectedIds = emptySet())
        }
    }

    private var activeJob: Job? = null

    fun deleteSelectedPermanently() {
        val ids = _uiState.value.selectedIds.toList()
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            // Same fix as emptyTrash() below — permanently deleting many trashed items is a
            // synchronous recursive delete with no feedback otherwise, which reads as a hang.
            recycleBinUseCase.deletePermanently(ids) { currentName, currentIndex, total ->
                _uiState.value = _uiState.value.copy(
                    deleteProgress = CloudTransferProgress(
                        currentFileName = currentName,
                        currentIndex = currentIndex,
                        totalFiles = total,
                        isIndeterminate = false,
                        isUpload = false,
                        operationLabel = "Deleting permanently"
                    )
                )
            }
            _uiState.value = _uiState.value.copy(selectedIds = emptySet(), deleteProgress = null)
        }
    }

    fun emptyTrash() {
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            // Empty Trash used to run with zero UI feedback while it recursively deleted every
            // item — for a large bin that looked exactly like the app hanging. Report progress
            // the same way compress/extract/upload already do elsewhere in the app.
            recycleBinUseCase.empty { currentName, currentIndex, total ->
                _uiState.value = _uiState.value.copy(
                    deleteProgress = CloudTransferProgress(
                        currentFileName = currentName,
                        currentIndex = currentIndex,
                        totalFiles = total,
                        isIndeterminate = false,
                        isUpload = false,
                        operationLabel = "Emptying Trash"
                    )
                )
            }
            _uiState.value = _uiState.value.copy(showEmptyConfirm = false, selectedIds = emptySet(), deleteProgress = null)
        }
    }

    fun setShowEmptyConfirm(show: Boolean) {
        _uiState.value = _uiState.value.copy(showEmptyConfirm = show)
    }
}
