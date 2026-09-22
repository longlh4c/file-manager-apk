package com.antigravity.filemanager.presentation.navigation

import kotlinx.coroutines.flow.first
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.antigravity.filemanager.domain.model.FileItem
import com.antigravity.filemanager.domain.model.FileSortOption
import com.antigravity.filemanager.domain.usecase.ActivePanel
import com.antigravity.filemanager.domain.usecase.DualPanelManager
import com.antigravity.filemanager.presentation.analyzer.StorageAnalysisScreen
import com.antigravity.filemanager.presentation.analyzer.StorageAnalysisViewModel
import com.antigravity.filemanager.presentation.browser.FileBrowserScreen
import com.antigravity.filemanager.presentation.categories.MediaCategoriesScreen
import com.antigravity.filemanager.presentation.cloud.CloudExplorerScreen
import com.antigravity.filemanager.presentation.cloud.CloudScreen
import com.antigravity.filemanager.presentation.dashboard.DashboardScreen
import com.antigravity.filemanager.presentation.network.AccessFromNetworkScreen
import com.antigravity.filemanager.presentation.theme.DarkCard
import com.antigravity.filemanager.presentation.theme.DividerDark
import com.antigravity.filemanager.presentation.theme.TealPrimary
import com.antigravity.filemanager.presentation.theme.TextSecondary
import com.antigravity.filemanager.presentation.trash.RecycleBinScreen
import com.antigravity.filemanager.presentation.viewers.ImageViewerScreen
import com.antigravity.filemanager.presentation.viewers.VideoPlayerScreen
import com.antigravity.filemanager.utils.rememberFoldablePosture
import java.io.File

// Routes that hide the bottom tab bar (e.g. full-screen media viewers).
private val TAB_BAR_HIDDEN_ROUTES = setOf(Screen.ImageViewer.route, Screen.VideoPlayer.route)

// Tab graph routes retaining independent back stacks.
private const val LOCAL_GRAPH = "local_graph"
private const val CLOUD_GRAPH = "cloud_graph"
private const val FTP_GRAPH = "ftp_graph"

// Nested graph so StorageAnalysis/StorageFolderBreakdown/LargeFiles share one ViewModel instance.
private const val STORAGE_ANALYSIS_GRAPH = "storage_analysis_graph"
private const val RIGHT_STORAGE_ANALYSIS_GRAPH = "right_storage_analysis_graph"

