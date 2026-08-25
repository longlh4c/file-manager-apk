package com.antigravity.filemanager.presentation.viewers

import android.content.Context
import androidx.lifecycle.ViewModel
import com.antigravity.filemanager.domain.model.FileItem
import com.antigravity.filemanager.domain.usecase.CloudStorageUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Resolves a cloud sibling to a local file for the swipeable viewer, downloading on demand as
 * the user swipes. Shares the same per-account cache dir CloudExplorerViewModel.openFile uses,
 * so a file the user already viewed/downloaded during normal browsing isn't re-fetched.
 */
@HiltViewModel
class CloudMediaViewerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cloudUseCase: CloudStorageUseCase
) : ViewModel() {

    suspend fun resolveLocalFile(accountId: String, file: FileItem): Result<File> = withContext(Dispatchers.IO) {
        val targetDir = File(context.cacheDir, "cloud_downloads/$accountId").apply { mkdirs() }
        val localFile = File(targetDir, file.name)
        if (localFile.exists() && localFile.length() > 0) {
            return@withContext Result.success(localFile)
        }
        cloudUseCase.downloadFile(accountId, file.path, targetDir.absolutePath)
    }
}
