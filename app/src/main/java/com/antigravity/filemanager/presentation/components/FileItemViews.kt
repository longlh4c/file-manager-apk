package com.antigravity.filemanager.presentation.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.antigravity.filemanager.domain.model.AppSourceBadge
import com.antigravity.filemanager.domain.model.FileItem
import com.antigravity.filemanager.domain.model.FolderBadgeType
import com.antigravity.filemanager.domain.model.MediaFolder
import com.antigravity.filemanager.presentation.theme.*

// A MediaFolder card's thumbnail (folder.latestThumbnailUri, used by both MediaFolderCard's grid
// and AudioListItem's list row below) is requested at this fixed decode size rather than letting
// Coil auto-size to whatever the composable happens to measure — the grid and list views render
// it at different pixel dimensions, so a size tied to layout meant switching between them (or
// just having both variants exist) never shared a decode, and DashboardViewModel's warm-up
// (prefetching these on app start, before the user has opened the category and forced any layout
// pass at all) had nothing fixed to prefetch *at*. One shared size across all three call sites
// means the warm-up's decode is the SAME cache entry the grid/list card ends up asking for.
const val MEDIA_FOLDER_THUMB_PX = 240

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaFolderCard(
    folder: MediaFolder,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    isSelected: Boolean = false,
    modifier: Modifier = Modifier
) {
    val bg = if (isSelected) Color(0xFF00695C).copy(alpha = 0.5f) else Color.Transparent

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Thumbnail with badge
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(DarkCard)
                .then(if (isSelected) Modifier.border(2.dp, TealPrimary, RoundedCornerShape(8.dp)) else Modifier)
        ) {
            if (folder.latestThumbnailUri != null) {
                val context = LocalContext.current
                AsyncImage(
                    model = remember(folder.latestThumbnailUri) {
                        ImageRequest.Builder(context)
                            .data(folder.latestThumbnailUri)
                            .size(MEDIA_FOLDER_THUMB_PX)
                            .build()
                    },
                    contentDescription = folder.name,
                    contentScale = ContentScale.Crop,
                    error = rememberVectorPainter(Icons.Default.Folder),
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }

            // App Source Badge (Bottom Right)
            if (folder.appSourceBadge != AppSourceBadge.NONE) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(getBadgeBgColor(folder.appSourceBadge))
                        .border(1.dp, Color(0xFF1E1E1E), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = getBadgeIcon(folder.appSourceBadge),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Name with item count — was one Text("$name ($count)") truncated as a single string, so
        // a long folder name (a downloader app's package-name-like folder, a Zalo/Messenger chat
        // title, ...) could ellipsize right before the count ever appeared, leaving a grid card
        // with no visible item count at all. Splitting into two Texts lets only the name shrink —
        // weight(1f, fill = false) gives it up to the remaining width without forcing it to claim
        // space it doesn't need, so short names still stay centered as a pair — while the count
        // renders at its natural width and is never the part that gets cut off.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = folder.name,
                color = if (isSelected) TealPrimary else TextPrimary,
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Text(
                text = " (${folder.itemCount})",
                color = if (isSelected) TealPrimary else TextPrimary,
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AudioListItem(
    folder: MediaFolder,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    isSelected: Boolean = false,
    modifier: Modifier = Modifier,
    // Was hardcoded to the music note everywhere — this composable is really just "folder list
    // row with a thumbnail and a type badge", reused below for Images/Videos' list view (their
    // root-folders grid used to ignore the view-mode toggle entirely and stay a grid no matter
    // what was selected; see MediaCategoriesScreen). Defaults keep Audio's call sites unchanged.
    badgeIcon: ImageVector = Icons.Default.MusicNote,
    badgeColor: Color = Color(0xFF26A69A)
) {
    val rowBg = if (isSelected) Color(0xFF00695C) else Color.Transparent

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(rowBg)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Square Album Artwork / Thumbnail with type Badge
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(DarkCard)
        ) {
            if (folder.latestThumbnailUri != null) {
                val context = LocalContext.current
                AsyncImage(
                    model = remember(folder.latestThumbnailUri) {
                        ImageRequest.Builder(context)
                            .data(folder.latestThumbnailUri)
                            .size(MEDIA_FOLDER_THUMB_PX)
                            .build()
                    },
                    contentDescription = folder.name,
                    contentScale = ContentScale.Crop,
                    error = rememberVectorPainter(badgeIcon),
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = badgeIcon,
                        contentDescription = null,
                        tint = TealPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            // Type badge at bottom right
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(2.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(badgeColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = badgeIcon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(11.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Title
        Text(
            text = folder.name,
            color = TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        // Count (e.g. "(16)")
        Text(
            text = "(${folder.itemCount})",
            color = TextSecondary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Normal
        )
    }
}

/**
 * "N subfolders, M files" when the split is actually known (currently only MEGA populates
 * subfolderCount/fileChildCount — see MegaApiClient.listFiles), otherwise falls back to the
 * plain combined count other providers/local storage already show. The split is only trusted
 * when it sums back to itemCount, so an unpopulated 0/0 on a provider that hasn't split its
 * count doesn't get misread as "this folder is empty".
 */
/** The folder a search result actually lives in, shown as a second line so a result found deep
 * in some subfolder isn't just a bare filename with no context for where it is. */
private fun parentPathLabel(path: String): String {
    val trimmed = path.trimEnd('/')
    val parent = trimmed.substringBeforeLast('/', "")
    return parent.ifEmpty { "/" }
}

private fun folderItemCountLabel(file: FileItem): String {
    // The Trash/Rubbish Bin badge is a synthetic, flat listing of deleted entries scattered
    // across the whole account (see DropboxApiClient.listTrash / GoogleDriveApiClient.listTrash)
    // — it never gets its own real itemCount the way an actual folder does, so showing "0
    // folders, 0 items" here isn't a stale placeholder, it's simply always wrong. MEGA's Rubbish
    // Bin is a real node in its tree with an accurate itemCount and is unaffected by this.
    if (file.folderBadgeType == com.antigravity.filemanager.domain.model.FolderBadgeType.TRASH && file.id == "__trash__") {
        return "Recently deleted"
    }
    val hasSplit = file.subfolderCount + file.fileChildCount == file.itemCount
    // Only worth splitting out the folder count when there actually are subfolders — an empty
    // folder or one with only files reads better as a plain "0 items" / "N items" than the
    // redundant "0 folders, N items".
    return if (hasSplit && file.subfolderCount > 0) {
        "${file.subfolderCount} folders, ${file.fileChildCount} items"
    } else {
        "${file.itemCount} items"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileListItem(
    file: FileItem,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    onVisible: ((FileItem) -> Unit)? = null,
    // Recursive search results can span many different folders, so the name alone doesn't say
    // where a match actually lives — pass true (only meaningful while showing search results) to
    // show its parent folder path as a second line under the name/size row.
    showPath: Boolean = false
) {
    if (onVisible != null && !file.isDirectory && file.thumbnailUri == null) {
        // Debounced: a row that only flashes past during a fast scroll gets cancelled here
        // (LaunchedEffect cancels its coroutine when the composable leaves composition) before
        // ever calling onVisible, so a quick fling doesn't fire a fetch for every item it
        // passes over — only rows the user actually settles on for a moment do.
        LaunchedEffect(file.id) {
            kotlinx.coroutines.delay(500)
            onVisible(file)
        }
    }

    val rowBg = if (isSelected) Color(0xFF00695C) else Color.Transparent

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(rowBg)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (file.isDirectory) {
            // Folder Icon with specific inner badge matching Screenshot 000552
            FolderIconWithBadge(badgeType = file.folderBadgeType)
        } else {
            // File Icon or Thumbnail
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(DarkCard),
                contentAlignment = Alignment.Center
            ) {
                if (file.thumbnailUri != null) {
                    AsyncImage(
                        model = file.thumbnailUri,
                        contentDescription = file.name,
                        contentScale = ContentScale.Crop,
                        // Audio files without embedded art (and any other decode failure) fall
                        // back to the normal type icon instead of rendering blank.
                        error = rememberVectorPainter(getFileIcon(file)),
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = getFileIcon(file),
                        contentDescription = null,
                        tint = getFileIconColor(file),
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        // Title & Item Count
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                color = TextPrimary,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (file.isDirectory) folderItemCountLabel(file) else file.formattedSize,
                color = TextSecondary,
                fontSize = 13.sp
            )
            if (showPath) {
                Text(
                    text = parentPathLabel(file.path),
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Modified Date on the right (matching Screenshot 000552)
        if (file.formattedDate.isNotEmpty()) {
            Text(
                text = file.formattedDate,
                color = TextSecondary,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
fun FolderIconWithBadge(badgeType: FolderBadgeType, size: Dp = 46.dp) {
    // Badge/icon-within-badge scale proportionally with the folder icon itself, so a caller
    // asking for a bigger icon (e.g. FileGridCard's floating, no-longer-boxed grid icon) doesn't
    // end up with an undersized badge stuck in the middle of a much larger folder glyph.
    val badgeSize = size * (18f / 46f)
    val badgeIconSize = size * (13f / 46f)
    val badgePadding = size * (4f / 46f)

    Box(
        modifier = Modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        // Yellow Folder background
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            tint = Color(0xFFF6A623),
            modifier = Modifier.size(size)
        )

        // Embedded Badge inside the flap (matching Screenshot 000552)
        when (badgeType) {
            FolderBadgeType.CAMERA -> {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(top = badgePadding)
                        .size(badgeSize)
                        .background(Color.White, RoundedCornerShape(2.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null, tint = Color(0xFFF6A623), modifier = Modifier.size(badgeIconSize))
                }
            }
            FolderBadgeType.DOCUMENTS -> {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(top = badgePadding)
                        .size(badgeSize)
                        .background(Color.White, RoundedCornerShape(2.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Article, contentDescription = null, tint = Color(0xFF1E88E5), modifier = Modifier.size(badgeIconSize))
                }
            }
            FolderBadgeType.DOWNLOAD -> {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(top = badgePadding)
                        .size(badgeSize)
                        .background(Color.White, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = null, tint = Color(0xFF00ACC1), modifier = Modifier.size(badgeIconSize))
                }
            }
            FolderBadgeType.MOVIES -> {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(top = badgePadding)
                        .size(badgeSize)
                        .background(Color.White, RoundedCornerShape(2.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Movie, contentDescription = null, tint = Color(0xFFE53935), modifier = Modifier.size(badgeIconSize))
                }
            }
            FolderBadgeType.MUSIC -> {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(top = badgePadding)
                        .size(badgeSize)
                        .background(Color.White, RoundedCornerShape(2.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color(0xFF26A69A), modifier = Modifier.size(badgeIconSize))
                }
            }
            FolderBadgeType.TRASH -> {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(top = badgePadding)
                        .size(badgeSize)
                        .background(Color.White, RoundedCornerShape(2.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFF757575), modifier = Modifier.size(badgeIconSize))
                }
            }
            FolderBadgeType.STANDARD -> {}
        }
    }
}

fun getFileIcon(file: FileItem): ImageVector {
    if (file.isDirectory) return Icons.Default.Folder
    return when (file.extension) {
        "pdf" -> Icons.Default.PictureAsPdf
        "doc", "docx" -> Icons.Default.Description
        "xls", "xlsx" -> Icons.Default.TableChart
        "ppt", "pptx" -> Icons.Default.Slideshow
        "txt", "log", "json", "xml" -> Icons.Default.Article
        "zip", "rar", "7z", "tar", "gz" -> Icons.Default.FolderZip
        "mp3", "flac", "wav", "m4a", "aac" -> Icons.Default.MusicNote
        "mp4", "mkv", "avi", "mov", "webm" -> Icons.Default.Movie
        "jpg", "jpeg", "png", "webp", "gif" -> Icons.Default.Image
        "apk" -> Icons.Default.Android
        else -> Icons.Default.InsertDriveFile
    }
}

fun getFileIconColor(file: FileItem): Color {
    if (file.isDirectory) return Color(0xFFF6A623)
    return when (file.extension) {
        "pdf" -> Color(0xFFE53935)
        "doc", "docx" -> Color(0xFF1E88E5)
        "xls", "xlsx" -> Color(0xFF43A047)
        "ppt", "pptx" -> Color(0xFFFB8C00)
        "zip", "rar", "7z" -> Color(0xFFFFB300)
        "mp3", "flac", "wav" -> Color(0xFF26A69A)
        "mp4", "mkv", "avi" -> Color(0xFFEF5350)
        "jpg", "png", "webp" -> Color(0xFFAB47BC)
        "apk" -> Color(0xFF7CB342)
        else -> TextSecondary
    }
}

private fun getBadgeIcon(badge: AppSourceBadge): ImageVector = when (badge) {
    AppSourceBadge.MESSENGER -> Icons.Default.ChatBubble
    AppSourceBadge.ZALO -> Icons.Default.Chat
    AppSourceBadge.DOWNLOAD -> Icons.Default.ArrowDownward
    AppSourceBadge.CAMERA -> Icons.Default.CameraAlt
    AppSourceBadge.SCREENSHOT -> Icons.Default.CropFree
    AppSourceBadge.INSTAGRAM -> Icons.Default.Camera
    AppSourceBadge.REDDIT -> Icons.Default.Forum
    AppSourceBadge.NINE_GAG -> Icons.Default.SentimentSatisfied
    AppSourceBadge.OFFICE_LENS -> Icons.Default.DocumentScanner
    AppSourceBadge.GENERIC_AUDIO -> Icons.Default.MusicNote
    AppSourceBadge.GENERIC_VIDEO -> Icons.Default.Videocam
    else -> Icons.Default.Image
}

private fun getBadgeBgColor(badge: AppSourceBadge): Color = when (badge) {
    AppSourceBadge.MESSENGER -> Color(0xFF0084FF)
    AppSourceBadge.ZALO -> Color(0xFF0068FF)
    AppSourceBadge.DOWNLOAD -> Color(0xFF00ACC1)
    AppSourceBadge.CAMERA -> Color(0xFF5C6BC0)
    AppSourceBadge.SCREENSHOT -> Color(0xFF7E57C2)
    AppSourceBadge.INSTAGRAM -> Color(0xFFE91E63)
    AppSourceBadge.REDDIT -> Color(0xFFFF5722)
    AppSourceBadge.NINE_GAG -> Color(0xFF212121)
    AppSourceBadge.OFFICE_LENS -> Color(0xFFFF9800)
    AppSourceBadge.GENERIC_AUDIO -> Color(0xFF26A69A)
    AppSourceBadge.GENERIC_VIDEO -> Color(0xFFE53935)
    else -> Color(0xFF78909C)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileGridCard(
    file: FileItem,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val bg = if (isSelected) Color(0xFF00695C).copy(alpha = 0.5f) else Color.Transparent

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // A real thumbnail (photo/video frame) wants a filled, cropped square — that still gets
        // its own clipped/background box below. A folder or a generic type icon doesn't: caging
        // it inside the same dark square just made it look small and muted (folders already
        // render as a plain flat icon via FolderIconWithBadge — the box around it was purely
        // this composable's own doing). Let those float directly instead, sized larger so the
        // file type actually reads at a glance.
        if (file.isDirectory) {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                contentAlignment = Alignment.Center
            ) {
                FolderIconWithBadge(badgeType = file.folderBadgeType, size = 76.dp)
            }
        } else if (file.thumbnailUri != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(DarkCard)
                    .then(if (isSelected) Modifier.border(2.dp, TealPrimary, RoundedCornerShape(8.dp)) else Modifier),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = file.thumbnailUri,
                    contentDescription = file.name,
                    contentScale = ContentScale.Crop,
                    error = rememberVectorPainter(getFileIcon(file)),
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = getFileIcon(file),
                    contentDescription = null,
                    tint = getFileIconColor(file),
                    modifier = Modifier.size(76.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = file.name,
            color = if (isSelected) TealPrimary else TextPrimary,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 14.sp,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = if (file.isDirectory) folderItemCountLabel(file) else file.formattedSize,
            color = TextSecondary,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileDetailedListItem(
    file: FileItem,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    // See FileListItem's showPath for why this exists.
    showPath: Boolean = false
) {
    val rowBg = if (isSelected) Color(0xFF00695C) else Color.Transparent

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(rowBg)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (file.isDirectory) {
            FolderIconWithBadge(badgeType = file.folderBadgeType)
        } else {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(DarkCard),
                contentAlignment = Alignment.Center
            ) {
                if (file.thumbnailUri != null) {
                    AsyncImage(
                        model = file.thumbnailUri,
                        contentDescription = file.name,
                        contentScale = ContentScale.Crop,
                        error = rememberVectorPainter(getFileIcon(file)),
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = getFileIcon(file),
                        contentDescription = null,
                        tint = getFileIconColor(file),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (file.isDirectory) "Directory • ${folderItemCountLabel(file)}" else "${file.extension.uppercase()} • ${file.formattedSize}",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
                if (file.formattedDate.isNotEmpty()) {
                    Text(
                        text = " • ${file.formattedDate}",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
            if (showPath) {
                Text(
                    text = parentPathLabel(file.path),
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun BottomBarActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clickable { onClick() }
            .padding(vertical = 4.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 11.sp,
            maxLines = 1
        )
    }
}
