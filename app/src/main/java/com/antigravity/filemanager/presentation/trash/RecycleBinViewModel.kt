package com.antigravity.filemanager.presentation.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.filemanager.domain.model.TrashItem
import com.antigravity.filemanager.domain.usecase.RecycleBinUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
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
    val showEmptyConfirm: Boolean = false
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
                _uiState.value = _uiState.value.copy(items = list, totalSizeBytes = total)
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

    fun deleteSelectedPermanently() {
        viewModelScope.launch {
            recycleBinUseCase.deletePermanently(_uiState.value.selectedIds.toList())
            _uiState.value = _uiState.value.copy(selectedIds = emptySet())
        }
    }

    fun emptyTrash() {
        viewModelScope.launch {
            recycleBinUseCase.empty()
            _uiState.value = _uiState.value.copy(showEmptyConfirm = false, selectedIds = emptySet())
        }
    }

    fun setShowEmptyConfirm(show: Boolean) {
        _uiState.value = _uiState.value.copy(showEmptyConfirm = show)
    }
}
