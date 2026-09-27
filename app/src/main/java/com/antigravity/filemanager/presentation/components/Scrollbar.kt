package com.antigravity.filemanager.presentation.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Where the scrollbar thumb sits, as fractions of the list's height. */
private class ThumbFraction(val top: Float, val height: Float)

/**
 * Scroll position of a lazy list or grid as a thumb, estimated from the lines on screen: every
 * line is taken to be as tall as the visible ones on average, which is exact for the file lists
 * (equal-height rows) and close enough for the grids.
 */
private fun thumbFraction(
    firstLine: Int,
    /** How far the first visible line is scrolled past the top, in px. */
    firstLineScrolled: Int,
    lineExtent: Float,
    lineCount: Int,
    viewport: Int
): ThumbFraction? {
    if (lineCount <= 0 || lineExtent <= 0f || viewport <= 0) return null
    val content = lineExtent * lineCount
    if (content <= viewport) return null // everything fits: no scrollbar
    val scrolled = (firstLine * lineExtent + firstLineScrolled).coerceIn(0f, content - viewport)
    return ThumbFraction(top = scrolled / content, height = viewport / content)
}

/** A thin scrollbar on the right edge while [state] scrolls, so a long folder shows where in it
 * the view is and how close the end is; it fades out shortly after scrolling stops. */
fun Modifier.verticalScrollbar(state: LazyListState): Modifier = scrollbar(
    isScrolling = { state.isScrollInProgress }
) {
    val info = state.layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return@scrollbar null
    val extent = (visible.last().offset + visible.last().size - visible.first().offset).toFloat() / visible.size +
        info.mainAxisItemSpacing
    thumbFraction(
        firstLine = visible.first().index,
        firstLineScrolled = info.viewportStartOffset - visible.first().offset,
        lineExtent = extent,
        lineCount = info.totalItemsCount,
        viewport = info.viewportEndOffset - info.viewportStartOffset
    )
}

/** [verticalScrollbar] for a grid, measured in rows. */
fun Modifier.verticalScrollbar(state: LazyGridState): Modifier = scrollbar(
    isScrolling = { state.isScrollInProgress }
) {
    val info = state.layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return@scrollbar null
    val first = visible.first()
    val columns = visible.count { it.offset.y == first.offset.y }.coerceAtLeast(1)
    val visibleRows = (visible.size + columns - 1) / columns
    val last = visible.last()
    val extent = (last.offset.y + last.size.height - first.offset.y).toFloat() / visibleRows + info.mainAxisItemSpacing
    thumbFraction(
        firstLine = first.index / columns,
        firstLineScrolled = info.viewportStartOffset - first.offset.y,
        lineExtent = extent,
        lineCount = (info.totalItemsCount + columns - 1) / columns,
        viewport = info.viewportEndOffset - info.viewportStartOffset
    )
}

private fun Modifier.scrollbar(isScrolling: () -> Boolean, thumb: () -> ThumbFraction?): Modifier = composed {
    val scrolling = isScrolling()
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(scrolling) {
        if (scrolling) shown = true else { delay(1200); shown = false }
    }
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(if (shown) 150 else 400),
        label = "ScrollbarAlpha"
    )
    drawWithContent {
        drawContent()
        if (alpha <= 0f) return@drawWithContent
        val fraction = thumb() ?: return@drawWithContent
        val width = 4.dp.toPx()
        val margin = 2.dp.toPx()
        val track = size.height - 2 * margin
        val height = (track * fraction.height).coerceAtLeast(36.dp.toPx()).coerceAtMost(track)
        val top = margin + (track - height) * (fraction.top / (1f - fraction.height).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
        drawRoundRect(
            color = Color(0xFFB0B0B0).copy(alpha = 0.7f * alpha),
            topLeft = Offset(size.width - width - margin, top),
            size = Size(width, height),
            cornerRadius = CornerRadius(width / 2, width / 2)
        )
    }
}
