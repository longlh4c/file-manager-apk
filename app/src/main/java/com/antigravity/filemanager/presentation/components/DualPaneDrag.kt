package com.antigravity.filemanager.presentation.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.antigravity.filemanager.domain.usecase.ActivePanel
import com.antigravity.filemanager.domain.usecase.GlobalClipboardState

/** Which dual-panel pane a screen is shown in; null outside dual-panel mode. */
val LocalPane = staticCompositionLocalOf<ActivePanel?> { null }

/** Drag-and-drop between the two panes; provided only while both panes are shown. */
val LocalDualPaneDrag = staticCompositionLocalOf<DualPaneDragState?> { null }

class DualPaneDragPayload(
    val sourcePane: ActivePanel,
    /** What is dragged, in clipboard form: local paths, or remote paths plus their account. */
    val items: GlobalClipboardState,
    /** Where it is dragged from: a local folder path, or [cloudLocation] for a cloud folder. */
    val sourceLocation: String,
    /** Called once the drop was acted on (e.g. to clear the source's selection). */
    val onFinished: () -> Unit
) {
    val paths: List<String> get() = items.paths

    /** Each dragged item's own location, in the same form as a zone's. */
    val itemLocations: List<String>
        get() = items.sourceCloudAccountId?.let { account -> paths.map { cloudLocation(account, it) } } ?: paths
}

enum class DropZoneKind {
    /** Copy/move into the folder at [DualPaneDropZone.location]. */
    FOLDER,
    /** Move the items to the (app or cloud) trash. */
    TRASH
}

/**
 * Somewhere items can be dropped: a whole folder view, one folder row, a dashboard card, a cloud
 * account. Its fields are refreshed on every recomposition and [bounds] on every layout, without
 * recomposing anything, so rows can register cheaply while a list scrolls.
 */
class DualPaneDropZone(
    val pane: ActivePanel,
    var location: String,
    var name: String,
    var kind: DropZoneKind,
    /** True for a zone covering a whole folder view; a smaller zone inside it wins. */
    var isPaneBackground: Boolean,
    /** Receives the dropped items with [GlobalClipboardState.isCut] set to the user's choice. */
    var onDrop: (items: GlobalClipboardState) -> Unit
) {
    var bounds: Rect = Rect.Zero
}

/** The drag-and-drop location of a folder in a cloud account. */
fun cloudLocation(accountId: String, path: String): String = "cloud:$accountId:$path"

/** A dropped-but-not-yet-confirmed drag, waiting on the Copy / Move / Cancel menu. */
class PendingDualPaneDrop(val payload: DualPaneDragPayload, val target: DualPaneDropZone)

/**
 * In-app drag between the panes: a long press on an item followed by a move starts it, an
 * overlay follows the finger, and releasing over a drop zone asks what to do ([pendingDrop]).
 * Positions are in window coordinates.
 */
class DualPaneDragState {
    var payload by mutableStateOf<DualPaneDragPayload?>(null)
        private set
    var pointer by mutableStateOf(Offset.Zero)
        private set
    var pendingDrop by mutableStateOf<PendingDualPaneDrop?>(null)
        private set

    private val zones = mutableStateListOf<DualPaneDropZone>()

    /** What a [DropZoneKind.TRASH] zone does with the dropped items (set by the app shell). */
    var onTrash: (GlobalClipboardState) -> Unit = {}

    /** Runs a drop into a destination no pane is showing — a dashboard card, a cloud account —
     * so it happens where it was dropped instead of opening that destination first. */
    var onDropInto: (location: String, items: GlobalClipboardState) -> Unit = { _, _ -> }


    /** The zone that would receive a drop at the current pointer position, if any. */
    val hoveredTarget: DualPaneDropZone?
        get() {
            val p = payload ?: return null
            val itemLocations = p.itemLocations
            return zones
                .filter { zone ->
                    zone.bounds.contains(pointer) &&
                        // A whole folder view only takes drops from the other pane.
                        !(zone.isPaneBackground && zone.pane == p.sourcePane) &&
                        zone.location != p.sourceLocation &&
                        // Never into one of the dragged folders or anywhere inside them.
                        itemLocations.none { zone.location == it || zone.location.startsWith("$it/") }
                }
                .minByOrNull { it.bounds.width * it.bounds.height }
        }

