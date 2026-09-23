package com.antigravity.filemanager.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import androidx.core.content.ContextCompat
import com.antigravity.filemanager.domain.model.UsbOtgVolumeInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsbOtgManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager

    private val _connectedUsbDrives = MutableStateFlow<List<UsbOtgVolumeInfo>>(emptyList())
    val connectedUsbDrives: StateFlow<List<UsbOtgVolumeInfo>> = _connectedUsbDrives.asStateFlow()

    // False until the first scan has finished: before that the empty list above means "not known
    // yet", not "no drive", and a browser restored inside a drive must not treat it as unplugged.
    private val _scanned = MutableStateFlow(false)
    val scanned: StateFlow<Boolean> = _scanned.asStateFlow()

    // One mount/unplug fires several refreshes; run as unordered parallel scans, an older scan
    // finishing last could bring back a drive that was already removed (or hide a new one).
    private val scanLock = kotlinx.coroutines.sync.Mutex()

    private val storageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refresh()
            // Storage mounts can take a brief moment after USB attachment
            scope.launch {
                delay(1200)
                refresh()
            }
        }
    }

    init {
        registerReceivers()
        refresh()
    }

    private fun registerReceivers() {
        try {
            val mediaFilter = IntentFilter().apply {
                addAction(Intent.ACTION_MEDIA_MOUNTED)
                addAction(Intent.ACTION_MEDIA_UNMOUNTED)
                addAction(Intent.ACTION_MEDIA_REMOVED)
                addAction(Intent.ACTION_MEDIA_EJECT)
                addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
                addDataScheme("file")
            }
            val exportFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.RECEIVER_EXPORTED
            } else {
                0
            }
            ContextCompat.registerReceiver(context, storageReceiver, mediaFilter, exportFlag)

            val usbFilter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            ContextCompat.registerReceiver(context, storageReceiver, usbFilter, exportFlag)

            // StorageVolumeCallback is API 30. The old N (24) guard let Android 8-10 reach it and
            // die with NoSuchMethodError, an Error the catch below never sees.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                storageManager?.registerStorageVolumeCallback(context.mainExecutor, object : StorageManager.StorageVolumeCallback() {
                    override fun onStateChanged(volume: android.os.storage.StorageVolume) {
                        refresh()
                    }
                })
            }
        } catch (e: Exception) {
            // Log / ignore if receiver registration fails in certain test environments
        }
    }

    fun refresh() {
        scope.launch {
            scanLock.withLock {
                _connectedUsbDrives.value = scanMountedUsbDrives()
                _scanned.value = true
            }
        }
    }

    private fun scanMountedUsbDrives(): List<UsbOtgVolumeInfo> {
        val results = mutableListOf<UsbOtgVolumeInfo>()
        val sm = storageManager ?: return results

        try {
            val volumes = sm.storageVolumes
            for (volume in volumes) {
                // We want removable, non-primary volumes that are mounted (read-only too: NTFS
                // drives are often mounted that way and used to be listed only by the fallback)
                if (volume.isRemovable && !volume.isPrimary && volume.state.let { it == Environment.MEDIA_MOUNTED || it == Environment.MEDIA_MOUNTED_READ_ONLY }) {
                    val rootDir: File? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        volume.directory
                    } else {
                        volume.uuid?.let { File("/storage/$it") }
                    }

                    val validDir = if (rootDir != null && rootDir.exists() && rootDir.canRead()) {
                        rootDir
                    } else {
                        // Fallback: check getExternalFilesDirs to see if an external volume root matches
                        findExternalRootFallback(volume.uuid)
                    }

                    if (validDir != null && validDir.exists() && validDir.canRead()) {
                        val total = validDir.totalSpace
                        val free = validDir.freeSpace
                        val used = (total - free).coerceAtLeast(0L)
                        val name = volume.getDescription(context)?.takeIf { it.isNotBlank() } ?: "USB OTG"
                        val id = volume.uuid ?: validDir.name

                        results.add(
                            UsbOtgVolumeInfo(
                                id = id,
                                rootPath = validDir.absolutePath,
                                displayName = name,
                                totalBytes = total,
                                freeBytes = free,
                                usedBytes = used
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // Fallback scanner if StorageManager reflection/API fails
        }

        // If no results from StorageManager, perform secondary scan via ContextCompat.getExternalFilesDirs
        if (results.isEmpty()) {
            results.addAll(scanFromExternalFilesDirs())
        }

        return results
    }

    private fun findExternalRootFallback(uuid: String?): File? {
        if (uuid == null) return null
        val candidates = listOf(
            File("/storage/$uuid"),
            File("/mnt/media_rw/$uuid")
        )
        return candidates.firstOrNull { it.exists() && it.canRead() }
    }

    private fun scanFromExternalFilesDirs(): List<UsbOtgVolumeInfo> {
        val list = mutableListOf<UsbOtgVolumeInfo>()
        try {
            val dirs = ContextCompat.getExternalFilesDirs(context, null)
            val primaryRoot = Environment.getExternalStorageDirectory().absolutePath

            for (dir in dirs) {
                if (dir == null) continue
                val fullPath = dir.absolutePath
                if (fullPath.startsWith(primaryRoot)) continue // Skip primary internal storage

                // Path format: /storage/XXXX-XXXX/Android/data/...
                val parts = fullPath.split("/")
                val storageIdx = parts.indexOf("storage")
                if (storageIdx != -1 && storageIdx + 1 < parts.size) {
                    val volumeId = parts[storageIdx + 1]
                    if (volumeId != "emulated" && volumeId != "self") {
                        val rootFile = File("/storage/$volumeId")
                        if (rootFile.exists() && rootFile.canRead()) {
                            val total = rootFile.totalSpace
                            val free = rootFile.freeSpace
                            val used = (total - free).coerceAtLeast(0L)
                            list.add(
                                UsbOtgVolumeInfo(
                                    id = volumeId,
                                    rootPath = rootFile.absolutePath,
                                    displayName = "USB OTG",
                                    totalBytes = total,
                                    freeBytes = free,
                                    usedBytes = used
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return list
    }
}
