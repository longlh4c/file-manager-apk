---
name: cloud-storage-integration
description: >-
  Use this skill when developing, debugging, or optimizing cloud storage integrations in OwlFM+ across Google Drive, Dropbox, MEGA, and TeraBox, including tree caching, upload/download streaming, and timestamp preservation.
---

# Cloud Storage Integration Skill

This runbook guides agents through working with the 4 supported cloud storage providers in OwlFM+: **Google Drive**, **Dropbox**, **MEGA**, and **TeraBox**.

## 1. Provider Architecture Overview

Location: `app/src/main/java/com/antigravity/filemanager/data/remote/cloud/api/`

| Provider | Client Class | Key Protocol / Library | Cache Mechanism |
|---|---|---|---|
| **Google Drive** | `GoogleDriveApiClient` | Google Drive API v3 (OAuth2) | `FolderCacheManager` |
| **Dropbox** | `DropboxApiClient` | Dropbox Java SDK v2 (OAuth2) | `treeCache` (disk & memory) + `FolderCacheManager` |
| **MEGA** | `MegaApiClient` | Mega JSON API + AES-128-CTR + MAC | `nodeTreeCache` (memory) + `FolderCacheManager` |
| **TeraBox** | `TeraBoxApiClient` | Baidu PCS / Netdisk web API (Cookie-based) | `FolderCacheManager` |

## 2. In-Memory Tree Patching Rules

To keep navigation snappy, never trigger full-account re-fetches after uploads or mutations:

### MEGA (`MegaApiClient.kt`)
- When uploading a file, post `"a": "p"` with node handle, parent handle, encrypted attributes, and `"ts": localFile.lastModified() / 1000`.
- Patch memory cache using:
  ```kotlin
  patchNodeCacheAfterFileUpload(
      accountId = account.id,
      handle = realHandle,
      parentHandle = resolvedParentHandle,
      name = localFile.name,
      size = totalBytes,
      encodedKey = encodedKey,
      fileAttrStr = faField ?: "",
      timestamp = localFile.lastModified()
  )
  ```

### Dropbox (`DropboxApiClient.kt`)
- Set `clientModified` explicitly:
  ```kotlin
  uploadBuilder.withClientModified(Date(localFile.lastModified()))
  ```
- Patch the tree using `patchTreeAfterUpload(accountId, metadata)` which saves `metadata.clientModified.time`.

### Google Drive (`GoogleDriveApiClient.kt`)
- Set `modifiedTime` on `DriveFile()` metadata before executing `files().create`:
  ```kotlin
  metadata.modifiedTime = com.google.api.client.util.DateTime(localFile.lastModified())
  ```

### TeraBox (`TeraBoxApiClient.kt`)
- `uploadedItem` sets `lastModified = if (localFile.lastModified() > 0) localFile.lastModified() else System.currentTimeMillis()`.

## 3. Offline Download & Thumbnail Caching
- **Cached Files**: Stored via `CloudDownloadCache.fileFor(context, accountId, remotePath, fileName)`.
- **Thumbnail Cache**: Handled in `thumbDir` as `<safePath>.jpg` or `<safeId>.jpg`.
- When an overwrite occurs, invalidate the old thumbnail and copy the new file into `CloudDownloadCache` with `setLastModified(originalTimestamp)` so viewing doesn't re-download stale bytes.
