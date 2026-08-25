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
 * a Dropbox video opened via a pre-signed streamable link) a remote URI played directly. */
private sealed class ResolvedVideoMedia {
    data class LocalFile(val file: File) : ResolvedVideoMedia()
    data class StreamUri(val uri: Uri) : ResolvedVideoMedia()
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
    onNavigateBack: () -> Unit,
    cloudMediaViewerViewModel: CloudMediaViewerViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val actualInitialPath = remember(initialPath) { Uri.decode(initialPath) }
    val actualParentPath = remember(parentPath) { Uri.decode(parentPath) }

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

    val initialIndex = remember(actualInitialPath, imageEntries) {
        val initialName = File(actualInitialPath).name
        val idx = imageEntries.indexOfFirst { it.entryName == initialName }
        if (idx >= 0) idx else 0
    }

    val pagerState = rememberPagerState(
        initialPage = initialIndex,
        pageCount = { imageEntries.size }
    )

    var showControls by remember { mutableStateOf(true) }

    // The tapped file is already downloaded; other cloud pages resolve on demand as swiped to.
    val resolvedCloudFiles = remember(cloudAccountId) {
        mutableStateMapOf<String, File>().apply {
            if (cloudAccountId != null) put(File(actualInitialPath).name, File(actualInitialPath))
        }
    }

    val currentEntry = imageEntries.getOrNull(pagerState.currentPage)
    val currentLocalFile: File? = when (currentEntry) {
        is ViewerEntry.Local -> currentEntry.file
        is ViewerEntry.Cloud -> resolvedCloudFiles[currentEntry.entryName]
        null -> File(actualInitialPath)
    }
    val currentName = currentEntry?.entryName ?: File(actualInitialPath).name

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
                when (val entry = imageEntries[page]) {
                    is ViewerEntry.Local -> ZoomableImagePage(
                        file = entry.file,
                        onTap = { showControls = !showControls }
                    )
                    is ViewerEntry.Cloud -> {
                        val resolved = resolvedCloudFiles[entry.entryName]
                        if (resolved != null) {
                            ZoomableImagePage(
                                file = resolved,
                                onTap = { showControls = !showControls }
                            )
                        } else {
                            LaunchedEffect(entry.entryName, cloudAccountId) {
                                val result = cloudMediaViewerViewModel.resolveLocalFile(cloudAccountId!!, entry.item)
                                result.getOrNull()?.let { resolvedCloudFiles[entry.entryName] = it }
                            }
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = TextSecondary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomableImagePage(
    file: File,
    onTap: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

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
            model = file.absolutePath,
            contentDescription = file.name,
            contentScale = ContentScale.Fit,
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
                            val result = cloudMediaViewerViewModel.resolveLocalFile(cloudAccountId!!, cloudEntry.item)
                            result.getOrNull()?.let { resolvedCloudMedia[cloudEntry.entryName] = ResolvedVideoMedia.LocalFile(it) }
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
                    val exoPlayer = remember(playbackUri) {
                        ExoPlayer.Builder(context).build().apply {
                            setMediaItem(MediaItem.fromUri(playbackUri))
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
