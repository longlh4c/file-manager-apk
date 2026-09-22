package com.antigravity.filemanager.presentation.archive

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.filemanager.data.local.storage.ArchiveInvalidPasswordException
import com.antigravity.filemanager.data.local.storage.ArchivePasswordRequiredException
import com.antigravity.filemanager.domain.model.ArchiveEntryInfo
import com.antigravity.filemanager.domain.model.FileItem
import com.antigravity.filemanager.domain.usecase.FileOperationsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import javax.inject.Inject

data class ArchiveViewerUiState(
    val archivePath: String = "",
    /** Folder inside the archive being shown; "" is the archive's root. */
    val currentDir: String = "",
    val items: List<FileItem> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val needsPassword: Boolean = false,
    val passwordError: String? = null,
    val selectedPaths: Set<String> = emptySet(),
    /** Label of a running extraction ("Opening", "Extracting"), null when idle. */
    val workingLabel: String? = null,
    val toastMessage: String? = null,
    /** A file extracted to cache, waiting to be handed to the app's file opener. */
    val fileToOpen: FileItem? = null
) {
    val archiveName: String get() = File(archivePath).name
    val isSelectionMode: Boolean get() = selectedPaths.isNotEmpty()
}

/**
 * Browses a zip/7z/rar like a folder. The entry list is read once; folders only implied by their
 * files' paths (archives often have no separate folder entries) are synthesized here. Opening a
 * file extracts just that entry into the cache; "Extract" writes only the chosen items next to
 * the archive.
 */
