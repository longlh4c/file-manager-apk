package com.antigravity.filemanager.presentation.viewers

import android.content.Context
import androidx.lifecycle.ViewModel
import com.antigravity.filemanager.domain.model.FileItem
import com.antigravity.filemanager.domain.usecase.CloudStorageUseCase
import com.antigravity.filemanager.domain.usecase.FileOperationsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** A cloud sibling resolved for playback/display — either a downloaded local file, or a direct
 * stream URL (any headers it needs are stashed in [CloudStreamHeaders], keyed by that URL). */
sealed class ResolvedMedia {
    data class LocalFile(val file: File) : ResolvedMedia()
    data class Stream(val url: String) : ResolvedMedia()
}

/**
 * Resolves a cloud sibling for the swipeable viewers (image/video), on demand as the user
 * swipes. Prefers streaming directly off the provider's link (Dropbox, Google Drive) over a
 * full download when the media type/provider supports it; otherwise falls back to downloading
 * to the same per-account cache dir CloudExplorerViewModel.openFile uses, so a file the user
 * already viewed/downloaded during normal browsing isn't re-fetched.
 */
@HiltViewModel
class CloudMediaViewerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cloudUseCase: CloudStorageUseCase,
    private val fileOperationsUseCase: FileOperationsUseCase
) : ViewModel() {

    /** Deletes a local file straight to the recycle bin — used by the image/video viewer's
     * Delete action, whether the file was already local or just got downloaded here for viewing. */
    suspend fun deleteLocalFile(path: String): Result<Int> = fileOperationsUseCase.delete(listOf(path), moveToRecycleBin = true)

    /** Deletes a cloud item straight from the viewer, same delete path as the folder browser uses. */
    suspend fun deleteCloudFile(accountId: String, remotePath: String): Result<Unit> = cloudUseCase.deleteItem(accountId, remotePath)

    suspend fun resolveMedia(accountId: String, file: FileItem, allowStreaming: Boolean = true): Result<ResolvedMedia> = withContext(Dispatchers.IO) {
        val targetDir = File(context.cacheDir, "cloud_downloads/$accountId").apply { mkdirs() }
        val localFile = File(targetDir, file.name)
        // A file left truncated by an interrupted prior download (non-empty but incomplete)
        // must not be mistaken for the real thing — delete it so the real download/stream can
        // proceed instead.
        if (localFile.exists() && localFile.length() > 0) {
            if (file.size <= 0 || localFile.length() == file.size) {
                return@withContext Result.success(ResolvedMedia.LocalFile(localFile))
            }
            localFile.delete()
        }

        if (allowStreaming) {
            val source = cloudUseCase.getStreamSource(accountId, file.path).getOrNull()
            if (source != null) {
                CloudStreamHeaders.put(source.url, source.headers)
                return@withContext Result.success(ResolvedMedia.Stream(source.url))
            }
        }

        cloudUseCase.downloadFile(accountId, file.path, targetDir.absolutePath).map { ResolvedMedia.LocalFile(it) }
    }
}
