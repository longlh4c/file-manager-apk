package com.antigravity.filemanager.presentation.cloud

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.filemanager.data.local.cache.FolderCacheManager
import com.antigravity.filemanager.domain.model.CloudAccount
import com.antigravity.filemanager.domain.model.CloudTransferProgress
import com.antigravity.filemanager.domain.model.FileItem
import com.antigravity.filemanager.domain.model.FileSortOption
import com.antigravity.filemanager.domain.usecase.CloudStorageUseCase
import com.antigravity.filemanager.domain.usecase.FileOperationsUseCase
import com.antigravity.filemanager.domain.usecase.GlobalClipboardManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class CloudExplorerUiState(
    val accountId: String = "",
    val account: CloudAccount? = null,
    val title: String = "Cloud Storage",
    val currentPath: String = "/",
    val pathSegments: List<String> = listOf("Root"),
    val pathStack: List<Pair<String, String>> = listOf("Root" to "/"),
    val files: List<FileItem> = emptyList(),
    val isLoading: Boolean = false,
    val selectedPaths: Set<String> = emptySet(),
    val isSelectionMode: Boolean = false,
    val sortOption: FileSortOption = FileSortOption.BY_NAME_ASC,
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    // Recursive search results (this folder + every subfolder underneath it), populated by
    // onSearchQueryChanged as it walks the tree. Empty query = "not searching", not "no results"
    // — the screen falls back to `files` (the current folder's plain listing) in that case.
    val searchResults: List<FileItem> = emptyList(),
    val isSearching: Boolean = false,
    val toastMessage: String? = null,
    val itemForProperties: FileItem? = null,
    val showPropertiesDialog: Boolean = false,
    val itemToRename: FileItem? = null,
    val showRenameDialog: Boolean = false,
    val showDeleteDialog: Boolean = false,
    val showEmptyTrashDialog: Boolean = false,
    val clipboardPaths: List<String> = emptyList(),
    val isCutOperation: Boolean = false,
    val clipboardSourceCloudAccountId: String? = null,
    val clipboardItemSizes: Map<String, Long> = emptyMap(),
    val clipboardItemIsDirectory: Map<String, Boolean> = emptyMap(),
    val overwriteConflicts: List<com.antigravity.filemanager.domain.model.OverwriteConflict> = emptyList(),
    val downloadProgress: CloudTransferProgress? = null,
    // Set the moment the user taps Cancel, cleared the moment a new transfer actually starts.
    // Needed because TransferGuard.progress is mirrored into downloadProgress (so it survives
    // this ViewModel being recreated) — cancelling doesn't stop in-flight parallel workers
    // instantly, so one of them can still emit a late progress update and resurrect the dialog
    // right after it was dismissed. While this is true, that mirror is ignored.
    val transferCancelledByUser: Boolean = false
) {
    /** True when browsing inside a provider's real trash/rubbish bin (Google Drive's "/Trash" or
     * MEGA's "/Rubbish Bin" virtual root entries, or any folder nested under them) — the
     * selection action bar swaps Copy/Move/Delete for Restore/Delete Permanently in this case. */
    val isInsideTrashView: Boolean
        get() = isPathInsideTrash(currentPath)
}

/** Same trash/rubbish-bin path check as CloudExplorerUiState.isInsideTrashView, but usable
 * per-item — needed because a search result can live inside trash even while the folder actually
 * open (and thus `currentPath`) is nowhere near it. */
private fun isPathInsideTrash(path: String): Boolean =
    path == "/Trash" || path.startsWith("/Trash/") ||
        path == "/Rubbish Bin" || path.startsWith("/Rubbish Bin/")

