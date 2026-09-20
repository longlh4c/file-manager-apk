package com.antigravity.filemanager.domain.usecase

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class ActivePanel {
    LEFT,
    RIGHT
}

@Singleton
class DualPanelManager @Inject constructor() {

    private val _isDualPanelActive = MutableStateFlow(false)
    val isDualPanelActive: StateFlow<Boolean> = _isDualPanelActive.asStateFlow()

    private val _activePanel = MutableStateFlow(ActivePanel.LEFT)
    val activePanel: StateFlow<ActivePanel> = _activePanel.asStateFlow()

    fun setActivePanel(panel: ActivePanel) {
        if (_activePanel.value != panel) {
            _activePanel.value = panel
        }
    }

    fun toggle(isUnfolded: Boolean, onNotice: (String) -> Unit = {}) {
        if (!isUnfolded) {
            onNotice("Dual panel mode is only available when device is unfolded")
            return
        }
        val nextState = !_isDualPanelActive.value
        _isDualPanelActive.value = nextState
        if (nextState) {
            _activePanel.value = ActivePanel.RIGHT
        } else {
            _activePanel.value = ActivePanel.LEFT
        }
    }

    fun open(isUnfolded: Boolean) {
        if (isUnfolded) {
            _isDualPanelActive.value = true
            _activePanel.value = ActivePanel.RIGHT
        }
    }

    fun close() {
        _isDualPanelActive.value = false
        _activePanel.value = ActivePanel.LEFT
    }

    fun onFoldPostureChanged(isUnfolded: Boolean) {
        if (!isUnfolded && _isDualPanelActive.value) {
            // Automatically collapse when device is folded shut to avoid cramped UI on outer display
            _isDualPanelActive.value = false
            _activePanel.value = ActivePanel.LEFT
        }
    }
}

