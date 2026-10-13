# Cloud and File Operations Rules

## 1. Direct In-Place File Operations
- **No Redundant Subfolders**: Do not create unnecessary subfolders like `Resized/` or nested duplicate folders during image resizing or file processing. Modify files in place or prompt for direct overwrite.
- **Immediate Cache Synchronization**: When an operation modifies, resizes, or moves a file:
  - Invalidate/update `FolderCacheManager` for the directory.
  - Invalidate/update `CloudDownloadCache` and clear Coil memory cache so updated images or thumbnails render immediately.
  - Update `_uiState` immediately without waiting for a separate user-initiated refresh.

## 2. Metadata & Timestamp Preservation
- **Original Date Retention**:
  - Image Resizer must retain the original creation/modification date from the source file (via EXIF `TAG_DATETIME_ORIGINAL`/`TAG_DATETIME` or fallback timestamp).
  - Resized files must write `destFile.setLastModified(...)` with the original timestamp.
- **EXIF Preservation**:
  - All EXIF attributes (Camera make/model, focal length, exposure, GPS latitude/longitude, etc.) must be transferred to newly encoded images.
- **Cloud Upload Timestamps**:
  - Ensure cloud upload APIs pass and preserve file timestamps:
    - **MEGA**: Include `"ts": localFile.lastModified() / 1000` in node creation (`"p"`) and patch cache with the preserved timestamp.
    - **Google Drive**: Set `DriveFile().modifiedTime = DateTime(localFile.lastModified())`.
    - **Dropbox**: Use `.withClientModified(Date(localFile.lastModified()))` in upload and commit builders, and read `clientModified.time`.
    - **TeraBox**: Return `uploadedItem.lastModified = localFile.lastModified()`.

## 3. High-Performance Caching & Navigation
- **Local Media Categories**:
  - Media categories (Images, Videos, Audio, Documents) should query MediaStore efficiently with high TTL caching.
  - Do not show the root internal storage or empty folder hierarchies in media categories; show media items directly or flat folders.
  - Retain scroll position when returning from viewing media.
- **Cloud Tree Caching**:
  - Never trigger a full account-wide recursive fetch on single file upload/deletion/rename. Use localized cache patching (`patchNodeCacheAfterFileUpload`, `patchTreeAfterUpload`).