@HiltViewModel
class CloudExplorerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cloudUseCase: CloudStorageUseCase,
    private val fileOperationsUseCase: FileOperationsUseCase,
    private val globalClipboardManager: GlobalClipboardManager,
    private val folderCacheManager: FolderCacheManager,
    private val folderPreferencesRepository: com.antigravity.filemanager.data.repository.FolderPreferencesRepository,
    private val transferGuard: com.antigravity.filemanager.data.service.TransferGuard,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        // MEGA in particular pays a full account-tree fetch+decrypt per listing, so a short
        // freshness window avoids repeating that on every navigation into an already-cached folder.
        private const val CLOUD_CACHE_FRESH_TTL_MS = 30_000L
    }

    private val accountId: String = savedStateHandle.get<String>("accountId") ?: ""
    private val title: String = savedStateHandle.get<String>("title") ?: "Cloud Storage"

    private val _uiState = MutableStateFlow(
        CloudExplorerUiState(accountId = accountId, title = title)
    )
    val uiState: StateFlow<CloudExplorerUiState> = _uiState.asStateFlow()

    // Must be declared before init{} below: loadAccountAndFiles() launches a coroutine that
    // reads this, and viewModelScope.launch can run its body synchronously (Dispatchers.Main.immediate,
    // no suspension hit yet) before the constructor reaches this point — if quotaPrefs were still
    // declared after init{}, that race hit a NullPointerException on the lazy delegate itself
    // (not just an uncomputed value) and crashed the app.
    private val quotaPrefs by lazy {
        context.getSharedPreferences("cloud_quota_cache", Context.MODE_PRIVATE)
    }

    // Same "declare before init{}" requirement as quotaPrefs above.
    private val lastFolderPrefs by lazy {
        context.getSharedPreferences("cloud_last_folder", Context.MODE_PRIVATE)
    }

    private fun loadPersistedPathStack(): List<Pair<String, String>>? {
        val json = lastFolderPrefs.getString(accountId, null) ?: return null
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                obj.getString("name") to obj.getString("path")
            }.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    // Remembers the folder the user was last browsing in this account, so leaving for another
    // account (or the account list) and coming back reopens where they left off instead of root.
    private fun persistPathStack(stack: List<Pair<String, String>>) {
        try {
            val arr = org.json.JSONArray()
            stack.forEach { (name, path) ->
                arr.put(org.json.JSONObject().apply { put("name", name); put("path", path) })
            }
            lastFolderPrefs.edit().putString(accountId, arr.toString()).apply()
        } catch (e: Exception) {}
    }

    init {
        val persistedStack = loadPersistedPathStack()
        if (persistedStack != null) {
            loadAccountAndFiles(persistedStack.last().second, persistedStack)
        } else {
            loadAccountAndFiles("/", listOf("Root" to "/"))
        }
        viewModelScope.launch {
            globalClipboardManager.state.collect { clip ->
                _uiState.value = _uiState.value.copy(
                    clipboardPaths = clip.paths,
                    isCutOperation = clip.isCut,
                    clipboardSourceCloudAccountId = clip.sourceCloudAccountId,
                    clipboardItemSizes = clip.itemSizes,
                    clipboardItemIsDirectory = clip.itemIsDirectory
                )
            }
        }
        // TransferGuard.progress is the same source feeding the persistent notification, and it
        // lives in a singleton tied to the whole app process — surviving regardless of whether
        // this particular ViewModel instance does. Without this, backgrounding the app mid-copy
        // and reopening it (which can recreate this ViewModel from scratch, e.g. after the OS
        // reclaims the Activity) left the notification correctly still showing progress while the
        // in-app bar came back blank, since it only ever knew about its own local, now-reset state.
        viewModelScope.launch {
            transferGuard.progress.collect { info ->
                if (info != null && _uiState.value.transferCancelledByUser) {
                    // A late update from a not-yet-cancelled parallel worker — this operation was
                    // already dismissed by the user, don't resurrect the dialog for it.
                    return@collect
                }
                _uiState.value = _uiState.value.copy(
                    downloadProgress = info?.let {
                        CloudTransferProgress(
                            currentFileName = it.currentFileName,
                            currentIndex = it.currentIndex,
                            totalFiles = it.totalFiles,
                            bytesTransferred = it.bytesTransferred,
                            totalBytes = it.totalBytes,
                            isIndeterminate = it.totalBytes <= 0,
                            isUpload = it.isUpload,
                            operationLabel = it.operationLabel
                        )
                    },
                    // A fresh info==null means the whole operation truly ended (TransferGuard.end()
                    // reached zero) — safe to arm the mirror again for the next transfer.
                    transferCancelledByUser = if (info == null) false else _uiState.value.transferCancelledByUser
                )
            }
        }
    }

    private var activeLoadJob: kotlinx.coroutines.Job? = null

    private fun loadAccountAndFiles(
        path: String,
        stack: List<Pair<String, String>> = _uiState.value.pathStack,
        forceFullRefresh: Boolean = false
    ) {
        // Without this, navigating quickly (open folder A, then B before A's fetch finishes)
        // left both loads running concurrently — whichever happened to resolve LAST won the UI
        // state, regardless of which folder the user was actually looking at by then. That's what
        // showed up as folders randomly appearing empty/missing: A's stale (or even correct-but-
        // for-the-wrong-path) result landing after B's, overwriting what should've stayed on screen.
        activeLoadJob?.cancel()
        activeLoadJob = viewModelScope.launch {
            val segments = stack.map { it.first }
            // Sort is remembered per folder (keyed by account + path), same as local file browsing.
            val folderSort = folderPreferencesRepository.getSortOption(sortKey(path))
            _uiState.value = _uiState.value.copy(sortOption = folderSort)
            val accounts = cloudUseCase.getAccounts()
            val baseAccount = accounts.find { it.id == accountId }

            // 1. Load cached quota from SharedPreferences instantly
            val cachedTotal = quotaPrefs.getLong("${accountId}_total", baseAccount?.effectiveTotalBytes ?: (15L * 1024 * 1024 * 1024))
            val cachedUsed = quotaPrefs.getLong("${accountId}_used", baseAccount?.effectiveUsedBytes ?: 0L)
            val cachedAccount = baseAccount?.copy(totalSpaceBytes = cachedTotal, usedSpaceBytes = cachedUsed)

            // 2. Try to show cached folder contents immediately (Stale phase)
            val cached = folderCacheManager.getCloudFolder(accountId, path, CLOUD_CACHE_FRESH_TTL_MS)
            var skipRevalidate = false
            if (cached != null && cached.files.isNotEmpty()) {
                val sortedCached = sortCloudFiles(cached.files, _uiState.value.sortOption)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    account = cachedAccount,
                    title = cachedAccount?.accountName ?: title,
                    currentPath = path,
                    pathStack = stack,
                    files = sortedCached,
                    pathSegments = segments,
                    selectedPaths = emptySet(),
                    isSelectionMode = false
                )
                if (cached.isFresh) {
                    // Cache is recent (and every mutation invalidates it via refresh()) —
                    // skip the network round-trip, which for MEGA means skipping a full
                    // account-tree re-download + re-decrypt just to show this folder again.
                    skipRevalidate = true
                    fetchFolderItemCounts(sortedCached)
                }
            } else {
                // No cache available — show loading spinner
                _uiState.value = _uiState.value.copy(isLoading = true, currentPath = path, pathStack = stack)
            }

            // 3. Revalidate: fetch fresh data from API in background
            if (!skipRevalidate) {
                launch {
                    val result = cloudUseCase.getFiles(accountId, path, forceFullRefresh)
                    if (result.isFailure) {
                        android.util.Log.e("CloudExplorerViewModel", "loadAccountAndFiles: getFiles failed for path='$path' forceFullRefresh=$forceFullRefresh", result.exceptionOrNull())
                        // Don't let a transient API error (e.g. MEGA rate-limiting under
                        // concurrent thumbnail fetches) wipe out a file list already on screen —
                        // just stop the spinner and leave whatever's showing (cached or empty)
                        // alone; the user can pull-to-refresh to retry. But when there's NOTHING
                        // already showing (nothing cached, first load, or genuinely empty), this
                        // used to fail completely silently — an expired token or dead network
                        // looked pixel-for-pixel identical to "this folder really is empty", with
                        // no way to tell the difference short of reading logcat.
                        if (_uiState.value.files.isEmpty()) {
                            _uiState.value = _uiState.value.copy(
                                isLoading = false,
                                toastMessage = "Couldn't load: ${result.exceptionOrNull()?.message ?: "unknown error"}"
                            )
                        } else {
                            _uiState.value = _uiState.value.copy(isLoading = false)
                        }
                        return@launch
                    }
                    val rawFilesList = result.getOrDefault(emptyList())

                    // Match already downloaded thumbnail files on disk. Images only — Coil can
                    // render those directly, but handing it a raw video file to decode itself is
                    // unreliable on some devices (see fetchThumbnailFor), so videos always go
                    // through requestThumbnail()'s proper frame-extraction path instead.
                    val targetDir = File(context.cacheDir, "cloud_downloads/$accountId")
                    val filesList = rawFilesList.map { file ->
                        if (!file.isDirectory && file.thumbnailUri == null &&
                            file.extension.lowercase() in thumbnailImageExtensions
                        ) {
                            val local = File(targetDir, file.name)
                            if (local.exists() && local.length() > 0) {
                                file.copy(thumbnailUri = local.absolutePath)
                            } else {
                                file
                            }
                        } else {
                            file
                        }
                    }

                    val sortedFiles = sortCloudFiles(filesList, _uiState.value.sortOption)

                    // Save to cache for next time
                    folderCacheManager.putCloudFolder(accountId, path, sortedFiles)

                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        files = sortedFiles,
                        pathSegments = segments,
                        selectedPaths = emptySet(),
                        isSelectionMode = false
                    )

                    // Trigger background asynchronous folder item counts; thumbnails are fetched
                    // lazily per-row as they're scrolled into view (see requestThumbnail).
                    fetchFolderItemCounts(sortedFiles)
                }
            }

            // 4. Refresh quota asynchronously (non-blocking)
            launch {
                try {
                    val quotaResult = cloudUseCase.getQuota(accountId)
                    val (total, used) = quotaResult.getOrDefault(Pair(cachedTotal, cachedUsed))
                    quotaPrefs.edit().putLong("${accountId}_total", total).putLong("${accountId}_used", used).apply()
                    val updatedAccount = baseAccount?.copy(totalSpaceBytes = total, usedSpaceBytes = used)
                    _uiState.value = _uiState.value.copy(
                        account = updatedAccount,
                        title = updatedAccount?.accountName ?: title
                    )
                } catch (_: Exception) {
                    // Keep cached quota values on failure
                }
            }
        }
    }

    private fun fetchFolderItemCounts(files: List<FileItem>) {
        // MEGA's listFiles() always computes an accurate itemCount for every folder in one pass,
        // so a folder showing 0 here really has 0 items and querying it again is pure waste.
        // Dropbox only gets that for free when DropboxApiClient.listFolderCached() served this
        // listing from its whole-account tree cache — the (now default) shallow single-folder
        // listing path doesn't know subfolder counts, so those need this same bounded fan-out
        // Google Drive already relies on. Either way each call below is cheap: it reuses the tree
        // cache if still fresh, or falls back to one shallow per-folder listing (never a full
        // whole-account fetch).
        val provider = _uiState.value.account?.provider
        if (provider == com.antigravity.filemanager.domain.model.CloudProvider.MEGA) return
        // The virtual Trash/Rubbish Bin item ("__trash__") has no real folder/item split to
        // fetch — see folderItemCountLabel's comment — so counting it here is both wasted work
        // and the source of the "0 folders, 0 items" badge it showed while never resolving.
        val foldersToCount = files.filter { it.isDirectory && it.itemCount == 0 && it.id != "__trash__" }
        if (foldersToCount.isEmpty()) return
        val path = _uiState.value.currentPath
        viewModelScope.launch(Dispatchers.IO) {
            val semaphore = kotlinx.coroutines.sync.Semaphore(4)
            // coroutineScope (not a bare launch-per-folder) so this suspends until every subfolder
            // count has resolved, before writing the result back to cache below — otherwise the
            // cache would get saved before the fan-out even finished.
            kotlinx.coroutines.coroutineScope {
                foldersToCount.forEach { folder ->
                    launch(Dispatchers.IO) {
                        semaphore.withPermit {
                            val result = cloudUseCase.getFiles(accountId, folder.path)
                            val children = result.getOrNull() ?: emptyList()
                            val count = children.size
                            if (count > 0) {
                                val subfolders = children.count { it.isDirectory }
                                val childFiles = count - subfolders
                                withContext(Dispatchers.Main) {
                                    val updated = _uiState.value.files.map {
                                        if (it.id == folder.id) {
                                            it.copy(itemCount = count, subfolderCount = subfolders, fileChildCount = childFiles)
                                        } else it
                                    }
                                    _uiState.value = _uiState.value.copy(files = updated)
                                }
                            }
                        }
                    }
                }
            }
            // Persist the resolved counts so re-opening this folder within the cache TTL reuses
            // them instead of re-running this whole per-subfolder fan-out from scratch every time
            // — this was previously the single biggest cost of reopening a Dropbox/Drive folder.
            if (_uiState.value.currentPath == path) {
                folderCacheManager.putCloudFolder(accountId, path, _uiState.value.files)
            }
        }
    }

    /** Decodes one frame from a Range-capable direct video URL on an untracked daemon thread —
     * see the call site in [fetchThumbnailFor] for why this can't just be a coroutine wrapping
     * the blocking MediaMetadataRetriever calls directly. Returns the saved JPEG's path, or null
     * on any failure (missing frame, decode error, etc — the caller times this out separately). */
    private suspend fun extractVideoFrameThumbnail(link: String, cachedThumb: File): String? =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val thread = Thread({
                val retriever = android.media.MediaMetadataRetriever()
                val result = try {
                    retriever.setDataSource(link, HashMap<String, String>())
                    val frame = retriever.getFrameAtTime(-1, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (frame != null) {
                        cachedThumb.outputStream().use { out ->
                            frame.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                        }
                        frame.recycle()
                        cachedThumb.absolutePath
                    } else {
                        null
                    }
                } catch (t: Throwable) {
                    null
                } finally {
                    retriever.release()
                }
                if (cont.isActive) cont.resumeWith(Result.success(result))
            }, "video-thumb-extract")
            thread.isDaemon = true
            thread.start()
        }

    /** Decodes from a local (partially downloaded) file — fallback for MEGA when the on-demand
     * data source path fails. Deletes [localFile] afterwards (it's a disposable temp file). */
    private suspend fun extractVideoFrameThumbnailFromFile(localFile: File, cachedThumb: File): String? =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val thread = Thread({
                val retriever = android.media.MediaMetadataRetriever()
                val result = try {
                    retriever.setDataSource(localFile.absolutePath)
                    val frame = retriever.getFrameAtTime(1_000_000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: retriever.getFrameAtTime(-1, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (frame != null) {
                        cachedThumb.outputStream().use { out ->
                            frame.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                        }
                        frame.recycle()
                        cachedThumb.absolutePath
                    } else {
                        null
                    }
                } catch (t: Throwable) {
                    null
                } finally {
                    retriever.release()
                    localFile.delete()
                }
                if (cont.isActive) cont.resumeWith(Result.success(result))
            }, "video-thumb-extract-partial")
            thread.isDaemon = true
            thread.start()
        }

    /** Same as [extractVideoFrameThumbnailFromFile] but for a persistent local copy (the full
     * cached download) rather than a disposable partial-download temp file — must NOT delete
     * [localFile] afterwards. */
    private suspend fun extractVideoFrameThumbnailFromLocalCopy(localFile: File, cachedThumb: File): String? =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val thread = Thread({
                val retriever = android.media.MediaMetadataRetriever()
                val result = try {
                    retriever.setDataSource(localFile.absolutePath)
                    val frame = retriever.getFrameAtTime(1_000_000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: retriever.getFrameAtTime(-1, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (frame != null) {
                        cachedThumb.outputStream().use { out ->
                            frame.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                        }
                        frame.recycle()
                        cachedThumb.absolutePath
                    } else {
                        null
                    }
                } catch (t: Throwable) {
                    null
                } finally {
                    retriever.release()
                }
                if (cont.isActive) cont.resumeWith(Result.success(result))
            }, "video-thumb-extract-localcopy")
            thread.isDaemon = true
            thread.start()
        }

    /** Decodes a frame straight off a [android.media.MediaDataSource] (see
     * [com.antigravity.filemanager.data.remote.cloud.api.MegaDecryptingDataSource]), which only
     * fetches+decrypts the byte ranges the retriever actually reads — no eager fixed-size
     * download needed. Used for MEGA, whose content is client-side encrypted so there's no bare
     * URL MediaMetadataRetriever could stream from directly. */
    private suspend fun extractVideoFrameThumbnailFromDataSource(dataSource: android.media.MediaDataSource, cachedThumb: File): String? =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val thread = Thread({
                val retriever = android.media.MediaMetadataRetriever()
                val result = try {
                    retriever.setDataSource(dataSource)
                    // A frame right at position 0 is often a black fade-in/intro/watermark
                    // splash — try ~1s in first so short/leading-black videos don't get an
                    // all-black thumbnail.
                    val frame = retriever.getFrameAtTime(1_000_000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: retriever.getFrameAtTime(-1, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (frame != null) {
                        cachedThumb.outputStream().use { out ->
                            frame.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                        }
                        frame.recycle()
                        cachedThumb.absolutePath
                    } else {
                        null
                    }
                } catch (t: Throwable) {
                    null
                } finally {
                    retriever.release()
                    try { dataSource.close() } catch (e: Exception) {}
                }
                if (cont.isActive) cont.resumeWith(Result.success(result))
            }, "video-thumb-extract-datasource")
            thread.isDaemon = true
            thread.start()
        }

    private val thumbnailSemaphore = kotlinx.coroutines.sync.Semaphore(3)
    private val inFlightThumbnailIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val thumbnailImageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif")
    private val thumbnailVideoExtensions = setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v")
    private val maxImageThumbnailPrefetchBytes = 15L * 1024 * 1024

    /**
     * Called from the file list row when it's actually composed on screen (see
     * `onVisible` on `FileListItem`), instead of eagerly prefetching a fixed batch up front —
     * that used to hard-cap at 12 items, so sorting by size (largest-first) on a folder with
     * more than 12 videos left everything past #12 without a thumbnail forever, no matter how
     * far the user scrolled.
     */
    fun requestThumbnail(file: FileItem) {
        val ext = file.extension.lowercase()
        // A video's thumbnailUri is only ever set to a real extracted JPEG (cloud_thumbs/*.jpg)
        // by this class — EXCEPT stale entries persisted (via folderCacheManager) from before
        // videos went through proper frame extraction, when it could still be a raw video file
        // path. Treat those as unset so they get re-derived instead of staying stuck broken.
        val hasStaleRawVideoThumbnail = file.thumbnailUri != null &&
            ext in thumbnailVideoExtensions && !file.thumbnailUri.endsWith(".jpg")
        if (file.isDirectory || (file.thumbnailUri != null && !hasStaleRawVideoThumbnail)) return
        val provider = _uiState.value.account?.provider
        // Dropbox exposes a pre-signed, Range-request-capable direct link (getStreamableLink),
        // so MediaMetadataRetriever can seek straight to one frame without downloading the
        // whole video — unlike the size-capped full-download fallback, it doesn't care how
        // large the file is.
        val supportsStreamableVideo = provider == com.antigravity.filemanager.domain.model.CloudProvider.DROPBOX
        // MEGA content is encrypted, so there's no bare streamable URL — but its AES-CTR
        // encryption is byte-range addressable, so a MEGA video's thumbnail can come from an
        // on-demand decrypting data source (openThumbnailDataSource) instead of a flat download,
        // same as the size-uncapped Dropbox/streamable path above.
        val supportsOnDemandVideo = provider == com.antigravity.filemanager.domain.model.CloudProvider.MEGA
        // Dropbox has no lightweight thumbnail endpoint for images either, but the same
        // pre-signed streamable link works for them too — Coil can decode straight off that
        // URL (including .gif) without us downloading the full file first, so images aren't
        // size-capped on Dropbox any more than videos are.
        val hasCheapImagePath = provider == com.antigravity.filemanager.domain.model.CloudProvider.DROPBOX ||
            provider == com.antigravity.filemanager.domain.model.CloudProvider.MEGA ||
            provider == com.antigravity.filemanager.domain.model.CloudProvider.GOOGLE_DRIVE
        val isVideo = ext in thumbnailVideoExtensions
        val isImage = ext in thumbnailImageExtensions
        val eligible = when {
            isVideo && (supportsStreamableVideo || supportsOnDemandVideo) -> true
            isImage && hasCheapImagePath -> true
            isVideo || isImage -> file.size in 1..maxImageThumbnailPrefetchBytes
            else -> false
        }
        if (!eligible) return
        if (!inFlightThumbnailIds.add(file.id)) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                thumbnailSemaphore.withPermit {
                    fetchThumbnailFor(file, provider, supportsStreamableVideo, isVideo)
                }
            } finally {
                inFlightThumbnailIds.remove(file.id)
            }
        }
    }

    private suspend fun fetchThumbnailFor(
        item: FileItem,
        provider: com.antigravity.filemanager.domain.model.CloudProvider?,
        supportsStreamableVideo: Boolean,
        isVideo: Boolean
    ) {
        try {
            val targetDir = File(context.cacheDir, "cloud_downloads/$accountId").apply { mkdirs() }
            val thumbDir = File(context.cacheDir, "cloud_thumbs/$accountId").apply { mkdirs() }
            val hasFastThumbnailEndpoint = provider == com.antigravity.filemanager.domain.model.CloudProvider.MEGA ||
                provider == com.antigravity.filemanager.domain.model.CloudProvider.GOOGLE_DRIVE

            if (isVideo && supportsStreamableVideo) {
                // Dropbox ids look like "id:sE5JG9qJOxw..." — the colon breaks Uri.parse() in
                // Coil's AsyncImage, which falls back to the generic file-type icon instead of
                // showing the cached frame (silently, since AsyncImage just renders its `error`
                // painter).
                val cachedThumb = File(thumbDir, "${item.id.replace(Regex("[^A-Za-z0-9._-]"), "_")}.jpg")
                if (cachedThumb.exists() && cachedThumb.length() > 0) {
                    withContext(Dispatchers.Main) { updateThumbnailUriInState(item.id, cachedThumb.absolutePath) }
                    return
                }
                // A large legacy video (moov atom at the end, needing several range round-trips
                // to locate) can otherwise hang the retriever for minutes — bound the whole
                // per-item attempt so one slow file can't starve the shared semaphore slots.
                // withTimeoutOrNull alone can't cancel this: MediaMetadataRetriever's
                // setDataSource/getFrameAtTime are blocking JNI/Binder calls that ignore
                // Thread.interrupt(), and structured concurrency (including runInterruptible)
                // makes the parent WAIT for that thread to finish before the timeout can even
                // return — the exact hang this is meant to prevent. extractVideoFrameThumbnail
                // runs the blocking work on an untracked daemon thread instead, so
                // withTimeoutOrNull can walk away (leaving the thread to finish or leak
                // harmlessly in the background) the moment the deadline hits.
                val link = cloudUseCase.getStreamableLink(accountId, item.path).getOrNull() ?: return
                val resultPath = kotlinx.coroutines.withTimeoutOrNull(20_000) {
                    extractVideoFrameThumbnail(link, cachedThumb)
                }
                if (resultPath != null) {
                    withContext(Dispatchers.Main) { updateThumbnailUriInState(item.id, resultPath) }
                }
                return
            }

            val localFile = File(targetDir, item.name)
            if (localFile.exists() && localFile.length() > 0) {
                if (isVideo) {
                    // Handing Coil the raw video file (its generic VideoThumbnailFetcher) is
                    // unreliable on some devices/ROMs (a known ThumbnailUtils
                    // NumberFormatException bug) — extract a real JPEG frame ourselves instead.
                    val safeId = item.id.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val cachedThumb = File(thumbDir, "$safeId.jpg")
                    if (cachedThumb.exists() && cachedThumb.length() > 0) {
                        withContext(Dispatchers.Main) { updateThumbnailUriInState(item.id, cachedThumb.absolutePath) }
                        return
                    }
                    val resultPath = kotlinx.coroutines.withTimeoutOrNull(20_000) {
                        extractVideoFrameThumbnailFromLocalCopy(localFile, cachedThumb)
                    }
                    if (resultPath != null) {
                        withContext(Dispatchers.Main) { updateThumbnailUriInState(item.id, resultPath) }
                        return
                    }
                    // Extraction failed too — fall through so other strategies still get a shot.
                } else {
                    withContext(Dispatchers.Main) { updateThumbnailUriInState(item.id, localFile.absolutePath) }
                    return
                }
            }

            // A provider's own pre-generated thumbnail (a few KB) is far cheaper than the full
            // file, but only exists if one was ever generated for this item — fall back to the
            // full-file download below when it's not available (or the provider has no such
            // endpoint at all).
            if (hasFastThumbnailEndpoint) {
                val cachedThumb = File(thumbDir, "${item.id}.jpg")
                if (cachedThumb.exists() && cachedThumb.length() > 0) {
                    withContext(Dispatchers.Main) { updateThumbnailUriInState(item.id, cachedThumb.absolutePath) }
                    return
                }
                val thumbResult = cloudUseCase.downloadThumbnail(accountId, item.id)
                val thumbBytes = thumbResult.getOrNull()
                if (thumbBytes != null && thumbBytes.isNotEmpty()) {
                    cachedThumb.writeBytes(thumbBytes)
                    withContext(Dispatchers.Main) { updateThumbnailUriInState(item.id, cachedThumb.absolutePath) }
                    return
                }
            }

            if (isVideo && provider == com.antigravity.filemanager.domain.model.CloudProvider.MEGA) {
                // No server-side thumbnail attribute for this video (checked above). Prefer an
                // on-demand decrypting data source — MediaMetadataRetriever only pulls the byte
                // ranges it actually needs (header + one keyframe), typically far less than a
                // flat prefix download. Falls back to fetching a fixed prefix if that fails.
                val safeId = item.id.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val cachedThumb = File(thumbDir, "$safeId.jpg")
                if (cachedThumb.exists() && cachedThumb.length() > 0) {
                    withContext(Dispatchers.Main) { updateThumbnailUriInState(item.id, cachedThumb.absolutePath) }
                    return
                }

                val dataSource = cloudUseCase.openThumbnailDataSource(accountId, item.id).getOrNull()
                if (dataSource != null) {
                    val resultPath = kotlinx.coroutines.withTimeoutOrNull(20_000) {
                        extractVideoFrameThumbnailFromDataSource(dataSource, cachedThumb)
                    }
                    if (resultPath != null) {
                        withContext(Dispatchers.Main) { updateThumbnailUriInState(item.id, resultPath) }
                        return
                    }
                }

                val partialFile = File(targetDir, "$safeId.partial")
                val partialResult = cloudUseCase.downloadFilePartial(accountId, item.id, partialFile, 1_500_000L)
                val partialDownloaded = partialResult.getOrNull()
                if (partialDownloaded != null && partialDownloaded.exists() && partialDownloaded.length() > 0) {
                    val resultPath = kotlinx.coroutines.withTimeoutOrNull(20_000) {
                        extractVideoFrameThumbnailFromFile(partialDownloaded, cachedThumb)
                    }
                    if (resultPath != null) {
                        withContext(Dispatchers.Main) { updateThumbnailUriInState(item.id, resultPath) }
                        return
                    }
                } else {
                    partialDownloaded?.delete()
                }
            }

            if (provider == com.antigravity.filemanager.domain.model.CloudProvider.DROPBOX) {
                // No lightweight thumbnail endpoint on Dropbox, but Coil can decode an image
                // (including .gif) straight off the same pre-signed streamable link used for
                // videos — no local download, and not size-capped.
                val link = cloudUseCase.getStreamableLink(accountId, item.path).getOrNull()
                if (link != null) {
                    withContext(Dispatchers.Main) { updateThumbnailUriInState(item.id, link) }
                    return
                }
            }

            // Last resort: no cheap path worked, so only download the whole file for a
            // thumbnail if it's small enough to be worth it.
            if (item.size !in 1..maxImageThumbnailPrefetchBytes) return
            val dlResult = cloudUseCase.downloadFile(accountId, item.path, targetDir.absolutePath)
            if (dlResult.isSuccess) {
                val downloaded = dlResult.getOrNull()
                if (downloaded != null && downloaded.exists() && downloaded.length() > 0) {
                    withContext(Dispatchers.Main) { updateThumbnailUriInState(item.id, downloaded.absolutePath) }
                }
            }
        } catch (t: Throwable) {
            // Safely ignore thumbnail fetch error
        }
    }

    private fun updateThumbnailUriInState(fileId: String, thumbPath: String) {
        val updated = _uiState.value.files.map {
            if (it.id == fileId) it.copy(thumbnailUri = thumbPath) else it
        }
        _uiState.value = _uiState.value.copy(files = updated)
    }

    private var activeTransferJob: kotlinx.coroutines.Job? = null

    /** Single place that builds and publishes the transfer-progress dialog state — replaces the
     * repeated `_uiState.value = _uiState.value.copy(downloadProgress = CloudTransferProgress(...))`
     * blocks that used to be duplicated at every progress tick across download/upload/paste/
     * delete/restore. `isIndeterminate` is derived from `totalBytes` (as every call site already
     * did by hand): a byte-counted transfer passes real totalBytes, a count-only operation
     * (deleting/restoring N items, no per-item byte size) just leaves it at 0 and gets the
     * indeterminate bar automatically. */
    private fun setTransferProgress(
        currentFileName: String,
        currentIndex: Int,
        totalFiles: Int,
        isUpload: Boolean,
        bytesTransferred: Long = 0L,
        totalBytes: Long = 0L,
        operationLabel: String? = null
    ) {
        _uiState.value = _uiState.value.copy(
            downloadProgress = CloudTransferProgress(
                currentFileName = currentFileName,
                currentIndex = currentIndex,
                totalFiles = totalFiles,
                bytesTransferred = bytesTransferred,
                totalBytes = totalBytes,
                isIndeterminate = totalBytes <= 0,
                isUpload = isUpload,
                operationLabel = operationLabel
            )
        )
        // Mirrors into the same TransferGuard the persistent notification (TransferService) reads
        // from — every operation that shows the in-app progress dialog now also keeps that
        // notification current, instead of only paste()'s upload/download legs doing so (delete/
        // restore used to update this in-app dialog with no persistent notification at all,
        // unlike copy/move, since nothing here fed TransferGuard).
        transferGuard.updateProgress(
            com.antigravity.filemanager.data.service.TransferProgressInfo(
                currentFileName = currentFileName,
                currentIndex = currentIndex,
                totalFiles = totalFiles,
                bytesTransferred = bytesTransferred,
                totalBytes = totalBytes,
                isUpload = isUpload,
                operationLabel = operationLabel
            )
        )
    }

    fun cancelTransfer() {
        activeTransferJob?.cancel()
        activeTransferJob = null
        _uiState.value = _uiState.value.copy(
            downloadProgress = null,
            isLoading = false,
            toastMessage = "Cancelled",
            transferCancelledByUser = true
        )
    }

    /**
     * A local cache file only counts as "already downloaded" if its size matches the known
     * remote size. Without this check, a file left truncated by an interrupted prior download
     * (app killed mid-transfer, cancelled job, network drop) — non-empty but incomplete — gets
     * silently served as if it were the real thing, which is exactly what made some large
     * videos "fail" to play: a 50MB partial file passed off as a 500MB video. The stale copy is
     * deleted so nothing else can be fooled by it either.
     */
    private fun isCompleteLocalCopy(localFile: File, expectedSize: Long): Boolean {
        if (!localFile.exists() || localFile.length() <= 0) return false
        if (expectedSize > 0 && localFile.length() != expectedSize) {
            localFile.delete()
            return false
        }
        return true
    }

    /**
     * Views an image or video straight off a provider's direct/pre-signed link instead of
     * downloading the whole file first — falls back to the normal download-then-open flow
     * ([openFile]) if the file's already cached locally, the provider doesn't support direct
     * streaming (MEGA — client-side encrypted), or the link request fails.
     */
    fun openMediaStream(file: FileItem, onReadyToOpen: (FileItem) -> Unit) {
        val targetDir = File(context.cacheDir, "cloud_downloads/$accountId")
        val localFile = File(targetDir, file.name)
        if (isCompleteLocalCopy(localFile, file.size)) {
            onReadyToOpen(file.copy(path = localFile.absolutePath))
            return
        }

        activeTransferJob?.cancel()
        activeTransferJob = viewModelScope.launch {
            val source = cloudUseCase.getStreamSource(accountId, file.path).getOrNull()
            if (source != null) {
                com.antigravity.filemanager.presentation.viewers.CloudStreamHeaders.put(source.url, source.headers)
                onReadyToOpen(file.copy(path = source.url))
            } else {
                openFile(file, onReadyToOpen)
            }
        }
    }

    fun openFile(file: FileItem, onReadyToOpen: (FileItem) -> Unit) {
        activeTransferJob?.cancel()
        activeTransferJob = viewModelScope.launch {
            try {
                val targetDir = File(context.cacheDir, "cloud_downloads/$accountId").apply { mkdirs() }
                val localFile = File(targetDir, file.name)
                if (isCompleteLocalCopy(localFile, file.size)) {
                    onReadyToOpen(file.copy(path = localFile.absolutePath))
                    return@launch
                }

                setTransferProgress(currentFileName = file.name, currentIndex = 1, totalFiles = 1, isUpload = false, totalBytes = file.size)

                val downloadThrottler = com.antigravity.filemanager.utils.ProgressThrottler()
                val result = cloudUseCase.downloadFile(accountId, file.path, targetDir.absolutePath) { bytesRead, totalBytes ->
                    val effTotal = if (totalBytes > 0) totalBytes else file.size
                    if (downloadThrottler.shouldEmit(bytesRead, effTotal)) {
                        setTransferProgress(currentFileName = file.name, currentIndex = 1, totalFiles = 1, isUpload = false, bytesTransferred = bytesRead, totalBytes = effTotal)
                    }
                }

                _uiState.value = _uiState.value.copy(downloadProgress = null)

                if (result.isSuccess) {
                    val downloaded = result.getOrNull() ?: localFile
                    updateThumbnailUriInState(file.id, downloaded.absolutePath)
                    onReadyToOpen(file.copy(path = downloaded.absolutePath))
                } else {
                    val err = result.exceptionOrNull()?.message ?: "Failed to download file"
                    _uiState.value = _uiState.value.copy(toastMessage = err)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _uiState.value = _uiState.value.copy(downloadProgress = null, toastMessage = "Download cancelled")
            }
        }
    }

    fun openFolder(folder: FileItem) {
        // Tapping a folder found via recursive search must actually leave search mode — otherwise
        // loadAccountAndFiles below correctly loads the tapped folder's real contents into `files`,
        // but the screen keeps rendering the stale `searchResults` list on top of it (filteredFiles
        // prefers searchResults whenever searchQuery is non-blank), so it looked like tapping the
        // folder did nothing at all.
        searchJob?.cancel()
        val newStack = _uiState.value.pathStack + (folder.name to folder.path)
        persistPathStack(newStack)
        _uiState.value = _uiState.value.copy(
            isSearchActive = false,
            searchQuery = "",
            searchResults = emptyList(),
            isSearching = false
        )
        loadAccountAndFiles(folder.path, newStack)
    }

    fun navigateToSegment(index: Int) {
        val stack = _uiState.value.pathStack
        if (index < stack.size) {
            val newStack = stack.subList(0, index + 1)
            // Persisted here too (not just openFolder): "remember the last folder" means wherever
            // the user actually left off, including root if they deliberately navigated back to
            // it before leaving — not just the deepest point ever reached this session.
            persistPathStack(newStack)
            loadAccountAndFiles(newStack.last().second, newStack)
        }
    }

    fun navigateBack(): Boolean {
        val stack = _uiState.value.pathStack
        if (stack.size > 1) {
            navigateToSegment(stack.size - 2)
            return true
        }
        return false
    }

    fun refresh(isManual: Boolean = false) {
        val currentPath = _uiState.value.currentPath
        // A whole-account tree refresh (list_folder(recursive=true) on Dropbox, or MEGA's only
        // "f" endpoint which always returns every node) is expensive — only pay for it when the
        // user explicitly asked to refresh (pull-to-refresh), not on every automatic refresh
        // (after paste/delete/rename/createFolder). It used to also require being at the account
        // root, back when the underlying tree cache expired on its own after 45s — now that the
        // cache is kept forever until explicitly invalidated (see MegaApiClient/DropboxApiClient),
        // that restriction just meant pull-to-refresh silently did nothing while inside a
        // subfolder, since there was no other trigger to ever drop the stale cache. A manual pull
        // now always forces the full refetch, wherever you are.
        val provider = _uiState.value.account?.provider
        val forceFullRefresh = isManual &&
            (provider == com.antigravity.filemanager.domain.model.CloudProvider.DROPBOX ||
                provider == com.antigravity.filemanager.domain.model.CloudProvider.MEGA)
        folderCacheManager.invalidateCloud(accountId, currentPath)
        loadAccountAndFiles(currentPath, _uiState.value.pathStack, forceFullRefresh)
    }

    private fun sortCloudFiles(files: List<FileItem>, sort: FileSortOption): List<FileItem> {
        val locale = java.util.Locale.getDefault()
        // Name is always the final tiebreaker. It matters most for BY_DATE_*: Dropbox's API
        // simply doesn't expose a modified-time for folders (every folder entry — real ones from
        // a listing, not just freshly created ones — comes back with lastModified=0), so sorting
        // folders "by date" alone has nothing to differentiate them and fell back to whatever
        // order the API/cache happened to return, which looked arbitrary. Falling back to name
        // keeps that at least stable and predictable instead of looking shuffled.
        return when (sort) {
            FileSortOption.BY_NAME_ASC -> files.sortedWith(
                compareBy<FileItem> { !it.isDirectory }.thenBy { it.name.lowercase(locale) }
            )
            FileSortOption.BY_NAME_DESC -> files.sortedWith(
                compareBy<FileItem> { !it.isDirectory }.thenByDescending { it.name.lowercase(locale) }
            )
            FileSortOption.BY_DATE_DESC -> files.sortedWith(
                compareBy<FileItem> { !it.isDirectory }.thenByDescending { it.lastModified }.thenBy { it.name.lowercase(locale) }
            )
            FileSortOption.BY_DATE_ASC -> files.sortedWith(
                compareBy<FileItem> { !it.isDirectory }.thenBy { it.lastModified }.thenBy { it.name.lowercase(locale) }
            )
            FileSortOption.BY_SIZE_DESC -> files.sortedWith(
                compareBy<FileItem> { !it.isDirectory }.thenByDescending { it.size }.thenBy { it.name.lowercase(locale) }
            )
            FileSortOption.BY_SIZE_ASC -> files.sortedWith(
                compareBy<FileItem> { !it.isDirectory }.thenBy { it.size }.thenBy { it.name.lowercase(locale) }
            )
            FileSortOption.BY_TYPE -> files.sortedWith(
                compareBy<FileItem> { !it.isDirectory }
                    .thenBy { it.extension.lowercase(locale) }
                    .thenBy { it.name.lowercase(locale) }
            )
        }
    }

    private fun sortKey(path: String): String = "cloud:$accountId:$path"

    fun onSortChanged(sort: FileSortOption) {
        val resorted = sortCloudFiles(_uiState.value.files, sort)
        _uiState.value = _uiState.value.copy(
            sortOption = sort,
            files = resorted
        )
        val path = _uiState.value.currentPath
        viewModelScope.launch {
            folderPreferencesRepository.saveSortOption(sortKey(path), sort)
        }
        // Thumbnails for the newly-reordered rows are fetched lazily as they're scrolled into
        // view (see requestThumbnail) — no bulk re-prefetch needed here.
    }

    private var searchJob: Job? = null

    fun onSearchQueryChanged(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(searchResults = emptyList(), isSearching = false)
            return
        }
        val basePath = _uiState.value.currentPath
        val thisJob = viewModelScope.launch {
            // Debounce so fast typing doesn't kick off a new tree walk per keystroke — only the
            // settled query actually searches.
            delay(350)
            _uiState.value = _uiState.value.copy(searchResults = emptyList(), isSearching = true)
            val found = mutableListOf<FileItem>()
            val resultsMutex = Mutex()
            // Bounded fan-out same as the paste()/delete() parallel phases above — walking every
            // subfolder one at a time would make search of a large tree feel like it hung.
            val semaphore = Semaphore(4)

            suspend fun walk(path: String) {
                currentCoroutineContext().ensureActive()
                // The permit is held only around the actual network fetch below, never across the
                // recursive descent into subfolders — holding it across the whole call (including
                // waiting on this folder's own children) let a wide/deep tree deadlock: with only 4
                // permits, 4 parent folders could each end up blocked waiting for a child folder's
                // walk() to get a permit that only a *sibling* blocked parent was holding, and
                // nothing could ever finish. Limiting only the leaf network call still bounds
                // concurrent requests to 4 without that risk.
                // Dropbox's Trash listing is capped to the most-recent ~1k entries by default for
                // a fast plain "open Trash" (see DropboxApiClient.listTrash) — Dropbox doesn't
                // return them in deletion order at all, so a search that respected that cap could
                // silently miss the very item being searched for. forceFullRefresh=true here tells
                // CloudManager's Trash branch to do the complete (uncapped) scan instead, only for
                // this one search call.
                val isDropboxTrash = path == "/Trash" || path.startsWith("/Trash/")
                val listResult = semaphore.withPermit { cloudUseCase.getFiles(accountId, path, forceFullRefresh = isDropboxTrash) }
                val children = listResult.getOrDefault(emptyList())
                val matches = children.filter { it.id != "__trash__" && it.name.contains(query, ignoreCase = true) }
                if (matches.isNotEmpty()) {
                    resultsMutex.withLock {
                        found.addAll(matches)
                        _uiState.value = _uiState.value.copy(searchResults = found.toList())
                    }
                }
                // The Trash/Rubbish Bin virtual folder is a flat, whole-account view of deleted
                // items, not a real subtree of `path` — descending into it here would search
                // already-deleted files under every folder redundantly (and, for Dropbox, repeat
                // that expensive recursive includeDeleted scan on every folder visited).
                val subfolders = children.filter { it.isDirectory && it.id != "__trash__" }
                coroutineScope {
                    subfolders.forEach { sub ->
                        launch { walk(sub.path) }
                    }
                }
            }

            try {
                walk(basePath)
            } finally {
                // A newer search may have already started and replaced searchJob by the time this
                // one unwinds (from cancellation) — only clear isSearching if this is still the
                // active search, so its finally block doesn't stomp on the newer one's state.
                if (searchJob === currentCoroutineContext().job) {
                    _uiState.value = _uiState.value.copy(isSearching = false)
                }
            }
        }
        searchJob = thisJob
    }

    fun setSearchActive(active: Boolean) {
        searchJob?.cancel()
        _uiState.value = _uiState.value.copy(
            isSearchActive = active,
            searchQuery = if (!active) "" else _uiState.value.searchQuery,
            searchResults = emptyList(),
            isSearching = false
        )
    }

    fun toggleSelection(path: String) {
        val current = _uiState.value.selectedPaths.toMutableSet()
        if (current.contains(path)) current.remove(path) else current.add(path)
        _uiState.value = _uiState.value.copy(
            selectedPaths = current,
            isSelectionMode = current.isNotEmpty()
        )
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selectedPaths = emptySet(), isSelectionMode = false)
    }

    // Mirrors CloudExplorerScreen's own `filteredFiles` derivation — Select All / Invert must
    // operate on what the user is actually looking at (the search results) rather than the full
    // folder contents, or searching "abc" and getting 20 hits then "Select All" would silently
    // select all 40 items in the folder instead of just those 20.
    // Mirrors CloudExplorerScreen's own `filteredFiles` derivation — results come from the
    // recursive search (see onSearchQueryChanged), not a plain filter of the current folder's
    // listing. This used to still do that plain filter, which meant every action resolved
    // against it (Select All, Copy, Cut, Delete, Restore, Rename, Properties) silently found
    // nothing for a match that actually lived in a different subfolder.
    private fun visibleFiles(): List<FileItem> {
        val query = _uiState.value.searchQuery
        return if (query.isBlank()) _uiState.value.files else _uiState.value.searchResults
    }

    fun selectAll() {
        val allIds = visibleFiles().map { it.id }.toSet()
        _uiState.value = _uiState.value.copy(
            selectedPaths = allIds,
            isSelectionMode = true
        )
    }

    fun invertSelection() {
        val all = visibleFiles().map { it.id }.toSet()
        val current = _uiState.value.selectedPaths
        val inverted = all - current
        _uiState.value = _uiState.value.copy(
            selectedPaths = inverted,
            isSelectionMode = inverted.isNotEmpty()
        )
    }

    fun showProperties(item: FileItem?) {
        _uiState.value = _uiState.value.copy(showPropertiesDialog = item != null, itemForProperties = item)
    }

    /** Selects everything in the currently open trash/rubbish-bin folder and permanently deletes
     * it in one shot — the "Empty Trash" action, so clearing a Rubbish Bin with hundreds of items
     * doesn't require the user to manually select-all first (see the confirmation dialog wiring in
     * CloudExplorerScreen). */
    fun emptyTrash() {
        selectAll()
        deleteSelected(moveToTrash = false)
        _uiState.value = _uiState.value.copy(showEmptyTrashDialog = false)
    }

    fun setShowEmptyTrashDialog(show: Boolean) {
        _uiState.value = _uiState.value.copy(showEmptyTrashDialog = show)
    }

    fun createFolder(folderName: String) {
        if (folderName.isBlank()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            cloudUseCase.createFolder(accountId, folderName.trim(), _uiState.value.currentPath)
            refresh()
        }
    }

    fun deleteSelected(moveToTrash: Boolean = true) {
        // Same job-cancellation wiring as paste() — without registering this as the
        // activeTransferJob, tapping Cancel on the progress dialog (cancelTransfer()) had nothing
        // real to cancel: it hid the dialog for a moment, but an already-in-flight parallel delete
        // worker (of the 8 running under the semaphore below) would finish a moment later and post
        // its own progress update, resurrecting the dialog — requiring another tap, repeated until
        // every in-flight worker happened to finish. Now Cancel actually stops the coroutine tree.
        activeTransferJob?.cancel()
        activeTransferJob = viewModelScope.launch {
            // Same foreground-service reference counting copy/move already gets for free from
            // CloudStorageUseCase.uploadFiles/downloadFile — delete/restore called cloudUseCase
            // directly and never touched TransferGuard at all, so backgrounding the app mid-delete
            // (or just not watching the screen) showed no persistent notification the way a
            // copy/move in progress does.
            transferGuard.begin()
            try {
            val selectedIds = _uiState.value.selectedPaths
            // selectedPaths actually holds item IDs now (see toggleSelection) — resolve back to
            // the real FileItems so the API calls below always get a genuine remote path, not an
            // opaque ID string that a path-addressed provider (Dropbox) can't do anything with.
            // Resolved against visibleFiles(), not just `files` — a selection can come from
            // recursive search results that were never part of the current folder's own listing.
            val toDelete = visibleFiles().filter { it.path in selectedIds || it.id in selectedIds }
            _uiState.value = _uiState.value.copy(
                showDeleteDialog = false,
                selectedPaths = emptySet(),
                isSelectionMode = false
            )
            // One request at a time here used to mean deleting a large selection (an emptied
            // Rubbish Bin with hundreds of items, easily an hour+ sequentially) looked exactly
            // like the app had hung — nothing to show for minutes on end. Bounded parallel fan-out
            // (same Semaphore(8) shape as paste()'s upload/download/removal phases) cuts that down
            // to roughly total/8 round trips instead of total, and the progress bar now actually
            // moves so a big delete doesn't look stuck.
            val failuresCounter = java.util.concurrent.atomic.AtomicInteger(0)
            val completedCounter = java.util.concurrent.atomic.AtomicInteger(0)
            // Paths that genuinely failed server-side (e.g. Dropbox's permanently_delete rejecting
            // a personal, non-Business account with a 400) — only these stay visible afterward.
            // Removing every selected item from the list up front regardless of outcome, then
            // refresh() re-fetching the ones that were never actually deleted, was exactly what
            // made a failed delete look like it worked for a moment and then "came back".
            val failedPaths = java.util.Collections.synchronizedSet(mutableSetOf<String>())
            var sawPermissionError = false
            setTransferProgress(currentFileName = "", currentIndex = 0, totalFiles = toDelete.size, isUpload = false, operationLabel = "Deleting")

            // Which items resolve to a real "permanent delete" once the per-item trash-location
            // override below is applied — computed up front so a whole-account-wide MEGA batch
            // (see below) can be routed around the generic per-item loop entirely.
            fun effectiveMoveToTrash(item: FileItem) = moveToTrash && !isPathInsideTrash(item.path)
            val isMega = _uiState.value.account?.provider == com.antigravity.filemanager.domain.model.CloudProvider.MEGA
            val (megaBatch, perItem) = if (isMega) {
                toDelete.partition { !effectiveMoveToTrash(it) }
            } else {
                emptyList<FileItem>() to toDelete
            }

            if (megaBatch.isNotEmpty()) {
                // MEGA's command endpoint accepts many delete commands per HTTP request — see
                // CloudManager.deletePermanentlyBatchMega. This is the fast path a large Rubbish
                // Bin actually needs; everything else below still goes through the generic
                // bounded-parallel per-item loop (Dropbox/Drive have no equivalent batch endpoint
                // wired up yet, and a "move to trash" is a different MEGA command anyway).
                val batchResults = cloudUseCase.deletePermanentlyBatchMega(accountId, megaBatch.map { it.path })
                batchResults.forEach { (path, result) ->
                    if (result.isFailure) {
                        failuresCounter.incrementAndGet()
                        failedPaths.add(path)
                    }
                }
                completedCounter.addAndGet(megaBatch.size)
                setTransferProgress(currentFileName = "", currentIndex = completedCounter.get(), totalFiles = toDelete.size, isUpload = false, operationLabel = "Deleting")
            }

            val deleteSemaphore = kotlinx.coroutines.sync.Semaphore(8)
            kotlinx.coroutines.coroutineScope {
                perItem.forEach { item ->
                    launch {
                        deleteSemaphore.withPermit {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            // The Delete dialog's "move to trash?" choice is based on whether the
                            // CURRENTLY OPEN folder is inside trash (isInsideTrashView), which is
                            // meaningless for a search result — it can live inside the real
                            // Rubbish Bin/Trash even while browsing/searching from the account
                            // root. Deleting an item that's already inside trash needs a real
                            // (permanent) delete regardless of that dialog choice; passing
                            // moveToTrash=true for it again is at best a no-op and at worst a
                            // silent failure.
                            val result = cloudUseCase.deleteItem(accountId, item.path, effectiveMoveToTrash(item))
                            if (result.isFailure) {
                                failuresCounter.incrementAndGet()
                                failedPaths.add(item.path)
                                // Dropbox's permanently_delete is Business/Team-only — a personal
                                // account gets a 400 with this exact scope message for every single
                                // item, which is worth surfacing plainly instead of a generic
                                // "N item(s) could not be deleted" that leaves the user guessing
                                // why (and re-trying, which will just fail the same way again).
                                val msg = result.exceptionOrNull()?.message ?: ""
                                if (msg.contains("files.permanent_delete") || msg.contains("not permitted to access this endpoint")) {
                                    sawPermissionError = true
                                }
                            }
                            val done = completedCounter.incrementAndGet()
                            setTransferProgress(currentFileName = item.name, currentIndex = done, totalFiles = toDelete.size, isUpload = false, operationLabel = "Deleting")
                        }
                    }
                }
            }
            val anyFailed = failuresCounter.get() > 0
            // Only remove items that were actually confirmed deleted server-side — an item whose
            // API call failed (still in failedPaths) stays right where it was instead of vanishing
            // and then reappearing once refresh() below finds it's still really there.
            val remainingFiles = _uiState.value.files.filterNot { (it.path in selectedIds || it.id in selectedIds) && it.path !in failedPaths }
            val remainingSearchResults = _uiState.value.searchResults.filterNot { (it.path in selectedIds || it.id in selectedIds) && it.path !in failedPaths }
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                downloadProgress = null,
                files = remainingFiles,
                searchResults = remainingSearchResults,
                toastMessage = when {
                    sawPermissionError -> "Dropbox denied permanent delete — this requires a Business/Team account"
                    anyFailed -> "${failuresCounter.get()} item(s) could not be deleted"
                    else -> _uiState.value.toastMessage
                }
            )
            refresh()
            } catch (e: kotlinx.coroutines.CancellationException) {
                _uiState.value = _uiState.value.copy(downloadProgress = null, isLoading = false, toastMessage = "Cancelled")
            } finally {
                transferGuard.end()
            }
        }
    }

    fun setShowDeleteDialog(show: Boolean) {
        _uiState.value = _uiState.value.copy(showDeleteDialog = show)
    }

    /** Restores the current selection out of the trash/rubbish bin view back to the account root. */
    fun restoreSelected() {
        activeTransferJob?.cancel()
        activeTransferJob = viewModelScope.launch {
            // See the matching comment in deleteSelected — restore never touched TransferGuard
            // either, so it showed no persistent notification the way copy/move does.
            transferGuard.begin()
            try {
            val selectedIds = _uiState.value.selectedPaths
            val toRestore = visibleFiles().filter { it.path in selectedIds || it.id in selectedIds }
            val remainingFiles = _uiState.value.files.filterNot { it.path in selectedIds || it.id in selectedIds }
            val remainingSearchResults = _uiState.value.searchResults.filterNot { it.path in selectedIds || it.id in selectedIds }
            _uiState.value = _uiState.value.copy(
                files = remainingFiles,
                searchResults = remainingSearchResults,
                selectedPaths = emptySet(),
                isSelectionMode = false
            )
            setTransferProgress(currentFileName = "", currentIndex = 0, totalFiles = toRestore.size, isUpload = true, operationLabel = "Restoring")
            // Same sequential-is-too-slow-for-a-big-selection fix as deleteSelected above.
            val restoreCompleted = java.util.concurrent.atomic.AtomicInteger(0)
            val restoreSemaphore = kotlinx.coroutines.sync.Semaphore(8)
            kotlinx.coroutines.coroutineScope {
                toRestore.forEach { item ->
                    launch {
                        restoreSemaphore.withPermit {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            cloudUseCase.restoreItem(accountId, item.path)
                            val done = restoreCompleted.incrementAndGet()
                            setTransferProgress(currentFileName = item.name, currentIndex = done, totalFiles = toRestore.size, isUpload = true, operationLabel = "Restoring")
                        }
                    }
                }
            }
            _uiState.value = _uiState.value.copy(isLoading = true, downloadProgress = null)
            refresh()
            } catch (e: kotlinx.coroutines.CancellationException) {
                _uiState.value = _uiState.value.copy(downloadProgress = null, isLoading = false, toastMessage = "Cancelled")
            } finally {
                transferGuard.end()
            }
        }
    }

    private var pendingOverwriteAction: (suspend (overwriteNames: Set<String>, skipNames: Set<String>) -> Unit)? = null

    fun paste() {
        val sources = _uiState.value.clipboardPaths
        if (sources.isEmpty() || _uiState.value.isLoading) return
        val sourceCloudAccountId = _uiState.value.clipboardSourceCloudAccountId
        val isMove = _uiState.value.isCutOperation
        val targetPath = _uiState.value.currentPath

        suspend fun doPaste(overwriteNames: Set<String>, skipNames: Set<String>) {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true)
                var failures = 0
                var lastErrorMessage: String? = null
                // Defaults to the clipboard's top-level item count; the cloud-to-cloud branch
                // below overwrites this with the actual flattened file count once known, so
                // pasting one folder with 438 files inside reports "438 item(s)", not "1".
                var transferredCount = sources.size
                if (sourceCloudAccountId == null) {
                    // Local files -> this cloud folder
                    val uploadThrottler = com.antigravity.filemanager.utils.ProgressThrottler()
                    val result = cloudUseCase.uploadFiles(
                        accountId = accountId,
                        localPaths = sources,
                        remoteDir = targetPath,
                        overwriteNames = overwriteNames,
                        skipNames = skipNames
                    ) { currentFile, currentIndex, totalFiles, bytesSent, totalBytes ->
                        if (uploadThrottler.shouldEmit(bytesSent, totalBytes)) {
                            setTransferProgress(currentFileName = currentFile, currentIndex = currentIndex, totalFiles = totalFiles, isUpload = true, bytesTransferred = bytesSent, totalBytes = totalBytes)
                        }
                    }
                    _uiState.value = _uiState.value.copy(downloadProgress = null)
                    if (result.isFailure) {
                        failures++
                        lastErrorMessage = result.exceptionOrNull()?.message
                    }
                    if (isMove && result.isSuccess) {
                        fileOperationsUseCase.delete(sources, moveToRecycleBin = false)
                    }
                } else if (isMove && sourceCloudAccountId == accountId) {
                    // Same-account Move: every provider has a real server-side move (change
                    // parent/path), so route through that instead of the generic cross-provider
                    // round trip below — no data ever needs to leave the provider's own servers.
                    // Items with an Overwrite decision still need the existing target cleared
                    // first (a plain server-side move doesn't merge/replace on its own); anything
                    // with no conflict just moves directly.
                    var moved = 0
                    for (sourcePath in sources) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val name = File(sourcePath).name
                        if (name in skipNames) continue
                        if (name in overwriteNames) {
                            val existingPath = if (targetPath == "/" || targetPath.isBlank()) "/$name" else "${targetPath.trimEnd('/')}/$name"
                            cloudUseCase.deleteItem(accountId, existingPath)
                        }
                        setTransferProgress(currentFileName = name, currentIndex = moved + 1, totalFiles = sources.size, isUpload = true, operationLabel = "Moving")
                        val moveResult = cloudUseCase.moveWithinAccount(accountId, sourcePath, targetPath)
                        if (moveResult.isFailure) {
                            failures++
                            lastErrorMessage = moveResult.exceptionOrNull()?.message
                        }
                        moved++
                    }
                    transferredCount = moved
                    _uiState.value = _uiState.value.copy(downloadProgress = null)
                } else {
                    // Cloud file(s)/folder(s) (possibly a different account/provider) -> this
                    // cloud folder, via a local temp round-trip since there is no cross-provider
                    // server-side move/copy. A source folder has no single "download" call, so
                    // first flatten it: recreate the matching folder tree at the destination and
                    // collect every real file underneath (recursively) into (remoteFilePath,
                    // itsResolvedTargetDir) pairs, same strategy FileUseCases.uploadFiles already
                    // uses for local folders. Without this, a folder in the clipboard was handed
                    // straight to downloadFile() as if it were a single file, which always failed
                    // and silently dropped every file inside it.
                    val tempDir = File(context.cacheDir, "clipboard_transfer").apply { mkdirs() }
                    val isDirectoryByPath = _uiState.value.clipboardItemIsDirectory
                    data class FlatEntry(val remoteFilePath: String, val targetDir: String, val topSource: String)
                    val flat = mutableListOf<FlatEntry>()
                    // Tracks whether every file under a given top-level source transferred
                    // successfully, so a move only deletes that source once nothing was lost.
                    val topLevelSucceeded = sources.associateWith { true }.toMutableMap()

                    // Same-shape "(1)" suffixing as FileUseCases.uniqueCloudName, used below to
                    // give a top-level "Keep Both" folder its own new name at the destination
                    // instead of merging its contents into the identically-named folder already
                    // there.
                    fun uniqueCloudName(existingNames: Set<String>, name: String): String {
                        if (name !in existingNames) return name
                        val dotIndex = name.lastIndexOf('.')
                        val base = if (dotIndex > 0) name.substring(0, dotIndex) else name
                        val ext = if (dotIndex > 0) name.substring(dotIndex) else ""
                        var counter = 1
                        var candidate = "$base ($counter)$ext"
                        while (candidate in existingNames) {
                            counter++
                            candidate = "$base ($counter)$ext"
                        }
                        return candidate
                    }

                    suspend fun flatten(remotePath: String, isDir: Boolean, targetDir: String, topSource: String, nameOverride: String? = null) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val name = nameOverride ?: File(remotePath).name
                        if (isDir) {
                            // Reuse an existing same-name folder at the destination instead of
                            // always creating a new one — MEGA in particular has no problem
                            // creating a second folder with an identical name (it dedupes nothing),
                            // so blindly calling createFolder on every retry/overwrite left
                            // duplicate "same name" folders behind instead of merging into the one
                            // already there. (A top-level "Keep Both" folder was already given a
                            // fresh unique `name` above, so this lookup naturally finds nothing for
                            // it and creates a real duplicate instead of merging into the original.)
                            val existingFolder = cloudUseCase.getFiles(accountId, targetDir).getOrDefault(emptyList())
                                .find { it.isDirectory && it.name == name }
                            if (existingFolder == null) {
                                val createResult = cloudUseCase.createFolder(accountId, name, targetDir)
                                if (createResult.isFailure) {
                                    // Real failure — still try to copy its children; any file that
                                    // can't actually land will fail on its own upload below.
                                }
                            }
                            val childTargetDir = if (targetDir == "/" || targetDir.isBlank()) "/$name" else "${targetDir.trimEnd('/')}/$name"
                            val children = cloudUseCase.getFiles(sourceCloudAccountId, remotePath).getOrElse {
                                topLevelSucceeded[topSource] = false
                                lastErrorMessage = it.message
                                emptyList()
                            }
                            for (child in children) {
                                flatten(child.path, child.isDirectory, childTargetDir, topSource)
                            }
                        } else {
                            flat.add(FlatEntry(remotePath, targetDir, topSource))
                        }
                    }
                    // Names already present at the destination, used to give each top-level
                    // "Keep Both" item (neither skipped nor chosen to overwrite) its own unique
                    // name up front — otherwise a same-name folder silently merged its contents
                    // into the existing one instead of landing as a real duplicate.
                    val destExistingNames = cloudUseCase.getFiles(accountId, targetPath).getOrDefault(emptyList())
                        .map { it.name }.toMutableSet()
                    for (remotePath in sources) {
                        val name = File(remotePath).name
                        if (name in skipNames) continue
                        val effectiveName = if (name in overwriteNames || name !in destExistingNames) {
                            name
                        } else {
                            uniqueCloudName(destExistingNames, name).also { destExistingNames.add(it) }
                        }
                        flatten(remotePath, isDirectoryByPath[remotePath] == true, targetPath, remotePath, effectiveName)
                    }

                    val totalCount = flat.size
                    transferredCount = totalCount
                    val downloadThrottler = com.antigravity.filemanager.utils.ProgressThrottler()
                    val uploadThrottler = com.antigravity.filemanager.utils.ProgressThrottler()
                    // Each file's round trip (download then upload) is dominated by per-request
                    // network latency, not local CPU/bandwidth — doing them one at a time is why
                    // 438 small files felt like it crawled. Running several in flight at once
                    // overlaps that latency instead of paying it 438 times in a row. Concurrency
                    // is capped (not unbounded) because MEGA in particular rate-limits bursts of
                    // parallel requests (see the comment on listCloudFiles' offline fallback).
                    val failuresCounter = java.util.concurrent.atomic.AtomicInteger(0)
                    val lastErrorRef = java.util.concurrent.atomic.AtomicReference<String?>(null)
                    val completedCounter = java.util.concurrent.atomic.AtomicInteger(0)
                    val semaphore = kotlinx.coroutines.sync.Semaphore(8)
                    kotlinx.coroutines.coroutineScope {
                        flat.forEachIndexed { index, entry ->
                            launch {
                                semaphore.withPermit {
                                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                    val remotePath = entry.remoteFilePath
                                    // Unique per-entry download dir — concurrent downloads can
                                    // otherwise collide when two source files share a name (e.g.
                                    // "readme.txt" in two different subfolders).
                                    val entryDir = File(tempDir, index.toString()).apply { mkdirs() }
                                    val dlResult = cloudUseCase.downloadFile(sourceCloudAccountId, remotePath, entryDir.absolutePath) { bytesRead, totalBytes ->
                                        if (downloadThrottler.shouldEmit(bytesRead, totalBytes)) {
                                            setTransferProgress(currentFileName = File(remotePath).name, currentIndex = completedCounter.get() + 1, totalFiles = totalCount, isUpload = false, bytesTransferred = bytesRead, totalBytes = totalBytes)
                                        }
                                    }
                                    val localFile = dlResult.getOrNull()
                                    if (localFile != null) {
                                        // overwriteNames/skipNames here are TOP-LEVEL clipboard
                                        // item names (e.g. the folder "MP3 Tones" itself), decided
                                        // once by the user in the conflict dialog — they were never
                                        // going to match a nested file's own name (e.g.
                                        // "Urgent2.mp3"). Passing them through unchanged meant every
                                        // file inside an "Overwrite"-d folder found no name match at
                                        // its own upload call and fell back to "keep both" (a "(1)"
                                        // suffix), instead of actually overwriting. Propagate the
                                        // top-level folder's decision down to each file under it.
                                        val topName = File(entry.topSource).name
                                        val effectiveOverwriteNames = if (topName in overwriteNames) {
                                            setOf(localFile.name)
                                        } else {
                                            emptySet()
                                        }
                                        val upResult = cloudUseCase.uploadFiles(
                                            accountId = accountId,
                                            localPaths = listOf(localFile.absolutePath),
                                            remoteDir = entry.targetDir,
                                            overwriteNames = effectiveOverwriteNames,
                                            skipNames = emptySet()
                                        ) { currentFile, _, _, bytesSent, totalBytes ->
                                          if (uploadThrottler.shouldEmit(bytesSent, totalBytes)) {
                                            setTransferProgress(currentFileName = currentFile, currentIndex = completedCounter.get() + 1, totalFiles = totalCount, isUpload = true, bytesTransferred = bytesSent, totalBytes = totalBytes)
                                          }
                                        }
                                        entryDir.deleteRecursively()
                                        if (upResult.isFailure) {
                                            failuresCounter.incrementAndGet()
                                            lastErrorRef.set(upResult.exceptionOrNull()?.message)
                                            topLevelSucceeded[entry.topSource] = false
                                        }
                                    } else {
                                        entryDir.deleteRecursively()
                                        failuresCounter.incrementAndGet()
                                        topLevelSucceeded[entry.topSource] = false
                                    }
                                    completedCounter.incrementAndGet()
                                }
                            }
                        }
                    }
                    failures += failuresCounter.get()
                    lastErrorRef.get()?.let { lastErrorMessage = it }
                    if (isMove) {
                        // Delete each top-level source (file or folder) as one unit once its whole
                        // subtree copied cleanly — deleting the folder node removes everything
                        // under it remotely, so there's no need to delete descendants one by one.
                        // Was a plain sequential forEach — each delete is its own network round
                        // trip (~1-1.5s), so a multi-file selection (e.g. 26 individual files cut
                        // at once, not a single folder) paid that one at a time with the progress
                        // bar showing nothing for this phase, which looked exactly like a hang on
                        // a large selection. Run it the same bounded-parallel way as the uploads
                        // above, and keep the progress bar reporting during it.
                        val toDelete = sources.filter { topLevelSucceeded[it] == true && File(it).name !in skipNames }
                        val deleteCompleted = java.util.concurrent.atomic.AtomicInteger(0)
                        val deleteSemaphore = kotlinx.coroutines.sync.Semaphore(8)
                        kotlinx.coroutines.coroutineScope {
                            toDelete.forEach { sourcePath ->
                                launch {
                                    deleteSemaphore.withPermit {
                                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                        setTransferProgress(currentFileName = File(sourcePath).name, currentIndex = deleteCompleted.get() + 1, totalFiles = toDelete.size, isUpload = false, operationLabel = "Removing source")
                                        cloudUseCase.deleteItem(sourceCloudAccountId, sourcePath)
                                        deleteCompleted.incrementAndGet()
                                    }
                                }
                            }
                        }
                    }
                    _uiState.value = _uiState.value.copy(downloadProgress = null)
                    tempDir.deleteRecursively()
                }
                globalClipboardManager.clear()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    toastMessage = if (failures == 0) {
                        "Pasted $transferredCount item(s)"
                    } else {
                        "Pasted with $failures failure(s)" + (lastErrorMessage?.let { ": $it" } ?: "")
                    }
                )
                refresh()
            } catch (e: kotlinx.coroutines.CancellationException) {
                _uiState.value = _uiState.value.copy(downloadProgress = null, isLoading = false, toastMessage = "Transfer cancelled")
            }
        }

        activeTransferJob?.cancel()
        // Show feedback immediately: findConflicts() below is a real network call (list the
        // target folder to check for name clashes) with no progress callback of its own. Without
        // this, tapping "Paste Here" looked like it did nothing for however long that call took —
        // and because every tap here cancels+restarts the job, impatient re-tapping just kept
        // resetting the same network call instead of ever letting it finish.
        _uiState.value = _uiState.value.copy(isLoading = true)
        activeTransferJob = viewModelScope.launch {
            val itemSizes = _uiState.value.clipboardItemSizes
            val items = sources.map { path ->
                val name = File(path).name
                val size = itemSizes[path] ?: (if (sourceCloudAccountId == null) File(path).length() else 0L)
                name to size
            }
            val conflicts = cloudUseCase.findConflicts(accountId, targetPath, items)
            if (conflicts.isNotEmpty()) {
                pendingOverwriteAction = { overwriteNames, skipNames -> doPaste(overwriteNames, skipNames) }
                _uiState.value = _uiState.value.copy(isLoading = false, overwriteConflicts = conflicts)
            } else {
                doPaste(emptySet(), emptySet())
            }
        }
    }

    fun resolveOverwriteConflict(overwriteNames: Set<String>, skipNames: Set<String>) {
        val action = pendingOverwriteAction
        pendingOverwriteAction = null
        _uiState.value = _uiState.value.copy(overwriteConflicts = emptyList())
        if (action != null) {
            activeTransferJob?.cancel()
            activeTransferJob = viewModelScope.launch { action(overwriteNames, skipNames) }
        }
    }

    fun cancelOverwriteConflict() {
        pendingOverwriteAction = null
        _uiState.value = _uiState.value.copy(overwriteConflicts = emptyList())
    }

    fun clearClipboard() {
        globalClipboardManager.clear()
    }

    fun copySelected() {
        // selectedPaths holds item IDs (see toggleSelection) — resolve back to the real FileItems
        // so the clipboard always carries genuine remote paths. Passing raw IDs through here used
        // to feed them straight into the whole path-addressed paste/flatten pipeline as if they
        // were paths, which breaks for every provider, not just the MEGA duplicate-name case this
        // was fixed for.
        val matching = visibleFiles().filter { it.path in _uiState.value.selectedPaths || it.id in _uiState.value.selectedPaths }
        val selected = matching.map { it.path }
        val sizes = matching.associate { it.path to it.size }
        val ids = matching.associate { it.path to it.id }
        val isDirectory = matching.associate { it.path to it.isDirectory }
        globalClipboardManager.copyFromCloud(accountId, selected, sizes, ids, isDirectory)
        clearSelection()
    }

    fun cutSelected() {
        val matching = visibleFiles().filter { it.path in _uiState.value.selectedPaths || it.id in _uiState.value.selectedPaths }
        val selected = matching.map { it.path }
        val sizes = matching.associate { it.path to it.size }
        val ids = matching.associate { it.path to it.id }
        val isDirectory = matching.associate { it.path to it.isDirectory }
        globalClipboardManager.cutFromCloud(accountId, selected, sizes, ids, isDirectory)
        clearSelection()
    }

    fun setShowRenameDialog(item: FileItem?) {
        _uiState.value = _uiState.value.copy(showRenameDialog = item != null, itemToRename = item)
    }

    fun renameSelected(newName: String) {
        val item = _uiState.value.itemToRename ?: return
        if (newName.isBlank() || newName == item.name) {
            _uiState.value = _uiState.value.copy(showRenameDialog = false, itemToRename = null)
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, showRenameDialog = false, itemToRename = null)
            val result = cloudUseCase.renameItem(accountId, item.path, newName.trim())
            if (result.isFailure) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    toastMessage = result.exceptionOrNull()?.message ?: "Rename failed"
                )
            }
            clearSelection()
            refresh()
        }
    }

    fun clearToast() {
        _uiState.value = _uiState.value.copy(toastMessage = null)
    }
}

