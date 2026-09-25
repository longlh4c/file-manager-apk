package com.antigravity.filemanager.utils

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo

@Immutable
data class FoldablePosture(
    val isUnfolded: Boolean,
    /** False until the window's first layout info arrives; [isUnfolded] is meaningless until then. */
    val isKnown: Boolean = true,
    val isSeparating: Boolean = false,
    val hingeOrientation: FoldingFeature.Orientation? = null,
    val foldingFeature: FoldingFeature? = null
)

@Composable
fun rememberFoldablePosture(): FoldablePosture {
    val context = LocalContext.current
    val activity = context as? Activity
    val configuration = LocalConfiguration.current

    val windowLayoutInfo by produceState<WindowLayoutInfo?>(initialValue = null, key1 = activity) {
        if (activity != null) {
            try {
                WindowInfoTracker.getOrCreate(activity)
                    .windowLayoutInfo(activity)
                    .collect { value = it }
            } catch (e: Exception) {
                value = null
            }
        }
    }

    val foldingFeature = windowLayoutInfo?.displayFeatures
        ?.filterIsInstance<FoldingFeature>()
        ?.firstOrNull()

    // Unfolded means a real foldable whose hinge is open (FLAT or HALF_OPENED). A wide screen on
    // its own used to count as well, so any phone turned sideways, a tablet, or a folded foldable's
    // outer screen in landscape could open two panels. A book-style foldable turned sideways
    // reports its hinge as HORIZONTAL but is still open, so a horizontal hinge counts only on a
    // wide screen; that keeps out clamshell flip phones, whose open hinge is horizontal on a
    // ~400dp-wide screen.
    val isHingeOpen = foldingFeature != null && (
        foldingFeature.state == FoldingFeature.State.FLAT ||
            foldingFeature.state == FoldingFeature.State.HALF_OPENED
        )
    val isUnfolded = isHingeOpen && (
        foldingFeature?.orientation == FoldingFeature.Orientation.VERTICAL ||
            configuration.screenWidthDp >= 600
        )

    return remember(isUnfolded, foldingFeature, windowLayoutInfo != null) {
        FoldablePosture(
            isUnfolded = isUnfolded,
            // Nothing is known before the first window layout arrives; treating that moment as
            // "folded" would close dual panel on every app start or activity recreation.
            isKnown = windowLayoutInfo != null,
            isSeparating = foldingFeature?.isSeparating == true,
            hingeOrientation = foldingFeature?.orientation,
            foldingFeature = foldingFeature
        )
    }
}
