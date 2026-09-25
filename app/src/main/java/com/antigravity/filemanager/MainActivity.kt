package com.antigravity.filemanager

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.antigravity.filemanager.domain.usecase.DualPanelManager
import com.antigravity.filemanager.domain.usecase.GlobalClipboardManager
import com.antigravity.filemanager.presentation.navigation.AppNavigation
import com.antigravity.filemanager.presentation.theme.DarkBackground
import com.antigravity.filemanager.presentation.theme.FileManagerTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var clipboardManager: GlobalClipboardManager

    @Inject
    lateinit var dualPanelManager: DualPanelManager

    @Inject
    lateinit var preferenceManager: com.antigravity.filemanager.data.local.preferences.PreferenceManager

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Callback after returning from Settings
    }

    private val runtimePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Callback for runtime permissions
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // If an instance of MainActivity already exists in this task and the launcher opens it,
        // finish this duplicate so the existing instance (and its backstack) is brought forward.
        if (!isTaskRoot && intent.hasCategory(Intent.CATEGORY_LAUNCHER) && intent.action == Intent.ACTION_MAIN) {
            super.onCreate(savedInstanceState)
            finish()
            return
        }

        // Manifest declares Theme.FileManager.Splash (brand gradient + owl mascot as
        // windowBackground) so the very first frame isn't a blank window — swap back to the
        // normal theme now, before Compose draws anything, so the splash background is only ever
        // visible for that one cold-start frame.
        setTheme(R.style.Theme_FileManager)
        super.onCreate(savedInstanceState)
        checkAndRequestStoragePermissions()
        // A restored activity (e.g. after the system killed the app in the background) comes back
        // with its original share intent: handling it again copied the files once more and
        // replaced whatever the user had put on the clipboard since.
        if (savedInstanceState == null) handleIncomingShareIntent(intent)

        setContent {
            FileManagerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    AppNavigation(
                        dualPanelManager = dualPanelManager,
                        preferenceManager = preferenceManager
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShareIntent(intent)
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE) {
            lifecycleScope.launch(Dispatchers.IO) {
                val uris = mutableListOf<Uri>()
                if (action == Intent.ACTION_SEND) {
                    val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    }
                    if (uri != null) uris.add(uri)
                } else if (action == Intent.ACTION_SEND_MULTIPLE) {
                    val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                    }
                    if (list != null) uris.addAll(list)
                }

                val copiedPaths = mutableListOf<String>()
                // One folder per share, so the previous share's files (possibly still on the
                // clipboard) are only removed once this one has replaced them.
                val incomingRoot = File(cacheDir, "shared_incoming")
                val cacheDir = File(incomingRoot, System.currentTimeMillis().toString()).apply { mkdirs() }
                for (uri in uris) {
                    try {
                        // The sending app controls both the URI and the display name. A file://
                        // URI into this app's own private data would be copied out to shared
                        // storage on paste, and a name like "../databases/x" would write outside
                        // this cache folder — both are rejected.
                        if (isOwnPrivateFileUri(uri)) continue
                        val fileName = getFileNameFromUri(uri)
                            ?.let { File(it).name }
                            ?.takeIf { it.isNotBlank() && it != "." && it != ".." }
                            ?: "shared_${System.currentTimeMillis()}"
                        val targetFile = com.antigravity.filemanager.data.local.storage.uniqueFile(cacheDir, fileName)
                        contentResolver.openInputStream(uri)?.use { input ->
                            targetFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (targetFile.exists() && targetFile.length() > 0) {
                            copiedPaths.add(targetFile.absolutePath)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                if (copiedPaths.isEmpty()) cacheDir.deleteRecursively()
                if (copiedPaths.isNotEmpty()) {
                    clipboardManager.copy(copiedPaths)
                    // Earlier shares are off the clipboard now; they used to pile up here forever.
                    incomingRoot.listFiles()?.filter { it != cacheDir }?.forEach { it.deleteRecursively() }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            "${copiedPaths.size} file(s) ready to paste. Open any folder and tap PASTE.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun isOwnPrivateFileUri(uri: Uri): Boolean {
        // This app's own FileProvider maps the whole filesystem (root-path), private data included.
        if (uri.scheme == "content") return uri.authority == "${packageName}.fileprovider"
        if (uri.scheme != "file") return false
        val path = uri.path ?: return true
        return try {
            val canonical = File(path).canonicalPath
            val privateRoots = listOfNotNull(applicationInfo.dataDir, filesDir.parent, applicationContext.dataDir?.path)
                .map { File(it).canonicalPath }
            privateRoots.any { canonical == it || canonical.startsWith(it + File.separator) }
        } catch (e: Exception) {
            true
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) {
                            return cursor.getString(nameIndex)
                        }
                    }
                }
            } catch (e: Exception) {}
        }
        return uri.lastPathSegment
    }

    private fun checkAndRequestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    storagePermissionLauncher.launch(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    storagePermissionLauncher.launch(intent)
                }
            }
        } else {
            val permissions = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            runtimePermissionLauncher.launch(permissions)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val mediaPermissions = arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            )
            runtimePermissionLauncher.launch(mediaPermissions)
        }
    }
}
