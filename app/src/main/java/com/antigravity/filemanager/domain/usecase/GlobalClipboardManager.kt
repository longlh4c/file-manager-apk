package com.antigravity.filemanager.domain.usecase

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class GlobalClipboardState(
    val paths: List<String> = emptyList(),
    val isCut: Boolean = false,
    /** Non-null when [paths] are remote paths in this cloud account, not local file paths. */
    val sourceCloudAccountId: String? = null,
    /** Item sizes by path, known at copy/cut time — used to show old-vs-new size in overwrite prompts. */
    val itemSizes: Map<String, Long> = emptyMap(),
    /** Remote file IDs for cloud items (path -> fileId/nodeHandle) */
    val cloudFileIds: Map<String, String> = emptyMap(),
    /** Whether each cloud path is a folder — needed so a cross-account paste knows to recurse
     * into it instead of trying to download it as a single file. */
    val itemIsDirectory: Map<String, Boolean> = emptyMap()
) {
    val hasItems: Boolean get() = paths.isNotEmpty()
    val itemCount: Int get() = paths.size
}

@Singleton
class GlobalClipboardManager @Inject constructor() {
    private val _state = MutableStateFlow(GlobalClipboardState())
    val state: StateFlow<GlobalClipboardState> = _state.asStateFlow()

    fun copy(paths: List<String>, itemSizes: Map<String, Long> = emptyMap()) {
        _state.value = GlobalClipboardState(paths = paths, isCut = false, itemSizes = itemSizes)
    }

    fun cut(paths: List<String>, itemSizes: Map<String, Long> = emptyMap()) {
        _state.value = GlobalClipboardState(paths = paths, isCut = true, itemSizes = itemSizes)
    }

    fun copyFromCloud(
        accountId: String,
        paths: List<String>,
        itemSizes: Map<String, Long> = emptyMap(),
        cloudFileIds: Map<String, String> = emptyMap(),
        itemIsDirectory: Map<String, Boolean> = emptyMap()
    ) {
        _state.value = GlobalClipboardState(
            paths = paths,
            isCut = false,
            sourceCloudAccountId = accountId,
            itemSizes = itemSizes,
            cloudFileIds = cloudFileIds,
            itemIsDirectory = itemIsDirectory
        )
    }

    fun cutFromCloud(
        accountId: String,
        paths: List<String>,
        itemSizes: Map<String, Long> = emptyMap(),
        cloudFileIds: Map<String, String> = emptyMap(),
        itemIsDirectory: Map<String, Boolean> = emptyMap()
    ) {
        _state.value = GlobalClipboardState(
            paths = paths,
            isCut = true,
            sourceCloudAccountId = accountId,
            itemSizes = itemSizes,
            cloudFileIds = cloudFileIds,
            itemIsDirectory = itemIsDirectory
        )
    }

    fun clear() {
        _state.value = GlobalClipboardState()
    }
}
