package com.antigravity.filemanager.data.remote.ftp

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import com.antigravity.filemanager.data.local.cache.FolderCacheManager
import com.antigravity.filemanager.data.local.observer.MediaChangeSignal
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.apache.ftpserver.ftplet.DefaultFtplet
import org.apache.ftpserver.ftplet.FtpReply
import org.apache.ftpserver.ftplet.FtpRequest
import org.apache.ftpserver.ftplet.FtpSession
import org.apache.ftpserver.ftplet.FtpletResult
import java.io.File

/**
 * Ftplet that intercepts file mutations (DELE, RMD, STOR, APPE, STOU, RNFR, RNTO, MKD)
 * performed by FTP clients and synchronizes Android MediaStore, FolderCacheManager,
 * and UI view models (via MediaChangeSignal).
 *
 * This ensures that when an FTP client deletes or uploads files, the storage capacity,
 * category counters, and file listings in OwlFM+ update immediately without requiring
 * an app restart or manual double-refresh.
 */
class FtpMediaSyncFtplet(
    @ApplicationContext private val context: Context,
    private val folderCacheManager: FolderCacheManager,
    private val mediaChangeSignal: MediaChangeSignal
) : DefaultFtplet() {

    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val ATTR_DELE_PATH = "owl_ftp_dele_path"
        private const val ATTR_RMD_PATHS = "owl_ftp_rmd_paths"
        private const val ATTR_RNFR_PATH = "owl_ftp_rnfr_path"
        private const val ATTR_ACTIVE_UPLOAD_PATH = "owl_ftp_active_upload_path"
        private const val ATTR_ACTIVE_UPLOAD_EXISTED = "owl_ftp_active_upload_existed"
        private const val ATTR_ACTIVE_UPLOAD_INIT_LEN = "owl_ftp_active_upload_init_len"
        private const val ATTR_ACTIVE_UPLOAD_CMD = "owl_ftp_active_upload_cmd"
    }

    private val cleanupLock = Any()

    private data class UploadState(
        val path: String,
        val existed: Boolean,
        val initLen: Long,
        val cmd: String
    )

    override fun destroy() {
        super.destroy()
        serverScope.cancel()
    }

    private fun resolvePhysicalFile(session: FtpSession, argument: String?): File? {
        if (argument.isNullOrBlank()) return null
        return try {
            val ftpFile = session.fileSystemView?.getFile(argument) ?: return null
            (ftpFile.physicalFile as? File)?.absoluteFile ?: run {
                val virtualPath = ftpFile.absolutePath.trimStart('/')
                val home = Environment.getExternalStorageDirectory()
                File(home, virtualPath).absoluteFile
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun collectPathsRecursively(file: File, out: MutableList<String>) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { collectPathsRecursively(it, out) }
        }
        out.add(file.absolutePath)
    }

    private fun notifyDeletedPaths(paths: List<String>) {
        if (paths.isEmpty()) return
        try {
            MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
        } catch (e: Exception) {
            android.util.Log.e("FtpMediaSyncFtplet", "Failed to scan deleted paths", e)
        }
        serverScope.launch {
            try {
                paths.forEach { path ->
                    try {
                        val uri = android.provider.MediaStore.Files.getContentUri("external")
                        context.contentResolver.delete(
                            uri,
                            "${android.provider.MediaStore.MediaColumns.DATA} = ?",
                            arrayOf(path)
                        )
                    } catch (_: Exception) { }
                }
                folderCacheManager.removeFromMediaFolders(paths)
                folderCacheManager.invalidateMediaFolders()
                folderCacheManager.clearDashboardSummary()
                paths.mapNotNull { File(it).parent }.distinct().forEach {
                    folderCacheManager.invalidateLocal(it)
                }
            } catch (e: Exception) {
                android.util.Log.e("FtpMediaSyncFtplet", "Failed to update cache on delete", e)
            }
        }
        mediaChangeSignal.notifyChanged()
    }

    private fun notifyUploadedPaths(paths: List<String>) {
        if (paths.isEmpty()) return
        try {
            MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
        } catch (e: Exception) {
            android.util.Log.e("FtpMediaSyncFtplet", "Failed to scan uploaded paths", e)
        }
        serverScope.launch {
            try {
                folderCacheManager.invalidateMediaFolders()
                folderCacheManager.clearDashboardSummary()
                paths.mapNotNull { File(it).parent }.distinct().forEach {
                    folderCacheManager.invalidateLocal(it)
                }
            } catch (e: Exception) {
                android.util.Log.e("FtpMediaSyncFtplet", "Failed to update cache on upload", e)
            }
        }
        mediaChangeSignal.notifyChanged()
    }

    private fun notifyRenamedPath(oldPath: String, newPath: String) {
        try {
            MediaScannerConnection.scanFile(context, arrayOf(oldPath, newPath), null, null)
        } catch (e: Exception) {
            android.util.Log.e("FtpMediaSyncFtplet", "Failed to scan renamed paths", e)
        }
        serverScope.launch {
            try {
                folderCacheManager.removeFromMediaFolders(listOf(oldPath))
                folderCacheManager.invalidateMediaFolders()
                folderCacheManager.clearDashboardSummary()
                listOfNotNull(File(oldPath).parent, File(newPath).parent).distinct().forEach {
                    folderCacheManager.invalidateLocal(it)
                }
            } catch (e: Exception) {
                android.util.Log.e("FtpMediaSyncFtplet", "Failed to update cache on rename", e)
            }
        }
        mediaChangeSignal.notifyChanged()
    }

    private fun notifyCreatedDir(dirPath: String) {
        serverScope.launch {
            try {
                File(dirPath).parent?.let { folderCacheManager.invalidateLocal(it) }
            } catch (e: Exception) {
                android.util.Log.e("FtpMediaSyncFtplet", "Failed to update cache on mkdir", e)
            }
        }
        mediaChangeSignal.notifyChanged()
    }

    private fun cleanUpFailedUpload(file: File, existed: Boolean, initLen: Long, cmd: String) {
        try {
            if (!file.exists()) return

            if (cmd == "APPE" && existed) {
                try {
                    java.io.RandomAccessFile(file, "rw").use { raf ->
                        raf.setLength(initLen)
                    }
                    notifyUploadedPaths(listOf(file.absolutePath))
                } catch (e: Exception) {
                    file.delete()
                    notifyDeletedPaths(listOf(file.absolutePath))
                }
            } else {
                val deleted = file.delete()
                if (deleted || !file.exists()) {
                    android.util.Log.i("FtpMediaSyncFtplet", "Deleted aborted partial upload: ${file.absolutePath}")
                    notifyDeletedPaths(listOf(file.absolutePath))
                } else {
                    android.util.Log.w("FtpMediaSyncFtplet", "Failed to delete aborted partial upload: ${file.absolutePath}")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("FtpMediaSyncFtplet", "Error cleaning up aborted upload: ${file.absolutePath}", e)
        }
    }

    private fun handleUploadCleanupOrComplete(session: FtpSession, success: Boolean, explicitPath: String? = null) {
        val state = synchronized(cleanupLock) {
            val path = explicitPath ?: (session.getAttribute(ATTR_ACTIVE_UPLOAD_PATH) as? String)
            if (path == null) return
            val existed = session.getAttribute(ATTR_ACTIVE_UPLOAD_EXISTED) as? Boolean ?: false
            val initLen = session.getAttribute(ATTR_ACTIVE_UPLOAD_INIT_LEN) as? Long ?: 0L
            val cmd = session.getAttribute(ATTR_ACTIVE_UPLOAD_CMD) as? String ?: "STOR"

            session.removeAttribute(ATTR_ACTIVE_UPLOAD_PATH)
            session.removeAttribute(ATTR_ACTIVE_UPLOAD_EXISTED)
            session.removeAttribute(ATTR_ACTIVE_UPLOAD_INIT_LEN)
            session.removeAttribute(ATTR_ACTIVE_UPLOAD_CMD)

            UploadState(path, existed, initLen, cmd)
        }

        val file = File(state.path)
        if (success) {
            if (file.exists()) {
                notifyUploadedPaths(listOf(file.absolutePath))
            }
        } else {
            cleanUpFailedUpload(file, state.existed, state.initLen, state.cmd)
        }
    }

    override fun beforeCommand(session: FtpSession, request: FtpRequest): FtpletResult {
        val cmd = request.command.uppercase()
        when (cmd) {
            "DELE" -> {
                val target = resolvePhysicalFile(session, request.argument)
                if (target != null && target.exists()) {
                    session.setAttribute(ATTR_DELE_PATH, target.absolutePath)
                }
            }
            "RMD" -> {
                val target = resolvePhysicalFile(session, request.argument)
                if (target != null && target.exists()) {
                    val paths = mutableListOf<String>()
                    collectPathsRecursively(target, paths)
                    session.setAttribute(ATTR_RMD_PATHS, paths)
                }
            }
            "RNFR" -> {
                val src = resolvePhysicalFile(session, request.argument)
                if (src != null && src.exists()) {
                    session.setAttribute(ATTR_RNFR_PATH, src.absolutePath)
                }
            }
            "STOR", "APPE", "STOU" -> {
                val target = resolvePhysicalFile(session, request.argument)
                if (target != null) {
                    val existed = target.exists()
                    val initLen = if (existed) target.length() else 0L
                    synchronized(cleanupLock) {
                        session.setAttribute(ATTR_ACTIVE_UPLOAD_PATH, target.absolutePath)
                        session.setAttribute(ATTR_ACTIVE_UPLOAD_EXISTED, existed)
                        session.setAttribute(ATTR_ACTIVE_UPLOAD_INIT_LEN, initLen)
                        session.setAttribute(ATTR_ACTIVE_UPLOAD_CMD, cmd)
                    }
                }
            }
        }
        return super.beforeCommand(session, request) ?: FtpletResult.DEFAULT
    }

    override fun afterCommand(session: FtpSession, request: FtpRequest, reply: FtpReply): FtpletResult {
        val cmd = request.command.uppercase()
        when (cmd) {
            "DELE" -> {
                val path = session.getAttribute(ATTR_DELE_PATH) as? String
                session.removeAttribute(ATTR_DELE_PATH)
                if (path != null && (reply.code in 200..299 || !File(path).exists())) {
                    notifyDeletedPaths(listOf(path))
                }
            }
            "RMD" -> {
                @Suppress("UNCHECKED_CAST")
                val paths = session.getAttribute(ATTR_RMD_PATHS) as? List<String>
                session.removeAttribute(ATTR_RMD_PATHS)
                if (!paths.isNullOrEmpty() && (reply.code in 200..299 || paths.none { File(it).exists() })) {
                    notifyDeletedPaths(paths)
                }
            }
            "STOR", "APPE", "STOU" -> {
                val isSuccess = reply.code in 200..299
                val target = resolvePhysicalFile(session, request.argument)
                handleUploadCleanupOrComplete(session, success = isSuccess, explicitPath = target?.absolutePath)
            }
            "ABOR" -> {
                handleUploadCleanupOrComplete(session, success = false)
            }
            "RNTO" -> {
                val oldPath = session.getAttribute(ATTR_RNFR_PATH) as? String
                session.removeAttribute(ATTR_RNFR_PATH)
                if (reply.code in 200..299 && oldPath != null) {
                    val destFile = resolvePhysicalFile(session, request.argument)
                    if (destFile != null && destFile.exists()) {
                        notifyRenamedPath(oldPath, destFile.absolutePath)
                    }
                }
            }
            "MKD" -> {
                if (reply.code in 200..299) {
                    val dir = resolvePhysicalFile(session, request.argument)
                    if (dir != null && dir.exists()) {
                        notifyCreatedDir(dir.absolutePath)
                    }
                }
            }
        }
        return super.afterCommand(session, request, reply) ?: FtpletResult.DEFAULT
    }

    override fun onDisconnect(session: FtpSession): FtpletResult {
        handleUploadCleanupOrComplete(session, success = false)
        return super.onDisconnect(session) ?: FtpletResult.DEFAULT
    }
}
