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
import kotlinx.coroutines.delay

// Wraps content with pull-to-refresh, auto-hiding the spinner after a short delay.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullToRefreshWrapper(
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val state = rememberPullToRefreshState()

    if (state.isRefreshing) {
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
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }
}
