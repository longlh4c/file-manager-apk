package com.antigravity.filemanager.presentation.viewers

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.antigravity.filemanager.domain.model.FileItem
import com.antigravity.filemanager.domain.model.FileSortOption
import com.antigravity.filemanager.presentation.theme.*
import com.antigravity.filemanager.presentation.theme.TealPrimary
import com.antigravity.filemanager.presentation.theme.TextPrimary
import com.antigravity.filemanager.presentation.theme.TextSecondary
import com.antigravity.filemanager.utils.FileOpener
import com.antigravity.filemanager.utils.rememberFoldablePosture
import java.io.File
import java.util.Locale


/**
 * Route arguments are already decoded once by Navigation, so a streamed cloud URL arrives as a
 * plain http(s) URL. Decoding it again rewrites its percent-escapes (e.g. TeraBox's "%2F" in the
 * path parameter), which changes the URL and stops it matching the headers stored for it in
 * [CloudStreamHeaders] — the request then goes out without its credentials.
 */
private fun decodeViewerPathArg(arg: String): String =
    if (CloudMediaDataSources.isStreamPath(arg)) arg else Uri.decode(arg)

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "svg", "raw", "dng")
private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "3gp", "ts", "m4v")

// A page in the swipeable viewer is either an already-local file, or a cloud file that still
// needs to be downloaded on demand (see CloudViewerSession / CloudMediaViewerViewModel).
private sealed class ViewerEntry {
    abstract val entryName: String
    /** Identity for resolved-media caches: the name alone collides when entries come from
     * several folders (e.g. search results with two IMG_0001.jpg). */
    abstract val key: String

    data class Local(val file: File) : ViewerEntry() {
        override val entryName get() = file.name
        override val key get() = file.absolutePath
    }

    data class Cloud(val item: FileItem) : ViewerEntry() {
        override val entryName get() = item.name
        override val key get() = item.path
    }
}

/** A resolved playable source for [VideoPlayerScreen] — either a downloaded local file, or (for
 * a cloud video opened via a direct/pre-signed link) a remote URI played directly. */
private sealed class ResolvedVideoMedia {
    data class LocalFile(val file: File) : ResolvedVideoMedia()
    data class StreamUri(val uri: Uri) : ResolvedVideoMedia()
}

/** Same idea as [ResolvedVideoMedia] but for [ImageViewerScreen] — Coil decodes a stream URL
 * directly (with any required headers) instead of a local file. */
private sealed class ResolvedImageMedia {
    data class LocalFile(val file: File) : ResolvedImageMedia()
    data class StreamUri(val url: String) : ResolvedImageMedia()
}

