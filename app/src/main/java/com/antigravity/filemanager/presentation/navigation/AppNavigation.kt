package com.antigravity.filemanager.presentation.navigation

import android.content.Intent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.antigravity.filemanager.domain.model.CategoryType
import com.antigravity.filemanager.domain.model.FileItem
import com.antigravity.filemanager.domain.model.FileSortOption
import com.antigravity.filemanager.presentation.analyzer.StorageAnalysisScreen
import com.antigravity.filemanager.presentation.analyzer.StorageAnalysisViewModel
import com.antigravity.filemanager.presentation.browser.FileBrowserScreen
import com.antigravity.filemanager.presentation.categories.MediaCategoriesScreen
import com.antigravity.filemanager.presentation.cloud.CloudScreen
import com.antigravity.filemanager.presentation.dashboard.DashboardScreen
import com.antigravity.filemanager.presentation.network.AccessFromNetworkScreen
import com.antigravity.filemanager.presentation.theme.DarkCard
import com.antigravity.filemanager.presentation.theme.TealPrimary
import com.antigravity.filemanager.presentation.theme.TextSecondary
import com.antigravity.filemanager.presentation.trash.RecycleBinScreen
import com.antigravity.filemanager.presentation.viewers.ImageViewerScreen
import com.antigravity.filemanager.presentation.viewers.VideoPlayerScreen
import java.io.File

// Routes that hide the bottom tab bar (e.g. full-screen media viewers).
private val TAB_BAR_HIDDEN_ROUTES = setOf(Screen.ImageViewer.route, Screen.VideoPlayer.route)

// Tab graph routes retaining independent back stacks.
private const val LOCAL_GRAPH = "local_graph"
private const val CLOUD_GRAPH = "cloud_graph"
private const val FTP_GRAPH = "ftp_graph"

