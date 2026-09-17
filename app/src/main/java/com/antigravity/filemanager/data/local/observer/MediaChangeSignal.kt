package com.antigravity.filemanager.data.local.observer

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fires whenever MediaStore reports a change anywhere under external storage — a new photo,
 * a download once the media scanner picks it up, a deleted file, etc. Registered once for the
 * app's whole lifetime (tied to the Application context, so it's never explicitly unregistered).
 *
 * Runs on a dedicated background HandlerThread instead of the MainLooper so that bulk file writes
 * (such as thousands of files landing via an FTP transfer) do not flood the main UI thread message
 * queue, preventing ANRs and UI freezes.
 */
@Singleton
class MediaChangeSignal @Inject constructor(
    @ApplicationContext context: Context
) {
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changes = _changes.asSharedFlow()

    private val observerThread = HandlerThread("MediaChangeSignalObserver").apply { start() }

    init {
        val observer = object : ContentObserver(Handler(observerThread.looper)) {
            override fun onChange(selfChange: Boolean) {
                _changes.tryEmit(Unit)
            }
        }
        // notifyForDescendants=true: a change to any row under this URI (any media type, any
        // folder) notifies here, not just exact-URI matches.
        context.contentResolver.registerContentObserver(
            MediaStore.Files.getContentUri("external"),
            true,
            observer
        )
    }
}
