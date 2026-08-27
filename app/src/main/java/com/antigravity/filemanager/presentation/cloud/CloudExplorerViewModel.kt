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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    val toastMessage: String? = null,
    val itemForProperties: FileItem? = null,
    val showPropertiesDialog: Boolean = false,
    val itemToRename: FileItem? = null,
    val showRenameDialog: Boolean = false,
    val showDeleteDialog: Boolean = false,
    val clipboardPaths: List<String> = emptyList(),
    val isCutOperation: Boolean = false,
    val clipboardSourceCloudAccountId: String? = null,
    val clipboardItemSizes: Map<String, Long> = emptyMap(),
    val overwriteConflicts: List<com.antigravity.filemanager.domain.model.OverwriteConflict> = emptyList(),
    val downloadProgress: CloudTransferProgress? = null
)

@HiltViewModel
class CloudExplorerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cloudUseCase: CloudStorageUseCase,
    private val fileOperationsUseCase: FileOperationsUseCase,
    private val globalClipboardManager: GlobalClipboardManager,
    private val folderCacheManager: FolderCacheManager,
    private val folderPreferencesRepository: com.antigravity.filemanager.data.repository.FolderPreferencesRepository,
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
                    clipboardItemSizes = clip.itemSizes
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
                        // Don't let a transient API error (e.g. MEGA rate-limiting under
                        // concurrent thumbnail fetches) wipe out a file list already on screen —
                        // just stop the spinner and leave whatever's showing (cached or empty)
                        // alone; the user can pull-to-refresh to retry.
                        _uiState.value = _uiState.value.copy(isLoading = false)
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
        val foldersToCount = files.filter { it.isDirectory && it.itemCount == 0 }
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

    fun cancelTransfer() {
        activeTransferJob?.cancel()
        activeTransferJob = null
        _uiState.value = _uiState.value.copy(
            downloadProgress = null,
            isLoading = false,
            toastMessage = "Cancelled"
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

                _uiState.value = _uiState.value.copy(
                    downloadProgress = CloudTransferProgress(
                        currentFileName = file.name,
                        currentIndex = 1,
                        totalFiles = 1,
                        bytesTransferred = 0L,
                        totalBytes = file.size,
                        isIndeterminate = file.size <= 0
                    )
                )

                val downloadThrottler = com.antigravity.filemanager.utils.ProgressThrottler()
                val result = cloudUseCase.downloadFile(accountId, file.path, targetDir.absolutePath) { bytesRead, totalBytes ->
                    val effTotal = if (totalBytes > 0) totalBytes else file.size
                    if (downloadThrottler.shouldEmit(bytesRead, effTotal)) {
                        _uiState.value = _uiState.value.copy(
                            downloadProgress = CloudTransferProgress(
                                currentFileName = file.name,
                                currentIndex = 1,
                                totalFiles = 1,
                                bytesTransferred = bytesRead,
                                totalBytes = effTotal,
                                isIndeterminate = effTotal <= 0
                            )
                        )
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
        val newStack = _uiState.value.pathStack + (folder.name to folder.path)
        persistPathStack(newStack)
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
        val isRoot = currentPath.isBlank() || currentPath == "/"
        // A whole-account tree refresh (list_folder(recursive=true) across everything, ~thousands
        // of items on a large Dropbox account) is expensive — only pay for it when the user
        // explicitly asked to refresh from the account root. Every other refresh (automatic, after
        // paste/delete/rename/createFolder, or a manual refresh while inside a subfolder) just
        // re-lists that one folder.
        val forceFullRefresh = isManual && isRoot &&
            _uiState.value.account?.provider == com.antigravity.filemanager.domain.model.CloudProvider.DROPBOX
        folderCacheManager.invalidateCloud(accountId, currentPath)
        loadAccountAndFiles(currentPath, _uiState.value.pathStack, forceFullRefresh)
    }

    private fun sortCloudFiles(files: List<FileItem>, sort: FileSortOption): List<FileItem> {
        val locale = java.util.Locale.getDefault()
        return when (sort) {
            FileSortOption.BY_NAME_ASC -> files.sortedWith(
                compareBy<FileItem> { !it.isDirectory }.thenBy { it.name.lowercase(locale) }
            )
            FileSortOption.BY_NAME_DESC -> files.sortedWith(
                compareBy<FileItem> { !it.isDirectory }.thenByDescending { it.name.lowercase(locale) }
            )
            FileSortOption.BY_DATE_DESC -> files.sortedWith(
                compareBy<FileItem> { !it.isDirectory }.thenByDescending { it.lastModified }
            )
            FileSortOption.BY_DATE_ASC -> files.sortedWith(
                compareBy<FileItem> { !it.isDirectory }.thenBy { it.lastModified }
            )
            FileSortOption.BY_SIZE_DESC -> files.sortedWith(
                compareBy<FileItem> { !it.isDirectory }.thenByDescending { it.size }
            )
            FileSortOption.BY_SIZE_ASC -> files.sortedWith(
                compareBy<FileItem> { !it.isDirectory }.thenBy { it.size }
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

    fun onSearchQueryChanged(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    fun setSearchActive(active: Boolean) {
        _uiState.value = _uiState.value.copy(
            isSearchActive = active,
            searchQuery = if (!active) "" else _uiState.value.searchQuery
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

    fun showProperties(item: FileItem?) {
        _uiState.value = _uiState.value.copy(showPropertiesDialog = item != null, itemForProperties = item)
    }

    fun createFolder(folderName: String) {
        if (folderName.isBlank()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            cloudUseCase.createFolder(accountId, folderName.trim(), _uiState.value.currentPath)
            refresh()
        }
    }

    fun deleteSelected() {
        viewModelScope.launch {
            val pathsToDelete = _uiState.value.selectedPaths.toSet()
            val remainingFiles = _uiState.value.files.filterNot { it.path in pathsToDelete || it.id in pathsToDelete }
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                showDeleteDialog = false,
                files = remainingFiles,
                selectedPaths = emptySet(),
                isSelectionMode = false
            )
            pathsToDelete.forEach { path ->
                cloudUseCase.deleteItem(accountId, path)
            }
            refresh()
        }
    }

    fun setShowDeleteDialog(show: Boolean) {
        _uiState.value = _uiState.value.copy(showDeleteDialog = show)
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
                            _uiState.value = _uiState.value.copy(
                                downloadProgress = CloudTransferProgress(
                                    currentFileName = currentFile,
                                    currentIndex = currentIndex,
                                    totalFiles = totalFiles,
                                    bytesTransferred = bytesSent,
                                    totalBytes = totalBytes,
                                    isIndeterminate = totalBytes <= 0,
                                    isUpload = true
                                )
                            )
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
                } else {
                    // Cloud file(s) (possibly a different account/provider) -> this cloud folder,
                    // via a local temp round-trip since there is no cross-provider server-side move/copy.
                    val tempDir = File(context.cacheDir, "clipboard_transfer").apply { mkdirs() }
                    val totalCount = sources.size
                    val downloadThrottler = com.antigravity.filemanager.utils.ProgressThrottler()
                    val uploadThrottler = com.antigravity.filemanager.utils.ProgressThrottler()
                    sources.forEachIndexed { index, remotePath ->
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        if (File(remotePath).name in skipNames) return@forEachIndexed
                        val dlResult = cloudUseCase.downloadFile(sourceCloudAccountId, remotePath, tempDir.absolutePath) { bytesRead, totalBytes ->
                            if (downloadThrottler.shouldEmit(bytesRead, totalBytes)) {
                                _uiState.value = _uiState.value.copy(
                                    downloadProgress = CloudTransferProgress(
                                        currentFileName = File(remotePath).name,
                                        currentIndex = index + 1,
                                        totalFiles = totalCount,
                                        bytesTransferred = bytesRead,
                                        totalBytes = totalBytes,
                                        isIndeterminate = totalBytes <= 0,
                                        isUpload = false
                                    )
                                )
                            }
                        }
                        val localFile = dlResult.getOrNull()
                        if (localFile != null) {
                            val upResult = cloudUseCase.uploadFiles(
                                accountId = accountId,
                                localPaths = listOf(localFile.absolutePath),
                                remoteDir = targetPath,
                                overwriteNames = overwriteNames,
                                skipNames = emptySet()
                            ) { currentFile, _, _, bytesSent, totalBytes ->
                              if (uploadThrottler.shouldEmit(bytesSent, totalBytes)) {
                                _uiState.value = _uiState.value.copy(
                                    downloadProgress = CloudTransferProgress(
                                        currentFileName = currentFile,
                                        currentIndex = index + 1,
                                        totalFiles = totalCount,
                                        bytesTransferred = bytesSent,
                                        totalBytes = totalBytes,
                                        isIndeterminate = totalBytes <= 0,
                                        isUpload = true
                                    )
                                )
                              }
                            }
                            localFile.delete()
                            if (upResult.isSuccess && isMove) {
                                cloudUseCase.deleteItem(sourceCloudAccountId, remotePath)
                            } else if (upResult.isFailure) {
                                failures++
                                lastErrorMessage = upResult.exceptionOrNull()?.message
                            }
                        } else {
                            failures++
                        }
                    }
                    _uiState.value = _uiState.value.copy(downloadProgress = null)
                    tempDir.deleteRecursively()
                }
                globalClipboardManager.clear()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    toastMessage = if (failures == 0) {
                        "Pasted ${sources.size} item(s)"
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
        val selected = _uiState.value.selectedPaths.toList()
        val matching = _uiState.value.files.filter { it.path in _uiState.value.selectedPaths || it.id in _uiState.value.selectedPaths }
        val sizes = matching.associate { it.path to it.size }
        val ids = matching.associate { it.path to it.id }
        globalClipboardManager.copyFromCloud(accountId, selected, sizes, ids)
        clearSelection()
    }

    fun cutSelected() {
        val selected = _uiState.value.selectedPaths.toList()
        val matching = _uiState.value.files.filter { it.path in _uiState.value.selectedPaths || it.id in _uiState.value.selectedPaths }
        val sizes = matching.associate { it.path to it.size }
        val ids = matching.associate { it.path to it.id }
        globalClipboardManager.cutFromCloud(accountId, selected, sizes, ids)
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