@Composable
fun AppNavigation(
    dualPanelManager: DualPanelManager,
    preferenceManager: com.antigravity.filemanager.data.local.preferences.PreferenceManager
) {
    val context = LocalContext.current
    val foldablePosture = rememberFoldablePosture()
    val isUnfolded = foldablePosture.isUnfolded
    val isDualPanelActive by dualPanelManager.isDualPanelActive.collectAsStateWithLifecycle()

    // Auto-collapse to single screen when device is folded shut to avoid cramped UI on outer display
    LaunchedEffect(isUnfolded) {
        dualPanelManager.onFoldPostureChanged(isUnfolded)
    }

    val onToggleDualPanel = {
        dualPanelManager.toggle(isUnfolded) { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    val isDualSplit = isDualPanelActive && isUnfolded

    val leftNavController = rememberNavController()
    val rightNavController = rememberNavController()

    // Restore the last-viewed image on cold start or after process death. Only the value persisted
    // at startup counts: observing the flow meant that, when nothing was saved yet, the first image
    // opened this session (which saves itself as "last viewed") was "restored" as a second viewer
    // stacked on top of itself, needing two Backs to leave.
    var hasRestoredLastViewed by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (hasRestoredLastViewed) return@LaunchedEffect
        hasRestoredLastViewed = true
        val state = preferenceManager.lastViewedImageFlow.first()
        if (state != null) {
            val isValid = if (state.cloudAccountId == null) File(state.path).exists() else true
            if (isValid) {
                leftNavController.navigate(
                    Screen.ImageViewer.createRoute(
                        path = state.path,
                        parentPath = state.parentPath,
                        sortOption = state.sortOption,
                        cloudAccountId = state.cloudAccountId,
                        fileName = state.fileName
                    )
                )
            }
        }
    }

    // Reset the right pane in the background only when dual panel is closed
    LaunchedEffect(isDualSplit) {
        if (!isDualSplit) {
            runCatching {
                rightNavController.popBackStack(Screen.Dashboard.route, inclusive = false)
            }
        }
    }

    val createOpenFileHandler: (NavHostController) -> (FileItem, FileSortOption, String?) -> Unit = { controller ->
        { file, sortOption, cloudAccountId ->
            val ext = file.extension.lowercase()
            val isStreamUrl = com.antigravity.filemanager.presentation.viewers.CloudMediaDataSources.isStreamPath(file.path)
            val parent = if (isStreamUrl) "" else File(file.path).parentFile?.absolutePath ?: ""
            when (ext) {
                "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "svg", "raw", "dng" -> {
                    controller.navigate(Screen.ImageViewer.createRoute(file.path, parent, sortOption.name, cloudAccountId, fileName = file.name))
                }
                "mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "3gp", "ts", "m4v" -> {
                    controller.navigate(Screen.VideoPlayer.createRoute(file.path, parent, sortOption.name, cloudAccountId, fileName = file.name))
                }
                else -> {
                    com.antigravity.filemanager.utils.FileOpener.openFile(context, file)
                }
            }
        }
    }

    val leftOpenFileHandler = remember(leftNavController) { createOpenFileHandler(leftNavController) }
    val rightOpenFileHandler = remember(rightNavController) { createOpenFileHandler(rightNavController) }

    val activePanel by dualPanelManager.activePanel.collectAsStateWithLifecycle()

    val animProgress by animateFloatAsState(
        targetValue = if (isDualSplit) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (isDualSplit) 240 else 180,
            easing = if (isDualSplit) FastOutSlowInEasing else FastOutLinearInEasing
        ),
        label = "DualPanelScaleProgress"
    )

    val isTransitioningOrActive = isDualSplit || animProgress > 0.001f

    val leftScale = if (isTransitioningOrActive) 1f + (1f - animProgress) * 0.025f else 1f
    val rightScale = 0.95f + animProgress * 0.05f

    val isLeftActive = !isDualSplit || activePanel == ActivePanel.LEFT
    val leftAlpha by animateFloatAsState(
        targetValue = if (isLeftActive) 1f else 0.72f,
        animationSpec = tween(180),
        label = "LeftPaneAlpha"
    )

    val isRightActive = isDualSplit && activePanel == ActivePanel.RIGHT
    val rightAlpha by animateFloatAsState(
        targetValue = if (isRightActive) 1f else 0.72f,
        animationSpec = tween(180),
        label = "RightPaneAlpha"
    )

    val leftAccentColor by animateColorAsState(
        targetValue = if (isDualSplit && activePanel == ActivePanel.LEFT) TealPrimary else Color.Transparent,
        animationSpec = tween(180),
        label = "LeftAccentColor"
    )

    val rightAccentColor by animateColorAsState(
        targetValue = if (isDualSplit && activePanel == ActivePanel.RIGHT) TealPrimary else Color.Transparent,
        animationSpec = tween(180),
        label = "RightAccentColor"
    )

    val leftPointerModifier = if (isDualSplit) {
        Modifier.pointerInput(isDualSplit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Press) {
                        dualPanelManager.setActivePanel(ActivePanel.LEFT)
                    }
                }
            }
        }
    } else Modifier

    val rightPointerModifier = if (isDualSplit) {
        Modifier.pointerInput(isDualSplit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Press) {
                        dualPanelManager.setActivePanel(ActivePanel.RIGHT)
                    }
                }
            }
        }
    } else Modifier

    Row(modifier = Modifier.fillMaxSize()) {
        // Left Pane: Always occupies slot 0 of Row so its composition and state are 100% preserved
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .then(leftPointerModifier)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = leftAlpha
                        scaleX = leftScale
                        scaleY = leftScale
                    }
            ) {
                LeftPaneContent(
                    navController = leftNavController,
                    openFileHandler = leftOpenFileHandler,
                    isDualPanelActive = isDualSplit,
                    onToggleDualPanel = onToggleDualPanel
                )
            }

            if (isDualSplit) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .align(Alignment.TopCenter)
                        .graphicsLayer { alpha = animProgress }
                        .background(leftAccentColor)
                )
            }
        }

        // Right Pane: Suggestion 2 Split Zoom & Fade (Samsung One UI Fold / Tablet style)
        if (isTransitioningOrActive) {
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .graphicsLayer {
                        alpha = animProgress
                        scaleY = animProgress
                    }
                    .background(DividerDark)
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(rightPointerModifier)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = rightAlpha * animProgress
                            scaleX = rightScale
                            scaleY = rightScale
                        }
                ) {
                    RightPaneContent(
                        navController = rightNavController,
                        openFileHandler = rightOpenFileHandler,
                        onClose = { dualPanelManager.close() },
                        activePanel = activePanel
                    )
                }

                if (isDualSplit) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .align(Alignment.TopCenter)
                            .graphicsLayer { alpha = animProgress }
                            .background(rightAccentColor)
                    )
                }
            }
        }
    }
}

