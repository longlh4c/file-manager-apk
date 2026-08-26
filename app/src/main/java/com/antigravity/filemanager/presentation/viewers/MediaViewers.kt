package com.antigravity.filemanager.presentation.viewers

import android.net.Uri
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
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import com.antigravity.filemanager.presentation.theme.PureBlack
import com.antigravity.filemanager.presentation.theme.TextPrimary
import com.antigravity.filemanager.presentation.theme.TextSecondary
import com.antigravity.filemanager.utils.FileOpener
import java.io.File
import java.util.Locale

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "svg", "raw", "dng")
private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "3gp", "ts", "m4v")

// A page in the swipeable viewer is either an already-local file, or a cloud file that still
// needs to be downloaded on demand (see CloudViewerSession / CloudMediaViewerViewModel).
private sealed class ViewerEntry {
    abstract val entryName: String

    data class Local(val file: File) : ViewerEntry() {
        override val entryName get() = file.name
    }

    data class Cloud(val item: FileItem) : ViewerEntry() {
        override val entryName get() = item.name
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
    val actualInitialPath = remember(initialPath) { Uri.decode(initialPath) }
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
    val imageEntries = remember(actualInitialPath, actualParentPath, sortOption, cloudAccountId) {
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
    }

    val initialIndex = remember(initialDisplayName, imageEntries) {
        val idx = imageEntries.indexOfFirst { it.entryName == initialDisplayName }
        if (idx >= 0) idx else 0
    }

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
                put(initialDisplayName, media)
            }
        }
    }

    val currentEntry = imageEntries.getOrNull(pagerState.currentPage)
    val currentMedia: ResolvedImageMedia? = when (currentEntry) {
        is ViewerEntry.Local -> ResolvedImageMedia.LocalFile(currentEntry.file)
        is ViewerEntry.Cloud -> resolvedCloudMedia[currentEntry.entryName]
        null -> if (isInitialStream) ResolvedImageMedia.StreamUri(actualInitialPath) else ResolvedImageMedia.LocalFile(File(actualInitialPath))
    }
    val currentLocalFile: File? = (currentMedia as? ResolvedImageMedia.LocalFile)?.file
    val currentName = currentEntry?.entryName ?: initialDisplayName

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
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                        }
                    },
                    actions = {
                        IconButton(
                            enabled = currentLocalFile != null,
                            onClick = {
                                currentLocalFile?.let { FileOpener.shareFiles(context, listOf(it.absolutePath)) }
                            }
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Share", tint = TextPrimary)
                        }
                        IconButton(
                            enabled = currentLocalFile != null,
                            onClick = {
                                currentLocalFile?.let { file ->
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
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val entry = imageEntries[page]
                val resolvedMedia = when (entry) {
                    is ViewerEntry.Local -> ResolvedImageMedia.LocalFile(entry.file)
                    is ViewerEntry.Cloud -> resolvedCloudMedia[entry.entryName]
                }

                if (resolvedMedia == null) {
                    LaunchedEffect(entry.entryName, cloudAccountId) {
                        val cloudEntry = entry as? ViewerEntry.Cloud ?: return@LaunchedEffect
                        val result = cloudMediaViewerViewModel.resolveMedia(cloudAccountId!!, cloudEntry.item)
                        result.getOrNull()?.let { media ->
                            resolvedCloudMedia[entry.entryName] = when (media) {
                                is ResolvedMedia.LocalFile -> ResolvedImageMedia.LocalFile(media.file)
                                is ResolvedMedia.Stream -> ResolvedImageMedia.StreamUri(media.url)
                            }
                        }
                    }
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = TextSecondary)
                    }
                } else {
                    ZoomableImagePage(
                        media = resolvedMedia,
                        onTap = { showControls = !showControls }
                    )
                }
            }
        }
    }
}

@Composable
private fun ZoomableImagePage(
    media: ResolvedImageMedia,
    onTap: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
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
                if (state is coil.compose.AsyncImagePainter.State.Error) {
                    android.util.Log.e("ZoomableImagePage", "Failed to load $displayName ($imageModel)", state.result.throwable)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        )
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
    val actualInitialPath = remember(initialPath) { Uri.decode(initialPath) }
    val actualParentPath = remember(parentPath) { Uri.decode(parentPath) }
    val actualFileName = remember(fileName) { Uri.decode(fileName) }
    // A tapped Dropbox video may already be a pre-signed streamable https URL (see
    // CloudExplorerViewModel.openVideoStream) rather than a downloaded local file — play it
    // directly instead of treating it as a filesystem path.
    val isInitialStream = remember(actualInitialPath) {
        actualInitialPath.startsWith("http://") || actualInitialPath.startsWith("https://")
    }
    val initialDisplayName = remember(actualInitialPath, actualFileName) {
        if (isInitialStream && actualFileName.isNotEmpty()) actualFileName else File(actualInitialPath).name
    }

    val videoEntries = remember(actualInitialPath, actualParentPath, sortOption, cloudAccountId) {
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
    }

    val initialIndex = remember(initialDisplayName, videoEntries) {
        val idx = videoEntries.indexOfFirst { it.entryName == initialDisplayName }
        if (idx >= 0) idx else 0
    }

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
                put(initialDisplayName, media)
            }
        }
    }

    val currentEntry = videoEntries.getOrNull(pagerState.currentPage)
    val currentMedia: ResolvedVideoMedia? = when (currentEntry) {
        is ViewerEntry.Local -> ResolvedVideoMedia.LocalFile(currentEntry.file)
        is ViewerEntry.Cloud -> resolvedCloudMedia[currentEntry.entryName]
        null -> if (isInitialStream) ResolvedVideoMedia.StreamUri(Uri.parse(actualInitialPath)) else ResolvedVideoMedia.LocalFile(File(actualInitialPath))
    }
    val currentLocalFile: File? = (currentMedia as? ResolvedVideoMedia.LocalFile)?.file
    val currentName = currentEntry?.entryName ?: initialDisplayName

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
                        enabled = currentLocalFile != null,
                        onClick = {
                            currentLocalFile?.let { FileOpener.shareFiles(context, listOf(it.absolutePath)) }
                        }
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Share", tint = TextPrimary)
                    }
                    IconButton(
                        enabled = currentLocalFile != null,
                        onClick = {
                            currentLocalFile?.let { file ->
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
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val entry = videoEntries[page]
                val isCurrent = page == pagerState.currentPage
                val resolvedMedia = when (entry) {
                    is ViewerEntry.Local -> ResolvedVideoMedia.LocalFile(entry.file)
                    is ViewerEntry.Cloud -> resolvedCloudMedia[entry.entryName]
                }

                if (resolvedMedia == null) {
                    if (isCurrent) {
                        LaunchedEffect(entry.entryName, cloudAccountId) {
                            val cloudEntry = entry as? ViewerEntry.Cloud ?: return@LaunchedEffect
                            val result = cloudMediaViewerViewModel.resolveMedia(cloudAccountId!!, cloudEntry.item)
                            result.getOrNull()?.let { media ->
                                resolvedCloudMedia[cloudEntry.entryName] = when (media) {
                                    is ResolvedMedia.LocalFile -> ResolvedVideoMedia.LocalFile(media.file)
                                    is ResolvedMedia.Stream -> ResolvedVideoMedia.StreamUri(Uri.parse(media.url))
                                }
                            }
                        }
                    }
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = TextSecondary)
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
                        if (headers.isEmpty()) {
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

                    DisposableEffect(exoPlayer) {
                        onDispose {
                            exoPlayer.release()
                        }
                    }

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
                }
            }
        }
    }
}
