package com.antigravity.filemanager.presentation.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.antigravity.filemanager.presentation.theme.DarkBackground
import com.antigravity.filemanager.presentation.theme.TextPrimary
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerTopBar(
    title: String,
    showBackButton: Boolean = false,
    onNavigationClick: () -> Unit = {},
    onSearchClick: (() -> Unit)? = null,
    onRefreshClick: (() -> Unit)? = null,
    onMenuClick: (() -> Unit)? = null,
    isRefreshing: Boolean = false,
    actions: @Composable (RowScope.() -> Unit)? = null
) {
    val rotation = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            rotation.animateTo(
                targetValue = rotation.value + 360f,
                animationSpec = tween(durationMillis = 600, easing = LinearEasing)
            )
        }
    }

    TopAppBar(
        title = {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium
            )
        },
        navigationIcon = {
            IconButton(onClick = onNavigationClick) {
                Icon(
                    imageVector = if (showBackButton) Icons.Default.ArrowBack else Icons.Default.Menu,
                    contentDescription = if (showBackButton) "Back" else "Menu",
                    tint = TextPrimary
                )
            }
        },
        actions = {
            if (actions != null) {
                actions()
            } else {
                if (onSearchClick != null) {
                    IconButton(onClick = onSearchClick) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = TextPrimary
                        )
                    }
                }
                // Refresh Button with rotating circle icon (matching user request)
                if (onRefreshClick != null) {
                    IconButton(onClick = {
                        coroutineScope.launch {
                            rotation.animateTo(
                                targetValue = rotation.value + 360f,
                                animationSpec = tween(durationMillis = 600, easing = LinearEasing)
                            )
                        }
                        onRefreshClick()
                    }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = TextPrimary,
                            modifier = Modifier.rotate(rotation.value)
                        )
                    }
                }
                if (onMenuClick != null) {
                    IconButton(onClick = onMenuClick) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More options",
                            tint = TextPrimary
                        )
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = DarkBackground
        )
    )
}
