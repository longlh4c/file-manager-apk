package com.antigravity.filemanager.data.local.observer

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
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
 * app's whole lifetime (tied to the Application context, so it's never explicitly unregistered —
 * fine for a single process-lifetime ContentObserver); screens collect [changes] (debounced)
 * instead of each registering their own observer, so Images/Audio/Videos/Documents can quietly
 * pick up new/removed files without the user having to pull-to-refresh.
 *
 * Local folder browsing (Downloads/Main storage/any plain directory) doesn't rely on this —
 * MediaStore only reflects files it has scanned, which can lag behind a file that just landed
 * on disk (FTP transfer, direct copy). See DirectoryWatcher for that path's own FileObserver.
 */
@Singleton
class MediaChangeSignal @Inject constructor(
    @ApplicationContext context: Context
) {
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changes = _changes.asSharedFlow()

    init {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
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