    fun start(payload: DualPaneDragPayload, at: Offset) {
        this.payload = payload
        pointer = at
    }

    fun move(to: Offset) {
        pointer = to
    }

    fun drop() {
        val p = payload ?: return
        val target = hoveredTarget
        payload = null
        if (target != null) pendingDrop = PendingDualPaneDrop(p, target)
    }

    fun cancel() {
        payload = null
    }

    /** The user's answer to the drop menu: true = move, false = copy, null = cancel. A trash
     * drop is always a move. */
    fun resolve(isMove: Boolean?) {
        val drop = pendingDrop ?: return
        pendingDrop = null
        if (isMove != null) {
            drop.target.onDrop(drop.payload.items.copy(isCut = isMove || drop.target.kind == DropZoneKind.TRASH))
            drop.payload.onFinished()
        }
    }

    fun register(zone: DualPaneDropZone) {
        if (zones.none { it === zone }) zones.add(zone)
    }

    fun unregister(zone: DualPaneDropZone) {
        zones.removeAll { it === zone }
    }
}

/**
 * Makes this element a drop zone for items dragged in dual-panel mode (a no-op otherwise).
 * Use [isPaneBackground] for the area showing a whole folder, so folder rows inside it win.
 */
fun Modifier.dualPaneDropZone(
    location: String,
    name: String,
    kind: DropZoneKind = DropZoneKind.FOLDER,
    isPaneBackground: Boolean = false,
    onDrop: (items: GlobalClipboardState) -> Unit
): Modifier = composed {
    val state = LocalDualPaneDrag.current
    val pane = LocalPane.current
    if (state == null || pane == null) return@composed this
    val zone = remember(pane) { DualPaneDropZone(pane, location, name, kind, isPaneBackground, onDrop) }
    SideEffect {
        zone.location = location
        zone.name = name
        zone.kind = kind
        zone.isPaneBackground = isPaneBackground
        zone.onDrop = onDrop
    }
    DisposableEffect(state, zone) {
        state.register(zone)
        onDispose { state.unregister(zone) }
    }
    onGloballyPositioned { zone.bounds = it.boundsInWindow() }
}

/**
 * Makes an item draggable to the other pane. A plain long press still just selects (the item's
 * own click handling); only moving the finger after the long press starts a drag. [items] is
 * read when the drag starts, so it sees the selection the long press just changed.
 */
fun Modifier.dualPaneDragSource(sourceLocation: String, items: () -> GlobalClipboardState, onFinished: () -> Unit): Modifier = composed {
    val state = LocalDualPaneDrag.current
    val pane = LocalPane.current
    if (state == null || pane == null) return@composed this
    val currentItems by rememberUpdatedState(items)
    val currentOnFinished by rememberUpdatedState(onFinished)
    val currentLocation by rememberUpdatedState(sourceLocation)
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    this
        .onGloballyPositioned { coordinates = it }
        .pointerInput(state, pane) {
            // Watched in the Initial pass: the item's own click handling consumes every event
            // after its long press fires, which made a regular drag detector give up right there.
            val startDistance = 16.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                // Phase 1: the finger must stay put for a long press; moving first is a scroll.
                val notLongPress = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) return@withTimeoutOrNull true
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull true
                    }
                    @Suppress("UNREACHABLE_CODE") true
                }
                if (notLongPress != null) return@awaitEachGesture
                // Phase 2: long-pressed (the item has just toggled its selection). Only moving
                // on from here starts a drag; lifting without moving leaves it a plain long press.
                var dragging = false
                try {
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (dragging) { dragging = false; state.drop() }
                            break
                        }
                        val window = coordinates?.localToWindow(change.position) ?: continue
                        if (!dragging && (change.position - down.position).getDistance() > startDistance) {
                            val dragged = currentItems()
                            if (dragged.paths.isNotEmpty()) {
                                dragging = true
                                state.start(DualPaneDragPayload(pane, dragged, currentLocation, currentOnFinished), window)
                            }
                        }
                        if (dragging) {
                            state.move(window)
                            change.consume() // no list scrolling underneath while dragging
                        }
                    }
                } finally {
                    if (dragging) state.cancel() // gesture cut short (item left the screen, etc.)
                }
            }
        }
}

