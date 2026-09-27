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
    fun droppingOnTheSameDriveMovesWithoutAsking() {
        val s = state()
        s.dragTo(Offset(700f, 600f))
        assertEquals("/sdcard/B", s.hoveredTarget?.location)
        s.drop()
        assertNull(s.pendingDrop)
        assertEquals(listOf("/sdcard/A/x.txt"), received["/sdcard/B"]?.paths)
        assertTrue(received["/sdcard/B"]!!.isCut)
        assertTrue(finished)
    }

    @Test
    fun droppingOntoAnotherDriveCopies() {
        val s = state().apply {
            register(zone(ActivePanel.RIGHT, "/storage/1A2B-3C4D/Music", Rect(500f, 300f, 1000f, 400f)))
            register(zone(ActivePanel.RIGHT, cloudLocation("mega1", "/Photos"), Rect(500f, 400f, 1000f, 500f)))
        }
        s.dragTo(Offset(700f, 350f))
        s.drop()
        assertFalse(received["/storage/1A2B-3C4D/Music"]!!.isCut)

        s.dragTo(Offset(700f, 450f))
        s.drop()
        assertFalse(received[cloudLocation("mega1", "/Photos")]!!.isCut)
    }

    @Test
    fun cloudItemsMoveWithinTheirOwnAccountOnly() {
        val s = DualPaneDragState().apply {
            register(zone(ActivePanel.RIGHT, cloudLocation("mega1", "/B"), Rect(500f, 0f, 1000f, 500f)))
            register(zone(ActivePanel.RIGHT, cloudLocation("mega2", "/B"), Rect(500f, 500f, 1000f, 1000f)))
        }
        fun dragFromMega1(at: Offset) {
            s.start(
                DualPaneDragPayload(
                    ActivePanel.LEFT,
                    GlobalClipboardState(paths = listOf("/A/x.jpg"), sourceCloudAccountId = "mega1"),
                    cloudLocation("mega1", "/A")
                ) {},
                Offset(10f, 10f)
            )
            s.move(at)
            s.drop()
        }
        dragFromMega1(Offset(700f, 200f))
        assertTrue(received[cloudLocation("mega1", "/B")]!!.isCut)
        dragFromMega1(Offset(700f, 700f))
        assertFalse(received[cloudLocation("mega2", "/B")]!!.isCut)
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
    fun cancellingTheTrashConfirmationLeavesEverythingAsIs() {
        val s = state().apply {
            register(zone(ActivePanel.RIGHT, "trash", Rect(600f, 800f, 700f, 900f), kind = DropZoneKind.TRASH))
        }
        s.dragTo(Offset(650f, 850f))
        s.drop()
        s.resolve(isMove = null)
        assertTrue(received.isEmpty())
        assertFalse(finished)
    }

}