// Nested graph so StorageAnalysis/StorageFolderBreakdown/LargeFiles share one ViewModel instance.
private const val STORAGE_ANALYSIS_GRAPH = "storage_analysis_graph"

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val context = LocalContext.current

    val openFileHandler: (FileItem, FileSortOption, String?) -> Unit = { file, sortOption, cloudAccountId ->
        val ext = file.extension.lowercase()
        // A streamed Dropbox video's "path" is a pre-signed https URL, not a local file — don't
        // run it through File(...).parentFile, which would just produce garbage.
        val isStreamUrl = file.path.startsWith("http://") || file.path.startsWith("https://")
        val parent = if (isStreamUrl) "" else File(file.path).parentFile?.absolutePath ?: ""
        when (ext) {
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "svg", "raw", "dng" -> {
                navController.navigate(Screen.ImageViewer.createRoute(file.path, parent, sortOption.name, cloudAccountId, fileName = file.name))
            }
            "mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "3gp", "ts", "m4v" -> {
                navController.navigate(Screen.VideoPlayer.createRoute(file.path, parent, sortOption.name, cloudAccountId, fileName = file.name))
            }
            else -> {
                // Open external intent for Audio (m4a, mp3...), PDF, APK, Docs, etc.
                com.antigravity.filemanager.utils.FileOpener.openFile(context, file)
            }
        }
    }

    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route

    val isCloudTab = currentRoute in CLOUD_TAB_ROUTES
    val isFtpTab = currentRoute in FTP_TAB_ROUTES
    val isLocalTab = !isCloudTab && !isFtpTab
    val showBottomBar = currentRoute !in TAB_BAR_HIDDEN_ROUTES

    fun switchTab(graphRoute: String) {
        if (graphRoute == LOCAL_GRAPH && isLocalTab && currentRoute != Screen.Dashboard.route) {
            navController.popBackStack(Screen.Dashboard.route, inclusive = false)
        } else if (graphRoute == CLOUD_GRAPH && isCloudTab && currentRoute != Screen.Cloud.route) {
            navController.popBackStack(Screen.Cloud.route, inclusive = false)
        } else if (graphRoute == FTP_GRAPH && isFtpTab && currentRoute != Screen.AccessFromNetwork.route) {
            navController.popBackStack(Screen.AccessFromNetwork.route, inclusive = false)
        } else {
            navController.navigate(graphRoute) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = DarkCard) {
                    NavigationBarItem(
                        selected = isLocalTab,
                        onClick = { switchTab(LOCAL_GRAPH) },
                        icon = { Icon(Icons.Default.Folder, contentDescription = "Local") },
                        label = { Text("Local") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = TealPrimary,
                            selectedTextColor = TealPrimary,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary,
                            indicatorColor = DarkCard
                        )
                    )
                    NavigationBarItem(
                        selected = isCloudTab,
                        onClick = { switchTab(CLOUD_GRAPH) },
                        icon = { Icon(Icons.Default.Cloud, contentDescription = "Cloud") },
                        label = { Text("Cloud") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = TealPrimary,
                            selectedTextColor = TealPrimary,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary,
                            indicatorColor = DarkCard
                        )
                    )
                    NavigationBarItem(
                        selected = isFtpTab,
                        onClick = { switchTab(FTP_GRAPH) },
                        icon = { Icon(Icons.Default.Router, contentDescription = "FTP") },
                        label = { Text("FTP") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = TealPrimary,
                            selectedTextColor = TealPrimary,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary,
                            indicatorColor = DarkCard
                        )
                    )
                }
            }
        }
    ) { paddingValues ->
    Box(modifier = Modifier.fillMaxSize().padding(bottom = paddingValues.calculateBottomPadding())) {
    // Zero-latency Navigation without transitions for ultra-fast file browsing
    NavHost(
        navController = navController,
        startDestination = LOCAL_GRAPH,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None }
    ) {
        navigation(startDestination = Screen.Dashboard.route, route = LOCAL_GRAPH) {
            composable(Screen.Dashboard.route) {
                DashboardScreen(
                    onNavigateToCategory = { cat ->
                        navController.navigate(Screen.MediaCategory.createRoute(cat))
                    },
                    onNavigateToBrowser = { path, title ->
                        navController.navigate(Screen.FileBrowser.createRoute(path, title))
                    },
                    onNavigateToTrash = {
                        navController.navigate(Screen.RecycleBin.route)
                    },
                    onNavigateToStorageAnalysis = {
                        navController.navigate(Screen.StorageAnalysis.route)
                    }
                )
            }

            // StorageAnalysis -> StorageFolderBreakdown -> LargeFiles all share ONE
            // StorageAnalysisViewModel (scoped to this nested graph's back stack entry) so that
            // navigating between them doesn't re-run the full-device recursive scan each time.
            navigation(startDestination = Screen.StorageAnalysis.route, route = STORAGE_ANALYSIS_GRAPH) {
                composable(Screen.StorageAnalysis.route) { backStackEntry ->
                    val parentEntry = remember(backStackEntry) {
                        navController.getBackStackEntry(STORAGE_ANALYSIS_GRAPH)
                    }
                    val sharedViewModel: StorageAnalysisViewModel = hiltViewModel(parentEntry)
                    StorageAnalysisScreen(
                        viewModel = sharedViewModel,
                        onNavigateBack = { navController.popBackStack() },
                        onNavigateToStorageBreakdown = { path ->
                            navController.navigate(Screen.StorageFolderBreakdown.createRoute(path))
                        },
                        onNavigateToLargeFiles = {
                            navController.navigate(Screen.LargeFiles.route)
                        },
                        onNavigateToRecycleBin = {
                            navController.navigate(Screen.RecycleBin.route)
                        },
                        onNavigateToDuplicateFiles = {
                            navController.navigate(Screen.DuplicateFiles.route)
                        }
                    )
                }

                composable(
                    route = Screen.StorageFolderBreakdown.route,
                    arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" })
                ) { backStackEntry ->
                    val parentEntry = remember(backStackEntry) {
                        navController.getBackStackEntry(STORAGE_ANALYSIS_GRAPH)
                    }
                    val sharedViewModel: StorageAnalysisViewModel = hiltViewModel(parentEntry)
                    val path = backStackEntry.arguments?.getString("path") ?: ""
                    com.antigravity.filemanager.presentation.analyzer.StorageFolderBreakdownScreen(
                        viewModel = sharedViewModel,
                        onNavigateBack = { navController.popBackStack() },
                        initialPath = if (path.isNotEmpty()) path else android.os.Environment.getExternalStorageDirectory().absolutePath
                    )
                }

                composable(Screen.LargeFiles.route) { backStackEntry ->
                    val parentEntry = remember(backStackEntry) {
                        navController.getBackStackEntry(STORAGE_ANALYSIS_GRAPH)
                    }
                    val sharedViewModel: StorageAnalysisViewModel = hiltViewModel(parentEntry)
                    com.antigravity.filemanager.presentation.analyzer.LargeFilesScreen(
                        viewModel = sharedViewModel,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }

                composable(Screen.DuplicateFiles.route) { backStackEntry ->
                    val parentEntry = remember(backStackEntry) {
                        navController.getBackStackEntry(STORAGE_ANALYSIS_GRAPH)
                    }
                    val sharedViewModel: StorageAnalysisViewModel = hiltViewModel(parentEntry)
                    com.antigravity.filemanager.presentation.analyzer.DuplicateFilesScreen(
                        viewModel = sharedViewModel,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
            }

            composable(Screen.RecycleBin.route) {
                RecycleBinScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Screen.MediaCategory.route,
                arguments = listOf(navArgument("categoryType") { type = NavType.StringType })
            ) {
                MediaCategoriesScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onOpenFile = openFileHandler
                )
            }

            composable(
                route = Screen.FileBrowser.route,
                arguments = listOf(
                    navArgument("path") { type = NavType.StringType; defaultValue = "" },
                    navArgument("title") { type = NavType.StringType; defaultValue = "Main storage" }
                )
            ) {
                FileBrowserScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onOpenFile = openFileHandler,
                    onNavigateToStorageAnalysis = {
                        navController.navigate(Screen.StorageAnalysis.route)
                    }
                )
            }
        }

        navigation(startDestination = Screen.Cloud.route, route = CLOUD_GRAPH) {
            composable(Screen.Cloud.route) {
                CloudScreen(
                    onNavigateBack = {},
                    onNavigateToCloudBrowser = { accountId, name ->
                        navController.navigate(Screen.CloudBrowser.createRoute(accountId, name))
                    }
                )
            }

            composable(
                route = Screen.CloudBrowser.route,
                arguments = listOf(
                    navArgument("accountId") { type = NavType.StringType; defaultValue = "" },
                    navArgument("title") { type = NavType.StringType; defaultValue = "Cloud Storage" }
                )
            ) { backStackEntry ->
                val accountId = backStackEntry.arguments?.getString("accountId") ?: ""
                val title = backStackEntry.arguments?.getString("title") ?: "Cloud Storage"
                com.antigravity.filemanager.presentation.cloud.CloudExplorerScreen(
                    accountId = accountId,
                    title = title,
                    onNavigateToCloudList = {
                        if (!navController.popBackStack(Screen.Cloud.route, false)) {
                            navController.popBackStack()
                        }
                    },
                    onOpenFile = openFileHandler
                )
            }
        }

        navigation(startDestination = Screen.AccessFromNetwork.route, route = FTP_GRAPH) {
            composable(Screen.AccessFromNetwork.route) {
                AccessFromNetworkScreen(
                    onNavigateBack = {},
                    showBackButton = false
                )
            }
        }

        composable(
            route = Screen.ImageViewer.route,
            arguments = listOf(
                navArgument("path") { type = NavType.StringType; defaultValue = "" },
                navArgument("parentPath") { type = NavType.StringType; defaultValue = "" },
                navArgument("sortOption") { type = NavType.StringType; defaultValue = FileSortOption.BY_NAME_ASC.name },
                navArgument("cloudAccountId") { type = NavType.StringType; defaultValue = "" },
                navArgument("fileName") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val path = backStackEntry.arguments?.getString("path") ?: ""
            val parentPath = backStackEntry.arguments?.getString("parentPath") ?: ""
            val sortOption = runCatching {
                FileSortOption.valueOf(backStackEntry.arguments?.getString("sortOption") ?: "")
            }.getOrDefault(FileSortOption.BY_NAME_ASC)
            val cloudAccountId = backStackEntry.arguments?.getString("cloudAccountId")?.takeIf { it.isNotEmpty() }
            val fileName = backStackEntry.arguments?.getString("fileName") ?: ""
            ImageViewerScreen(
                initialPath = path,
                parentPath = parentPath,
                sortOption = sortOption,
                cloudAccountId = cloudAccountId,
                fileName = fileName,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.VideoPlayer.route,
            arguments = listOf(
                navArgument("path") { type = NavType.StringType; defaultValue = "" },
                navArgument("parentPath") { type = NavType.StringType; defaultValue = "" },
                navArgument("sortOption") { type = NavType.StringType; defaultValue = FileSortOption.BY_NAME_ASC.name },
                navArgument("cloudAccountId") { type = NavType.StringType; defaultValue = "" },
                navArgument("fileName") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val path = backStackEntry.arguments?.getString("path") ?: ""
            val parentPath = backStackEntry.arguments?.getString("parentPath") ?: ""
            val sortOption = runCatching {
                FileSortOption.valueOf(backStackEntry.arguments?.getString("sortOption") ?: "")
            }.getOrDefault(FileSortOption.BY_NAME_ASC)
            val cloudAccountId = backStackEntry.arguments?.getString("cloudAccountId")?.takeIf { it.isNotEmpty() }
            val fileName = backStackEntry.arguments?.getString("fileName") ?: ""
            VideoPlayerScreen(
                initialPath = path,
                parentPath = parentPath,
                sortOption = sortOption,
                cloudAccountId = cloudAccountId,
                fileName = fileName,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
    }
    }
}

private val LOCAL_TAB_ROUTES = setOf(
    Screen.Dashboard.route,
    Screen.StorageAnalysis.route,
    Screen.StorageFolderBreakdown.route,
    Screen.LargeFiles.route,
    Screen.RecycleBin.route,
    Screen.MediaCategory.route,
    Screen.FileBrowser.route
)

private val CLOUD_TAB_ROUTES = setOf(
    Screen.Cloud.route,
    Screen.CloudBrowser.route
)

private val FTP_TAB_ROUTES = setOf(
    Screen.AccessFromNetwork.route
)