@Composable
private fun LeftPaneContent(
    navController: NavHostController,
    openFileHandler: (FileItem, FileSortOption, String?) -> Unit,
    isDualPanelActive: Boolean,
    onToggleDualPanel: () -> Unit
) {
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
                FileManagerBottomBar(
                    isLocalTab = isLocalTab,
                    isCloudTab = isCloudTab,
                    isFtpTab = isFtpTab,
                    onSelectLocal = { switchTab(LOCAL_GRAPH) },
                    onSelectCloud = { switchTab(CLOUD_GRAPH) },
                    onSelectFtp = { switchTab(FTP_GRAPH) }
                )
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(bottom = paddingValues.calculateBottomPadding())) {
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
                            },
                            onDualPanelToggle = onToggleDualPanel,
                            isDualPanelActive = isDualPanelActive
                        )
                    }

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
                            onOpenFile = openFileHandler,
                            onDualPanelToggle = onToggleDualPanel,
                            isDualPanelActive = isDualPanelActive
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
                            },
                            onDualPanelToggle = onToggleDualPanel,
                            isDualPanelActive = isDualPanelActive
                        )
                    }
                }

                navigation(startDestination = Screen.Cloud.route, route = CLOUD_GRAPH) {
                    composable(Screen.Cloud.route) {
                        CloudScreen(
                            onNavigateBack = {},
                            onNavigateToCloudBrowser = { accountId, name ->
                                navController.navigate(Screen.CloudBrowser.createRoute(accountId, name))
                            },
                            onDualPanelToggle = onToggleDualPanel,
                            isDualPanelActive = isDualPanelActive
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
                        CloudExplorerScreen(
                            accountId = accountId,
                            title = title,
                            onNavigateToCloudList = {
                                if (!navController.popBackStack(Screen.Cloud.route, false)) {
                                    navController.popBackStack()
                                }
                            },
                            onOpenFile = openFileHandler,
                            onDualPanelToggle = onToggleDualPanel,
                            isDualPanelActive = isDualPanelActive
                        )
                    }
                }

                navigation(startDestination = Screen.AccessFromNetwork.route, route = FTP_GRAPH) {
                    composable(Screen.AccessFromNetwork.route) {
                        AccessFromNetworkScreen(
                            onNavigateBack = {},
                            showBackButton = false,
                            onDualPanelToggle = onToggleDualPanel,
                            isDualPanelActive = isDualPanelActive
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

@Composable
private fun FileManagerBottomBar(
    isLocalTab: Boolean,
    isCloudTab: Boolean,
    isFtpTab: Boolean,
    onSelectLocal: () -> Unit,
    onSelectCloud: () -> Unit,
    onSelectFtp: () -> Unit
) {
    NavigationBar(containerColor = DarkCard) {
        NavigationBarItem(
            selected = isLocalTab,
            onClick = onSelectLocal,
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
            onClick = onSelectCloud,
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
            onClick = onSelectFtp,
            icon = { Icon(Icons.Default.Router, contentDescription = "FTP", modifier = Modifier.size(28.dp)) },
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

@Composable
private fun RightPaneContent(
    navController: NavHostController,
    openFileHandler: (FileItem, FileSortOption, String?) -> Unit,
    onClose: () -> Unit,
    activePanel: ActivePanel
) {
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    val isCloudTab = currentRoute in CLOUD_TAB_ROUTES
    val isFtpTab = currentRoute in FTP_TAB_ROUTES
    val isLocalTab = !isCloudTab && !isFtpTab
    val showBottomBar = currentRoute !in TAB_BAR_HIDDEN_ROUTES

    val rightCanPop = navController.previousBackStackEntry != null

    // BackHandler within Right Pane: Only pops Right Pane navigation when Right Pane is currently focused
    BackHandler(enabled = rightCanPop && activePanel == ActivePanel.RIGHT) {
        navController.popBackStack()
    }

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
                FileManagerBottomBar(
                    isLocalTab = isLocalTab,
                    isCloudTab = isCloudTab,
                    isFtpTab = isFtpTab,
                    onSelectLocal = { switchTab(LOCAL_GRAPH) },
                    onSelectCloud = { switchTab(CLOUD_GRAPH) },
                    onSelectFtp = { switchTab(FTP_GRAPH) }
                )
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(bottom = paddingValues.calculateBottomPadding())) {
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
                            },
                            onDualPanelToggle = onClose,
                            isDualPanelActive = true,
                            onCloseDualPanel = onClose
                        )
                    }

                    navigation(startDestination = Screen.StorageAnalysis.route, route = RIGHT_STORAGE_ANALYSIS_GRAPH) {
                        composable(Screen.StorageAnalysis.route) { backStackEntry ->
                            val parentEntry = remember(backStackEntry) {
                                navController.getBackStackEntry(RIGHT_STORAGE_ANALYSIS_GRAPH)
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
                                navController.getBackStackEntry(RIGHT_STORAGE_ANALYSIS_GRAPH)
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
                                navController.getBackStackEntry(RIGHT_STORAGE_ANALYSIS_GRAPH)
                            }
                            val sharedViewModel: StorageAnalysisViewModel = hiltViewModel(parentEntry)
                            com.antigravity.filemanager.presentation.analyzer.LargeFilesScreen(
                                viewModel = sharedViewModel,
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }

                        composable(Screen.DuplicateFiles.route) { backStackEntry ->
                            val parentEntry = remember(backStackEntry) {
                                navController.getBackStackEntry(RIGHT_STORAGE_ANALYSIS_GRAPH)
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
                            onOpenFile = openFileHandler,
                            onDualPanelToggle = onClose,
                            isDualPanelActive = true
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
                            },
                            onDualPanelToggle = onClose,
                            isDualPanelActive = true
                        )
                    }
                }

                navigation(startDestination = Screen.Cloud.route, route = CLOUD_GRAPH) {
                    composable(Screen.Cloud.route) {
                        CloudScreen(
                            onNavigateBack = {},
                            onNavigateToCloudBrowser = { accountId, name ->
                                navController.navigate(Screen.CloudBrowser.createRoute(accountId, name))
                            },
                            onDualPanelToggle = onClose,
                            isDualPanelActive = true,
                            onCloseDualPanel = onClose
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
                        CloudExplorerScreen(
                            accountId = accountId,
                            title = title,
                            onNavigateToCloudList = {
                                if (!navController.popBackStack(Screen.Cloud.route, false)) {
                                    navController.popBackStack()
                                }
                            },
                            onOpenFile = openFileHandler,
                            onDualPanelToggle = onClose,
                            isDualPanelActive = true
                        )
                    }
                }

                navigation(startDestination = Screen.AccessFromNetwork.route, route = FTP_GRAPH) {
                    composable(Screen.AccessFromNetwork.route) {
                        AccessFromNetworkScreen(
                            onNavigateBack = {},
                            showBackButton = false,
                            onDualPanelToggle = onClose,
                            isDualPanelActive = true,
                            onCloseDualPanel = onClose
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
