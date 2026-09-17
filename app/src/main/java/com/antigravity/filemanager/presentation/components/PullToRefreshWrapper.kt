package com.antigravity.filemanager.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.antigravity.filemanager.presentation.theme.DarkCard
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

    if (isRefreshing != null) {
        // Sync external refresh state
        LaunchedEffect(isRefreshing) {
            if (isRefreshing) {
                state.startRefresh()
            } else {
                state.endRefresh()
            }
        }

        // When triggered by user gesture, notify onRefresh only if not already refreshing
        if (state.isRefreshing) {
            LaunchedEffect(state.isRefreshing) {
                if (!isRefreshing) {
                    onRefresh()
                }
            }
        }
    } else {
        if (state.isRefreshing) {
            LaunchedEffect(true) {
                onRefresh()
                delay(1000)
                state.endRefresh()
            }
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
                containerColor = DarkCard,
                contentColor = TealPrimary
            )
        }
    }
}