@HiltViewModel
class ArchiveViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val fileOperationsUseCase: FileOperationsUseCase
) : ViewModel() {

    private val archivePath: String = savedStateHandle.get<String>("path").orEmpty()
    private var password: String? = null
    private var entries: List<ArchiveEntryInfo> = emptyList()

    private val _uiState = MutableStateFlow(ArchiveViewerUiState(archivePath = archivePath))
    val uiState: StateFlow<ArchiveViewerUiState> = _uiState.asStateFlow()

    private val previewRoot = File(context.cacheDir, "archive_view")

    init {
        viewModelScope.launch(Dispatchers.IO) { pruneOldPreviews() }
        load()
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                entries = fileOperationsUseCase.listArchiveEntries(archivePath, password)
                _uiState.update { it.copy(isLoading = false, needsPassword = false, passwordError = null) }
                showDir("")
            } catch (e: ArchivePasswordRequiredException) {
                _uiState.update { it.copy(isLoading = false, needsPassword = true, passwordError = null) }
            } catch (e: ArchiveInvalidPasswordException) {
                _uiState.update { it.copy(isLoading = false, needsPassword = true, passwordError = "Incorrect password") }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = "Couldn't read archive: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    fun submitPassword(value: String) {
        password = value
        _uiState.update { it.copy(needsPassword = false) }
        load()
    }

    private fun showDir(dir: String) {
        _uiState.update { it.copy(currentDir = dir, items = childrenOf(dir), selectedPaths = emptySet()) }
    }

    /** Direct children of [dir], including folders that exist only as a prefix of deeper files. */
    private fun childrenOf(dir: String): List<FileItem> {
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        val files = LinkedHashMap<String, ArchiveEntryInfo>()
        val folders = LinkedHashMap<String, MutableList<ArchiveEntryInfo>>()
        for (entry in entries) {
            if (!entry.path.startsWith(prefix) || entry.path == dir) continue
            val rest = entry.path.removePrefix(prefix)
            val name = rest.substringBefore('/')
            if (rest.contains('/') || entry.isDirectory) {
                folders.getOrPut(name) { mutableListOf() }.let { if (rest.contains('/')) it.add(entry) }
            } else {
                files[name] = entry
            }
        }
        val folderItems = folders.map { (name, contents) ->
            val path = prefix + name
            FileItem(
                id = path,
                name = name,
                path = path,
                size = contents.filter { !it.isDirectory }.sumOf { it.size },
                lastModified = entries.firstOrNull { it.path == path }?.lastModified ?: 0L,
                isDirectory = true,
                itemCount = contents.map { it.path.removePrefix("$path/").substringBefore('/') }.distinct().size
            )
        }
        val fileItems = files.filterKeys { it !in folders }.map { (name, entry) ->
            FileItem(
                id = entry.path,
                name = name,
                path = entry.path,
                size = entry.size,
                lastModified = entry.lastModified,
                isDirectory = false,
                extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            )
        }
        val byName = compareBy<FileItem> { it.name.lowercase(Locale.getDefault()) }
        return folderItems.sortedWith(byName) + fileItems.sortedWith(byName)
    }

    fun onItemClick(item: FileItem) {
        when {
            _uiState.value.isSelectionMode -> toggleSelection(item)
            item.isDirectory -> showDir(item.path)
            else -> openEntry(item)
        }
    }

    fun toggleSelection(item: FileItem) {
        _uiState.update { state ->
            val selected = state.selectedPaths
            state.copy(selectedPaths = if (item.path in selected) selected - item.path else selected + item.path)
        }
    }

    fun selectAll() {
        _uiState.update { it.copy(selectedPaths = it.items.map { item -> item.path }.toSet()) }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedPaths = emptySet()) }
    }

    /** Opens the folder at [dir] (a breadcrumb). */
    fun goTo(dir: String) = showDir(dir)

    /** Goes up one folder inside the archive; false when already at its root. */
    fun navigateUp(): Boolean {
        val dir = _uiState.value.currentDir
        if (dir.isEmpty()) return false
        showDir(dir.substringBeforeLast('/', ""))
        return true
    }

    private fun openEntry(item: FileItem) {
        viewModelScope.launch {
            _uiState.update { it.copy(workingLabel = "Opening") }
            // One folder per entry, emptied first, so reopening doesn't pile up "name (1)" copies.
            val dir = File(previewRoot, Integer.toHexString("$archivePath|${item.path}".hashCode()))
            withContext(Dispatchers.IO) { dir.deleteRecursively() }
            val result = fileOperationsUseCase.extractArchiveEntries(
                archivePath, listOf(item.path), item.path.substringBeforeLast('/', ""), dir.absolutePath,
                password, notifyMediaChange = false
            )
            val file = result.getOrNull()?.firstOrNull()
            _uiState.update { state ->
                if (file == null) {
                    state.copy(workingLabel = null, toastMessage = "Couldn't open ${item.name}: ${result.exceptionOrNull()?.message ?: "not found"}")
                } else {
                    state.copy(workingLabel = null, fileToOpen = item.copy(id = file.absolutePath, path = file.absolutePath))
                }
            }
        }
    }

    fun onFileOpened() {
        _uiState.update { it.copy(fileToOpen = null) }
    }

    /** Extracts the selection, or with nothing selected the whole archive, next to the archive. */
    fun extract(all: Boolean) {
        val state = _uiState.value
        val (paths, baseDir) = if (all || !state.isSelectionMode) {
            childrenOf("").map { it.path } to ""
        } else {
            state.selectedPaths.toList() to state.currentDir
        }
        if (paths.isEmpty()) return
        val targetDir = File(archivePath).parentFile ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(workingLabel = "Extracting", selectedPaths = emptySet()) }
            val result = fileOperationsUseCase.extractArchiveEntries(archivePath, paths, baseDir, targetDir.absolutePath, password)
            _uiState.update {
                it.copy(
                    workingLabel = null,
                    toastMessage = result.fold(
                        onSuccess = { files -> "Extracted ${files.size} file(s) to ${targetDir.name}" },
                        onFailure = { e -> "Extraction failed: ${e.message ?: e.javaClass.simpleName}" }
                    )
                )
            }
        }
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    private fun pruneOldPreviews() {
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        previewRoot.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
    }
}