private fun sortSiblingFiles(files: List<File>, sortOption: FileSortOption): List<File> {
    return when (sortOption) {
        FileSortOption.BY_NAME_ASC -> files.sortedBy { it.name.lowercase(Locale.getDefault()) }
        FileSortOption.BY_NAME_DESC -> files.sortedByDescending { it.name.lowercase(Locale.getDefault()) }
        FileSortOption.BY_DATE_DESC -> files.sortedByDescending { it.lastModified() }
        FileSortOption.BY_DATE_ASC -> files.sortedBy { it.lastModified() }
        FileSortOption.BY_SIZE_DESC -> files.sortedByDescending { it.length() }
        FileSortOption.BY_SIZE_ASC -> files.sortedBy { it.length() }
        FileSortOption.BY_TYPE -> files.sortedBy { it.extension.lowercase(Locale.getDefault()) }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    initialPath: String,
    parentPath: String = "",
    sortOption: FileSortOption = FileSortOption.BY_NAME_ASC,
    cloudAccountId: String? = null,
    fileName: String = "",
    onNavigateBack: () -> Unit,
    cloudMediaViewerViewModel: CloudMediaViewerViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val actualInitialPath = remember(initialPath) { decodeViewerPathArg(initialPath) }
    val actualParentPath = remember(parentPath) { Uri.decode(parentPath) }
    val actualFileName = remember(fileName) { Uri.decode(fileName) }
    // A tapped Dropbox/Google Drive image (including .gif) may already be a direct streamable
    // URL (see CloudExplorerViewModel.openMediaStream) rather than a downloaded local file —
    // decode it directly instead of treating it as a filesystem path.
    val isInitialStream = remember(actualInitialPath) {
        actualInitialPath.startsWith("http://") || actualInitialPath.startsWith("https://")
    }
    val initialDisplayName = remember(actualInitialPath, actualFileName) {
        if (isInitialStream && actualFileName.isNotEmpty()) actualFileName else File(actualInitialPath).name
    }

    // Gather sibling images, ordered the same way the caller had them sorted. Cloud files come
    // from the session the explorer screen stashed (there's no local folder to scan for them).
    // Mutable (var + mutableStateOf, not a plain remember val) so deleting the currently-viewed
    // page can drop it from the list in place and land on the next one, instead of the viewer
    // having to close back out to the folder — see the delete handler below.
    var imageEntries by remember(actualInitialPath, actualParentPath, sortOption, cloudAccountId) {
        mutableStateOf(
            if (cloudAccountId != null) {
                val siblings = CloudViewerSession.get(cloudAccountId)
                    ?.filter { !it.isDirectory && it.extension.lowercase(Locale.getDefault()) in IMAGE_EXTENSIONS }
                    ?.map { ViewerEntry.Cloud(it) }
                if (!siblings.isNullOrEmpty()) siblings else listOf(ViewerEntry.Local(File(actualInitialPath)))
            } else {
                val targetParent = if (actualParentPath.isNotEmpty()) File(actualParentPath) else File(actualInitialPath).parentFile
                val found = targetParent?.listFiles()?.filter {
                    it.isFile && !it.name.startsWith(".") && it.extension.lowercase(Locale.getDefault()) in IMAGE_EXTENSIONS
                }?.let { sortSiblingFiles(it, sortOption) }
                if (!found.isNullOrEmpty()) found.map { ViewerEntry.Local(it) } else listOf(ViewerEntry.Local(File(actualInitialPath)))
            }
        )
    }

    val initialIndex = remember(initialDisplayName, imageEntries) {
        val idx = imageEntries.indexOfFirst { it.entryName == initialDisplayName }
        if (idx >= 0) idx else 0
    }
    val initialKey = imageEntries.firstOrNull { it.entryName == initialDisplayName }?.key ?: initialDisplayName
    // Per entry: bumped to retry a failed resolve; present in failedCloudMedia while failed.
    val resolveAttempt = remember { mutableStateMapOf<String, Int>() }
    val failedCloudMedia = remember { mutableStateMapOf<String, String>() }

    val pagerState = rememberPagerState(
        initialPage = initialIndex,
        pageCount = { imageEntries.size }
    )

    var showControls by remember { mutableStateOf(true) }

    // The tapped file is already resolved (local or stream); other cloud pages resolve on
    // demand as swiped to.
    val resolvedCloudMedia = remember(cloudAccountId) {
        mutableStateMapOf<String, ResolvedImageMedia>().apply {
            if (cloudAccountId != null) {
                val media = if (isInitialStream) {
                    ResolvedImageMedia.StreamUri(actualInitialPath)
                } else {
                    ResolvedImageMedia.LocalFile(File(actualInitialPath))
                }
                put(initialKey, media)
            }
        }
    }

    val currentEntry = imageEntries.getOrNull(pagerState.currentPage)
    val currentMedia: ResolvedImageMedia? = when (currentEntry) {
        is ViewerEntry.Local -> ResolvedImageMedia.LocalFile(currentEntry.file)
        is ViewerEntry.Cloud -> resolvedCloudMedia[currentEntry.key]
        null -> if (isInitialStream) ResolvedImageMedia.StreamUri(actualInitialPath) else ResolvedImageMedia.LocalFile(File(actualInitialPath))
    }
    val currentLocalFile: File? = (currentMedia as? ResolvedImageMedia.LocalFile)?.file
    val currentName = currentEntry?.entryName ?: initialDisplayName

    val coroutineScope = rememberCoroutineScope()

    // Automatically persist the current viewed image position so that if the app is minimized
    // or killed by the OS in the background, it can restore right where the user left off.
    LaunchedEffect(currentEntry) {
        if (currentEntry != null) {
            val entryPath = when (currentEntry) {
                is ViewerEntry.Local -> currentEntry.file.absolutePath
                is ViewerEntry.Cloud -> currentEntry.item.path
            }
            cloudMediaViewerViewModel.saveLastViewedImage(
                path = entryPath,
                parentPath = actualParentPath,
                sortOption = sortOption.name,
                cloudAccountId = cloudAccountId,
                fileName = currentEntry.entryName
            )
        }
    }

    val handleBack: () -> Unit = {
        coroutineScope.launch {
            cloudMediaViewerViewModel.clearLastViewedImage()
        }
        onNavigateBack()
    }

    BackHandler {
        handleBack()
    }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }
    var isPreparingAction by remember { mutableStateOf(false) }

    // Share/Open With need an actual local file — a cloud image opened via direct stream URL
    // (see resolveMedia's allowStreaming preference, used to avoid downloading just to view it)
    // has no local file at all, which silently disabled both buttons with no way to use them.
    // Force a real download on demand the first time either is tapped for a streamed entry, then
    // reuse that download for the rest of this viewing session exactly like swiping normally
    // resolves and caches each page.
    fun withLocalFile(onReady: (File) -> Unit) {
        val readyFile = currentLocalFile
        if (readyFile != null) {
            onReady(readyFile)
            return
        }
        val cloudEntry = currentEntry as? ViewerEntry.Cloud ?: return
        if (cloudAccountId == null || isPreparingAction) return
        isPreparingAction = true
        coroutineScope.launch {
            val result = cloudMediaViewerViewModel.resolveMedia(cloudAccountId, cloudEntry.item, allowStreaming = false)
            isPreparingAction = false
            result.getOrNull()?.let { media ->
                if (media is ResolvedMedia.LocalFile) {
                    resolvedCloudMedia[cloudEntry.key] = ResolvedImageMedia.LocalFile(media.file)
                    onReady(media.file)
                }
            }
        }
    }

    if (showDeleteConfirm && currentEntry != null) {
        AlertDialog(
            onDismissRequest = { if (!isDeleting) showDeleteConfirm = false },
            title = { Text("Delete", color = TextPrimary, fontWeight = FontWeight.SemiBold) },
            text = { Text("Delete \"$currentName\"? It will be moved to the recycle bin.", color = TextSecondary) },
            confirmButton = {
                TextButton(
                    enabled = !isDeleting,
                    onClick = {
                        isDeleting = true
                        coroutineScope.launch {
                            val result = when (currentEntry) {
                                is ViewerEntry.Local -> cloudMediaViewerViewModel.deleteLocalFile(currentEntry.file.absolutePath)
                                is ViewerEntry.Cloud -> cloudMediaViewerViewModel.deleteCloudFile(cloudAccountId ?: "", currentEntry.item.path)
                            }
                            isDeleting = false
                            showDeleteConfirm = false
                            if (result.isSuccess) {
                                // Drop the deleted page and land on the next one (or the new
                                // last page, if it was the last one) instead of closing the
                                // viewer — only back out if that was the only file left.
                                val deletedIndex = pagerState.currentPage
                                val updated = imageEntries.toMutableList().also {
                                    if (deletedIndex in it.indices) it.removeAt(deletedIndex)
                                }
                                if (updated.isEmpty()) {
                                    handleBack()
                                } else {
                                    imageEntries = updated
                                    val targetIndex = deletedIndex.coerceAtMost(updated.size - 1)
                                    coroutineScope.launch { pagerState.scrollToPage(targetIndex) }
                                }
                            }
                        }
                    }
                ) {
                    // A remote cloud delete (Dropbox/Drive/MEGA) is a real network round trip
                    // that can take a couple seconds — the "Deleting…" text alone, with nothing
                    // else in the dialog changing, read as the whole thing being frozen. A visible
                    // spinner makes it obvious something is actually happening.
                    if (isDeleting) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = TealPrimary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Deleting…", color = PastelCoral)
                        }
                    } else {
                        Text("Delete", color = PastelCoral, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(enabled = !isDeleting, onClick = { showDeleteConfirm = false }) { Text("Cancel", color = TextSecondary) }
            },
            containerColor = DarkCard
        )
    }

    val posture = rememberFoldablePosture()

    Scaffold(
        topBar = {
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = currentName,
                                color = TextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (imageEntries.size > 1) {
                                Text(
                                    text = "${pagerState.currentPage + 1} / ${imageEntries.size}",
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = handleBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                        }
                    },
                    actions = {
                        IconButton(
                            enabled = currentEntry != null && !isPreparingAction,
                            onClick = {
                                withLocalFile { file -> FileOpener.shareFiles(context, listOf(file.absolutePath)) }
                            }
                        ) {
                            if (isPreparingAction) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = TealPrimary, strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Share, contentDescription = "Share", tint = TextPrimary)
                            }
                        }
                        IconButton(
                            enabled = currentEntry != null && !isPreparingAction,
                            onClick = {
                                withLocalFile { file ->
                                    val item = FileItem(
                                        id = file.absolutePath,
                                        name = file.name,
                                        path = file.absolutePath,
                                        size = file.length(),
                                        lastModified = file.lastModified(),
                                        isDirectory = false,
                                        mimeType = "image/${file.extension}",
                                        extension = file.extension
                                    )
                                    FileOpener.openWith(context, item)
                                }
                            }
                        ) {
                            Icon(Icons.Default.OpenWith, contentDescription = "Open with", tint = TextPrimary)
                        }
                        IconButton(
                            enabled = currentEntry != null,
                            onClick = { showDeleteConfirm = true }
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = TextPrimary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xCC000000))
                )
            }
        },
        containerColor = PureBlack
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(PureBlack)
                .padding(paddingValues)
        ) {
            HorizontalPager(
                state = pagerState,
                // Swipe right to advance to the next image (and left for the previous one) —
                // the opposite of HorizontalPager's own default page order, which advances on a
                // left swipe.
                reverseLayout = true,
                beyondBoundsPageCount = 1,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val entry = imageEntries[page]
                val resolvedMedia = when (entry) {
                    is ViewerEntry.Local -> ResolvedImageMedia.LocalFile(entry.file)
                    is ViewerEntry.Cloud -> resolvedCloudMedia[entry.key]
                }

                if (resolvedMedia == null) {
                    LaunchedEffect(entry.key, cloudAccountId, resolveAttempt[entry.key]) {
                        val cloudEntry = entry as? ViewerEntry.Cloud ?: return@LaunchedEffect
                        val result = cloudMediaViewerViewModel.resolveMedia(cloudAccountId!!, cloudEntry.item)
                        result.onFailure { failedCloudMedia[cloudEntry.key] = it.message ?: "Could not load this file" }
                        result.getOrNull()?.let { media ->
                            resolvedCloudMedia[entry.key] = when (media) {
                                is ResolvedMedia.LocalFile -> ResolvedImageMedia.LocalFile(media.file)
                                is ResolvedMedia.Stream -> ResolvedImageMedia.StreamUri(media.url)
                            }
                        }
                    }
                    CloudResolvePlaceholder(failedCloudMedia[entry.key]) {
                        failedCloudMedia.remove(entry.key)
                        resolveAttempt[entry.key] = (resolveAttempt[entry.key] ?: 0) + 1
                    }
                } else {
                    ZoomableImagePage(
                        media = resolvedMedia,
                        pagerState = pagerState,
                        page = page,
                        isUnfolded = posture.isUnfolded,
                        onTap = { showControls = !showControls }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZoomableImagePage(
    media: ResolvedImageMedia,
    pagerState: PagerState,
    page: Int,
    isUnfolded: Boolean,
    onTap: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var intrinsicSize by remember { mutableStateOf<Size?>(null) }
    // A streamed image shows nothing (a black page) until Coil has fetched and decoded it.
    var isImageLoading by remember(media) { mutableStateOf(true) }
    val density = LocalDensity.current
    val targetSpacingPx = remember(density) { with(density) { 16.dp.toPx() } }

    val displayName = when (media) {
        is ResolvedImageMedia.LocalFile -> media.file.name
        is ResolvedImageMedia.StreamUri -> media.url.substringAfterLast("/")
    }
    // A stream URL that needs headers (e.g. Google Drive's bearer token) gets them attached by
    // FileManagerApp's shared OkHttpClient network interceptor (keyed by this same URL via
    // CloudStreamHeaders) — a plain String model is enough here, no per-request ImageRequest
    // needed, and unlike headers set directly on the request, a network interceptor still runs
    // if the response 302s to a different host.
    val imageModel = when (media) {
        is ResolvedImageMedia.LocalFile -> media.file.absolutePath
        is ResolvedImageMedia.StreamUri -> media.url
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1.05f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                // Only react once a second finger is down, so a single-finger drag at scale 1
                // is left untouched for the HorizontalPager to treat as a page swipe.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size >= 2) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            scale = (scale * zoomChange).coerceIn(1f, 5f)
                            offset = if (scale > 1.05f) {
                                val maxOffsetX = (size.width * (scale - 1)) / 2
                                val maxOffsetY = (size.height * (scale - 1)) / 2
                                Offset(
                                    x = (offset.x + panChange.x).coerceIn(-maxOffsetX, maxOffsetX),
                                    y = (offset.y + panChange.y).coerceIn(-maxOffsetY, maxOffsetY)
                                )
                            } else {
                                Offset.Zero
                            }
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = imageModel,
            contentDescription = displayName,
            contentScale = ContentScale.Fit,
            onState = { state ->
                isImageLoading = state is coil.compose.AsyncImagePainter.State.Loading ||
                    state is coil.compose.AsyncImagePainter.State.Empty
                if (state is coil.compose.AsyncImagePainter.State.Success) {
                    intrinsicSize = state.painter.intrinsicSize
                } else if (state is coil.compose.AsyncImagePainter.State.Error) {
                    android.util.Log.e("ZoomableImagePage", "Failed to load $displayName ($imageModel)", state.result.throwable)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale

                    val dynamicShift: Float = if (isUnfolded && scale <= 1.05f) {
                        val pageOffset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                        val absOffset = kotlin.math.abs(pageOffset)
                        if (absOffset < 1.0f) {
                            val containerWidth = size.width
                            val containerHeight = size.height
                            if (containerWidth > 0f && containerHeight > 0f) {
                                val currentIntrinsic = intrinsicSize
                                val imageAspect = if (currentIntrinsic != null && currentIntrinsic.width > 0f && currentIntrinsic.height > 0f) {
                                    currentIntrinsic.width / currentIntrinsic.height
                                } else {
                                    if (containerWidth >= containerHeight) 0.75f else (containerWidth / containerHeight)
                                }
                                val containerAspect = containerWidth / containerHeight
                                if (containerAspect > imageAspect) {
                                    val renderedWidth = containerHeight * imageAspect
                                    val sideMargin = (containerWidth - renderedWidth) / 2f
                                    val excessVoid = (sideMargin * 2f - targetSpacingPx).coerceAtLeast(0f)
                                    val maxShift = (excessVoid / 2f).coerceAtMost(containerWidth * 0.25f)
                                    val progress = kotlin.math.sin(absOffset * Math.PI.toFloat())
                                    val shiftMagnitude = maxShift * progress
                                    if (pageOffset < 0f) shiftMagnitude else -shiftMagnitude
                                } else 0f
                            } else 0f
                        } else 0f
                    } else 0f

                    translationX = offset.x + dynamicShift
                    translationY = offset.y
                }
        )
        if (isImageLoading) {
            CircularProgressIndicator(color = TealPrimary)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun VideoPlayerScreen(
    initialPath: String,
    parentPath: String = "",
    sortOption: FileSortOption = FileSortOption.BY_NAME_ASC,
    cloudAccountId: String? = null,
    fileName: String = "",
    onNavigateBack: () -> Unit,
    cloudMediaViewerViewModel: CloudMediaViewerViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val actualInitialPath = remember(initialPath) { decodeViewerPathArg(initialPath) }
    val actualParentPath = remember(parentPath) { Uri.decode(parentPath) }
    val actualFileName = remember(fileName) { Uri.decode(fileName) }
    // A tapped Dropbox video may already be a pre-signed streamable https URL (see
    // CloudExplorerViewModel.openVideoStream) rather than a downloaded local file — play it
    // directly instead of treating it as a filesystem path.
    val isInitialStream = remember(actualInitialPath) { CloudMediaDataSources.isStreamPath(actualInitialPath) }
    val initialDisplayName = remember(actualInitialPath, actualFileName) {
        if (isInitialStream && actualFileName.isNotEmpty()) actualFileName else File(actualInitialPath).name
    }

    // Mutable for the same reason as ImageViewerScreen's imageEntries — deleting the current
    // page drops it and lands on the next one instead of closing the viewer.
    var videoEntries by remember(actualInitialPath, actualParentPath, sortOption, cloudAccountId) {
        mutableStateOf(
            if (cloudAccountId != null) {
                val siblings = CloudViewerSession.get(cloudAccountId)
                    ?.filter { !it.isDirectory && it.extension.lowercase(Locale.getDefault()) in VIDEO_EXTENSIONS }
                    ?.map { ViewerEntry.Cloud(it) }
                if (!siblings.isNullOrEmpty()) siblings else listOf(ViewerEntry.Local(File(actualInitialPath)))
            } else {
                val targetParent = if (actualParentPath.isNotEmpty()) File(actualParentPath) else File(actualInitialPath).parentFile
                val found = targetParent?.listFiles()?.filter {
                    it.isFile && !it.name.startsWith(".") && it.extension.lowercase(Locale.getDefault()) in VIDEO_EXTENSIONS
                }?.let { sortSiblingFiles(it, sortOption) }
                if (!found.isNullOrEmpty()) found.map { ViewerEntry.Local(it) } else listOf(ViewerEntry.Local(File(actualInitialPath)))
            }
        )
    }

    val initialIndex = remember(initialDisplayName, videoEntries) {
        val idx = videoEntries.indexOfFirst { it.entryName == initialDisplayName }
        if (idx >= 0) idx else 0
    }
    val initialKey = videoEntries.firstOrNull { it.entryName == initialDisplayName }?.key ?: initialDisplayName
    // Per entry: bumped to retry a failed resolve; present in failedCloudMedia while failed.
    val resolveAttempt = remember { mutableStateMapOf<String, Int>() }
    val failedCloudMedia = remember { mutableStateMapOf<String, String>() }

    val pagerState = rememberPagerState(
        initialPage = initialIndex,
        pageCount = { videoEntries.size }
    )

    val resolvedCloudMedia = remember(cloudAccountId) {
        mutableStateMapOf<String, ResolvedVideoMedia>().apply {
            if (cloudAccountId != null) {
                val media = if (isInitialStream) {
                    ResolvedVideoMedia.StreamUri(Uri.parse(actualInitialPath))
                } else {
                    ResolvedVideoMedia.LocalFile(File(actualInitialPath))
                }
                put(initialKey, media)
            }
        }
    }

    val currentEntry = videoEntries.getOrNull(pagerState.currentPage)
    val currentMedia: ResolvedVideoMedia? = when (currentEntry) {
        is ViewerEntry.Local -> ResolvedVideoMedia.LocalFile(currentEntry.file)
        is ViewerEntry.Cloud -> resolvedCloudMedia[currentEntry.key]
        null -> if (isInitialStream) ResolvedVideoMedia.StreamUri(Uri.parse(actualInitialPath)) else ResolvedVideoMedia.LocalFile(File(actualInitialPath))
    }
    val currentLocalFile: File? = (currentMedia as? ResolvedVideoMedia.LocalFile)?.file
    val currentName = currentEntry?.entryName ?: initialDisplayName

    val coroutineScope = rememberCoroutineScope()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }
    var isPreparingAction by remember { mutableStateOf(false) }

    // See ImageViewerScreen's matching comment — a streamed cloud video has no local file at all,
    // which silently disabled Share/Open With with no way to use them. Force a real download on
    // demand the first time either is tapped, reusing it for the rest of this viewing session.
    fun withLocalFile(onReady: (File) -> Unit) {
        val readyFile = currentLocalFile
        if (readyFile != null) {
            onReady(readyFile)
            return
        }
        val cloudEntry = currentEntry as? ViewerEntry.Cloud ?: return
        if (cloudAccountId == null || isPreparingAction) return
        isPreparingAction = true
        coroutineScope.launch {
            val result = cloudMediaViewerViewModel.resolveMedia(cloudAccountId, cloudEntry.item, allowStreaming = false)
            isPreparingAction = false
            result.getOrNull()?.let { media ->
                if (media is ResolvedMedia.LocalFile) {
                    resolvedCloudMedia[cloudEntry.key] = ResolvedVideoMedia.LocalFile(media.file)
                    onReady(media.file)
                }
            }
        }
    }

    if (showDeleteConfirm && currentEntry != null) {
        AlertDialog(
            onDismissRequest = { if (!isDeleting) showDeleteConfirm = false },
            title = { Text("Delete", color = TextPrimary, fontWeight = FontWeight.SemiBold) },
            text = { Text("Delete \"$currentName\"? It will be moved to the recycle bin.", color = TextSecondary) },
            confirmButton = {
                TextButton(
                    enabled = !isDeleting,
                    onClick = {
                        isDeleting = true
                        coroutineScope.launch {
                            val result = when (currentEntry) {
                                is ViewerEntry.Local -> cloudMediaViewerViewModel.deleteLocalFile(currentEntry.file.absolutePath)
                                is ViewerEntry.Cloud -> cloudMediaViewerViewModel.deleteCloudFile(cloudAccountId ?: "", currentEntry.item.path)
                            }
                            isDeleting = false
                            showDeleteConfirm = false
                            if (result.isSuccess) {
                                // See ImageViewerScreen's matching delete handler.
                                val deletedIndex = pagerState.currentPage
                                val updated = videoEntries.toMutableList().also {
                                    if (deletedIndex in it.indices) it.removeAt(deletedIndex)
                                }
                                if (updated.isEmpty()) {
                                    onNavigateBack()
                                } else {
                                    videoEntries = updated
                                    val targetIndex = deletedIndex.coerceAtMost(updated.size - 1)
                                    coroutineScope.launch { pagerState.scrollToPage(targetIndex) }
                                }
                            }
                        }
                    }
                ) {
                    // A remote cloud delete (Dropbox/Drive/MEGA) is a real network round trip
                    // that can take a couple seconds — the "Deleting…" text alone, with nothing
                    // else in the dialog changing, read as the whole thing being frozen. A visible
                    // spinner makes it obvious something is actually happening.
                    if (isDeleting) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = TealPrimary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Deleting…", color = PastelCoral)
                        }
                    } else {
                        Text("Delete", color = PastelCoral, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(enabled = !isDeleting, onClick = { showDeleteConfirm = false }) { Text("Cancel", color = TextSecondary) }
            },
            containerColor = DarkCard
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = currentName,
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (videoEntries.size > 1) {
                            Text(
                                text = "${pagerState.currentPage + 1} / ${videoEntries.size}",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                actions = {
                    IconButton(
                        enabled = currentEntry != null && !isPreparingAction,
                        onClick = {
                            withLocalFile { file -> FileOpener.shareFiles(context, listOf(file.absolutePath)) }
                        }
                    ) {
                        if (isPreparingAction) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = TealPrimary, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Share, contentDescription = "Share", tint = TextPrimary)
                        }
                    }
                    IconButton(
                        enabled = currentEntry != null && !isPreparingAction,
                        onClick = {
                            withLocalFile { file ->
                                val item = FileItem(
                                    id = file.absolutePath,
                                    name = file.name,
                                    path = file.absolutePath,
                                    size = file.length(),
                                    lastModified = file.lastModified(),
                                    isDirectory = false,
                                    mimeType = "video/${file.extension}",
                                    extension = file.extension
                                )
                                FileOpener.openWith(context, item)
                            }
                        }
                    ) {
                        Icon(Icons.Default.OpenWith, contentDescription = "Open with", tint = TextPrimary)
                    }
                    IconButton(
                        enabled = currentEntry != null,
                        onClick = { showDeleteConfirm = true }
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xCC000000))
            )
        },
        containerColor = PureBlack
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(PureBlack)
                .padding(paddingValues)
        ) {
            HorizontalPager(
                state = pagerState,
                // Same swipe convention as the image viewer — right advances, left goes back.
                reverseLayout = true,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val entry = videoEntries[page]
                val isCurrent = page == pagerState.currentPage
                val resolvedMedia = when (entry) {
                    is ViewerEntry.Local -> ResolvedVideoMedia.LocalFile(entry.file)
                    is ViewerEntry.Cloud -> resolvedCloudMedia[entry.key]
                }

                if (resolvedMedia == null) {
                    if (isCurrent) {
                        LaunchedEffect(entry.key, cloudAccountId, resolveAttempt[entry.key]) {
                            val cloudEntry = entry as? ViewerEntry.Cloud ?: return@LaunchedEffect
                            val result = cloudMediaViewerViewModel.resolveMedia(cloudAccountId!!, cloudEntry.item)
                            result.onFailure { failedCloudMedia[cloudEntry.key] = it.message ?: "Could not load this file" }
                            result.getOrNull()?.let { media ->
                                resolvedCloudMedia[cloudEntry.key] = when (media) {
                                    is ResolvedMedia.LocalFile -> ResolvedVideoMedia.LocalFile(media.file)
                                    is ResolvedMedia.Stream -> ResolvedVideoMedia.StreamUri(Uri.parse(media.url))
                                }
                            }
                        }
                    }
                    CloudResolvePlaceholder(failedCloudMedia[entry.key]) {
                        failedCloudMedia.remove(entry.key)
                        resolveAttempt[entry.key] = (resolveAttempt[entry.key] ?: 0) + 1
                    }
                } else if (isCurrent) {
                    val playbackUri = when (resolvedMedia) {
                        is ResolvedVideoMedia.LocalFile -> Uri.fromFile(resolvedMedia.file)
                        is ResolvedVideoMedia.StreamUri -> resolvedMedia.uri
                    }
                    // A stream URI may need headers (e.g. Google Drive's bearer token) — Dropbox's
                    // pre-signed link needs none, so this is empty for that case and ExoPlayer's
                    // default HTTP data source is used either way.
                    val exoPlayer = remember(playbackUri) {
                        val headers = CloudStreamHeaders.get(playbackUri.toString())
                        val player = ExoPlayer.Builder(context).build()
                        val decryptingSource = CloudMediaDataSources.get(playbackUri.toString())
                        if (decryptingSource != null) {
                            // MEGA: ExoPlayer reads through the on-demand decrypting source.
                            val dataSourceFactory = androidx.media3.datasource.DataSource.Factory {
                                MediaDataSourceDataSource(decryptingSource, playbackUri)
                            }
                            val mediaSource = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(dataSourceFactory)
                                .createMediaSource(MediaItem.fromUri(playbackUri))
                            player.setMediaSource(mediaSource)
                        } else if (headers.isEmpty()) {
                            player.setMediaItem(MediaItem.fromUri(playbackUri))
                        } else {
                            val dataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                                .setDefaultRequestProperties(headers)
                            val mediaSource = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(dataSourceFactory)
                                .createMediaSource(MediaItem.fromUri(playbackUri))
                            player.setMediaSource(mediaSource)
                        }
                        player.apply {
                            prepare()
                            playWhenReady = true
                        }
                    }

                    // PlayerView stays black until the first frame is ready — for a stream that
                    // means the whole connect/buffer time, so show a spinner over it meanwhile.
                    var isBuffering by remember(exoPlayer) { mutableStateOf(true) }
                    DisposableEffect(exoPlayer) {
                        val listener = object : androidx.media3.common.Player.Listener {
                            override fun onPlaybackStateChanged(playbackState: Int) {
                                isBuffering = playbackState == androidx.media3.common.Player.STATE_BUFFERING ||
                                    (playbackState == androidx.media3.common.Player.STATE_IDLE && exoPlayer.playerError == null)
                            }

                            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                                isBuffering = false
                            }
                        }
                        exoPlayer.addListener(listener)
                        onDispose {
                            exoPlayer.removeListener(listener)
                            exoPlayer.release()
                        }
                    }

                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    player = exoPlayer
                                    useController = true
                                }
                            },
                            update = { view -> view.player = exoPlayer },
                            modifier = Modifier.fillMaxSize()
                        )
                        if (isBuffering) {
                            CircularProgressIndicator(color = TealPrimary)
                        }
                    }
                }
            }
        }
    }
}

/** Shown while a cloud page resolves; on failure shows the error with a retry instead of
 * spinning forever (a failed resolve used to be silently ignored). */
@Composable
private fun CloudResolvePlaceholder(error: String?, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (error == null) {
            CircularProgressIndicator(color = TealPrimary)
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Text(error, color = TextSecondary, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(onClick = onRetry) { Text("Retry", color = TealPrimary, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}
