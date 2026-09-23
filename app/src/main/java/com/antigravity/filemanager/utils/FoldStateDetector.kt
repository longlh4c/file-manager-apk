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

    // Dual-layer detection:
    // 1) Hardware hinge state: FoldingFeature is FLAT or HALF_OPENED
    // 2) Display width: screenWidthDp >= 600dp (on foldables like Vivo X Fold, inner display is ~770dp wide, outer display is ~390-410dp)
    // Only a VERTICAL hinge splits the screen into side-by-side halves. A clamshell flip phone
    // reports its horizontal hinge as FLAT/HALF_OPENED too whenever it is open, which made a
    // ~400dp-wide phone count as "unfolded" and allowed two panels on it.
    val isHingeUnfolded = foldingFeature != null &&
        foldingFeature.orientation == FoldingFeature.Orientation.VERTICAL && (
            foldingFeature.state == FoldingFeature.State.FLAT ||
            foldingFeature.state == FoldingFeature.State.HALF_OPENED
        )
    val isWideScreen = configuration.screenWidthDp >= 600

    val isUnfolded = isHingeUnfolded || isWideScreen

    return remember(isUnfolded, foldingFeature) {
        FoldablePosture(
            isUnfolded = isUnfolded,
            isSeparating = foldingFeature?.isSeparating == true,
            hingeOrientation = foldingFeature?.orientation,
            foldingFeature = foldingFeature
        )
    }
}
