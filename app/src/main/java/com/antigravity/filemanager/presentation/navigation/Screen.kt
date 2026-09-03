package com.antigravity.filemanager.presentation.navigation

import android.net.Uri
import com.antigravity.filemanager.domain.model.CategoryType

sealed class Screen(val route: String) {
    object Dashboard : Screen("dashboard")
    object StorageAnalysis : Screen("storage_analysis")
    object StorageFolderBreakdown : Screen("storage_folder_breakdown?path={path}") {
        fun createRoute(path: String = ""): String = "storage_folder_breakdown?path=${Uri.encode(path)}"
    }
    object LargeFiles : Screen("large_files")
    object DuplicateFiles : Screen("duplicate_files")
    object AccessFromNetwork : Screen("access_from_network")
    object Cloud : Screen("cloud")
    object RecycleBin : Screen("recycle_bin")

    object MediaCategory : Screen("category/{categoryType}") {
        fun createRoute(categoryType: CategoryType): String = "category/${categoryType.name}"
    }

    object FileBrowser : Screen("browser?path={path}&title={title}") {
        fun createRoute(path: String, title: String = ""): String {
            val encodedPath = Uri.encode(path)
            val encodedTitle = Uri.encode(title)
            return "browser?path=$encodedPath&title=$encodedTitle"
        }
    }

    object ImageViewer : Screen("image_viewer?path={path}&parentPath={parentPath}&sortOption={sortOption}&cloudAccountId={cloudAccountId}&fileName={fileName}") {
        fun createRoute(path: String, parentPath: String = "", sortOption: String = "BY_NAME_ASC", cloudAccountId: String? = null, fileName: String = ""): String {
            val encodedPath = Uri.encode(path)
            val encodedParent = Uri.encode(parentPath)
            val encodedAccountId = Uri.encode(cloudAccountId ?: "")
            val encodedFileName = Uri.encode(fileName)
            return "image_viewer?path=$encodedPath&parentPath=$encodedParent&sortOption=$sortOption&cloudAccountId=$encodedAccountId&fileName=$encodedFileName"
        }
    }

    object VideoPlayer : Screen("video_player?path={path}&parentPath={parentPath}&sortOption={sortOption}&cloudAccountId={cloudAccountId}&fileName={fileName}") {
        fun createRoute(path: String, parentPath: String = "", sortOption: String = "BY_NAME_ASC", cloudAccountId: String? = null, fileName: String = ""): String {
            val encodedPath = Uri.encode(path)
            val encodedParent = Uri.encode(parentPath)
            val encodedAccountId = Uri.encode(cloudAccountId ?: "")
            val encodedFileName = Uri.encode(fileName)
            return "video_player?path=$encodedPath&parentPath=$encodedParent&sortOption=$sortOption&cloudAccountId=$encodedAccountId&fileName=$encodedFileName"
        }
    }

    object CloudBrowser : Screen("cloud_browser?accountId={accountId}&title={title}") {
        fun createRoute(accountId: String, title: String = ""): String {
            val encodedId = Uri.encode(accountId)
            val encodedTitle = Uri.encode(title)
            return "cloud_browser?accountId=$encodedId&title=$encodedTitle"
        }
    }
}
