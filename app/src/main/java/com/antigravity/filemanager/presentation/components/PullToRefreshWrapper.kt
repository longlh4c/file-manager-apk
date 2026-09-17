package com.antigravity.filemanager.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.antigravity.filemanager.presentation.theme.DarkBackground
import com.antigravity.filemanager.presentation.theme.TealPrimary
import kotlinx.coroutines.delay

// Wraps content with pull-to-refresh, supporting both external isRefreshing control
// and self-managed delay-based pull gestures.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullToRefreshWrapper(
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    isRefreshing: Boolean? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val state = rememberPullToRefreshState()
    var isProgrammaticRefresh by remember { mutableStateOf(false) }

    if (isRefreshing != null) {
        // Sync external programmatic refresh state (e.g. folder loading)
        LaunchedEffect(isRefreshing) {
            if (isRefreshing) {
                isProgrammaticRefresh = true
                state.startRefresh()
            } else {
                state.endRefresh()
                isProgrammaticRefresh = false
            }
        }
    }

    // Handle user pull gesture
    if (state.isRefreshing && !isProgrammaticRefresh) {
        LaunchedEffect(true) {
            onRefresh()
            delay(1000)
            state.endRefresh()
        }
    }

    Box(modifier = modifier.nestedScroll(state.nestedScrollConnection)) {
        content()
        // PullToRefreshContainer always draws its resting background circle even at rest,
        // so only compose it into the tree while a pull/refresh is actually in progress.
        if (state.isRefreshing || state.progress > 0f) {
            PullToRefreshContainer(
                state = state,
                modifier = Modifier.align(Alignment.TopCenter),
                containerColor = DarkBackground,
                contentColor = TealPrimary
            )
        }
    }
}
