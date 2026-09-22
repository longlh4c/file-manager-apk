package com.antigravity.filemanager.presentation.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.antigravity.filemanager.domain.usecase.ActivePanel
import com.antigravity.filemanager.domain.usecase.GlobalClipboardState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DualPaneDragStateTest {

    private val leftBounds = Rect(0f, 0f, 500f, 1000f)
    private val rightBounds = Rect(500f, 0f, 1000f, 1000f)

    private var dropped: Pair<List<String>, Boolean>? = null
    private var finished = false

    private fun state(rightFolder: String = "/sdcard/B") = DualPaneDragState().apply {
        register(ActivePanel.LEFT, DualPaneDropTarget("/sdcard/A", "A", leftBounds) { error("left must not receive") })
        register(ActivePanel.RIGHT, DualPaneDropTarget(rightFolder, "B", rightBounds) { dropped = it.paths to it.isCut })
    }

    private fun payload() = DualPaneDragPayload(ActivePanel.LEFT, GlobalClipboardState(paths = listOf("/sdcard/A/x.txt")), "/sdcard/A") { finished = true }

    @Test
    fun droppingOnTheOtherPaneAsksAndThenMoves() {
        val s = state()
        s.start(payload(), Offset(100f, 100f))
        s.move(Offset(700f, 300f))
        assertEquals("B", s.hoveredTarget?.folderName)

        s.drop()
        assertTrue(s.pendingDrop != null)
        assertNull(dropped)

        s.resolve(isMove = true)
        assertEquals(listOf("/sdcard/A/x.txt") to true, dropped)
        assertTrue(finished)
        assertNull(s.pendingDrop)
    }

    @Test
    fun droppingBackOnTheSourcePaneDoesNothing() {
        val s = state()
        s.start(payload(), Offset(100f, 100f))
        s.move(Offset(200f, 300f))
        s.drop()
        assertNull(s.pendingDrop)
        assertNull(s.payload)
    }

    @Test
    fun otherPaneShowingTheSameFolderIsNotATarget() {
        val s = state(rightFolder = "/sdcard/A")
        s.start(payload(), Offset(100f, 100f))
        s.move(Offset(700f, 300f))
        assertNull(s.hoveredTarget)
        s.drop()
        assertNull(s.pendingDrop)
    }

    @Test
    fun cancellingTheMenuLeavesEverythingAsIs() {
        val s = state()
        s.start(payload(), Offset(100f, 100f))
        s.move(Offset(700f, 300f))
        s.drop()
        s.resolve(isMove = null)
        assertNull(dropped)
        assertTrue(!finished)
    }
}
