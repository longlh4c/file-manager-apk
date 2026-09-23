package com.antigravity.filemanager.presentation.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.antigravity.filemanager.domain.usecase.ActivePanel
import com.antigravity.filemanager.domain.usecase.GlobalClipboardState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DualPaneDragStateTest {

    private val received = mutableMapOf<String, GlobalClipboardState>()
    private var finished = false

    private fun zone(
        pane: ActivePanel,
        location: String,
        bounds: Rect,
        background: Boolean = false,
        kind: DropZoneKind = DropZoneKind.FOLDER
    ) = DualPaneDropZone(pane, location, location.substringAfterLast('/'), kind, background) { received[location] = it }
        .apply { this.bounds = bounds }

    /** Left pane shows /sdcard/A with a sub-folder row; right pane shows /sdcard/B with a row C. */
    private fun state() = DualPaneDragState().apply {
        register(zone(ActivePanel.LEFT, "/sdcard/A", Rect(0f, 0f, 500f, 1000f), background = true))
        register(zone(ActivePanel.LEFT, "/sdcard/A/sub", Rect(0f, 200f, 500f, 300f)))
        register(zone(ActivePanel.RIGHT, "/sdcard/B", Rect(500f, 0f, 1000f, 1000f), background = true))
        register(zone(ActivePanel.RIGHT, "/sdcard/B/C", Rect(500f, 100f, 1000f, 200f)))
    }

    private fun payload(vararg paths: String) =
        DualPaneDragPayload(ActivePanel.LEFT, GlobalClipboardState(paths = paths.toList()), "/sdcard/A") { finished = true }

    private fun DualPaneDragState.dragTo(at: Offset, vararg paths: String = arrayOf("/sdcard/A/x.txt")) {
        start(payload(*paths), Offset(10f, 10f))
        move(at)
    }

    @Test
    fun droppingOnTheOtherPaneAsksAndThenMoves() {
        val s = state()
        s.dragTo(Offset(700f, 600f))
        assertEquals("/sdcard/B", s.hoveredTarget?.location)
        s.drop()
        assertTrue(s.pendingDrop != null)
        assertTrue(received.isEmpty())

        s.resolve(isMove = true)
        assertEquals(listOf("/sdcard/A/x.txt"), received["/sdcard/B"]?.paths)
        assertTrue(received["/sdcard/B"]!!.isCut)
        assertTrue(finished)
    }

    @Test
    fun aFolderRowWinsOverThePaneAroundIt() {
        val s = state()
        s.dragTo(Offset(700f, 150f))
        assertEquals("/sdcard/B/C", s.hoveredTarget?.location)
    }

    @Test
    fun aFolderRowInTheSamePaneTakesDropsButThePaneItselfDoesNot() {
        val s = state()
        s.dragTo(Offset(100f, 250f))
        assertEquals("/sdcard/A/sub", s.hoveredTarget?.location)
        s.move(Offset(100f, 600f))
        assertNull(s.hoveredTarget)
    }

    @Test
    fun aFolderCannotBeDroppedIntoItself() {
        val s = state()
        s.dragTo(Offset(100f, 250f), "/sdcard/A/sub")
        assertNull(s.hoveredTarget)
    }

    @Test
    fun aTrashDropIsAlwaysAMove() {
        val s = state().apply {
            register(zone(ActivePanel.RIGHT, "trash", Rect(600f, 800f, 700f, 900f), kind = DropZoneKind.TRASH))
        }
        s.dragTo(Offset(650f, 850f))
        s.drop()
        s.resolve(isMove = false)
        assertTrue(received["trash"]!!.isCut)
    }

    @Test
    fun cancellingTheMenuLeavesEverythingAsIs() {
        val s = state()
        s.dragTo(Offset(700f, 600f))
        s.drop()
        s.resolve(isMove = null)
        assertTrue(received.isEmpty())
        assertFalse(finished)
    }

}
