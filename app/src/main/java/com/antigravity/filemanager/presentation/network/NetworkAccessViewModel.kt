package com.antigravity.filemanager.presentation.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.filemanager.data.local.preferences.PreferenceManager
import com.antigravity.filemanager.domain.usecase.FtpServerUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NetworkAccessUiState(
    val isRunning: Boolean = false,
    val ipAddress: String? = null,
    val port: Int = 1524,
    val password: String = "",
    val showHiddenFiles: Boolean = false
) {
    val accessUrl: String
        get() = if (ipAddress != null) "ftp://$ipAddress:$port" else ""
}

@HiltViewModel
class NetworkAccessViewModel @Inject constructor(
    private val ftpServerUseCase: FtpServerUseCase,
    private val preferenceManager: PreferenceManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(NetworkAccessUiState())
    val uiState: StateFlow<NetworkAccessUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            preferenceManager.ftpPortFlow.collectLatest { port ->
                _uiState.value = _uiState.value.copy(port = port)
            }
        }
        viewModelScope.launch {
            preferenceManager.ftpPasswordFlow.collectLatest { pass ->
                _uiState.value = _uiState.value.copy(password = pass)
            }
        }
        viewModelScope.launch {
            preferenceManager.ftpShowHiddenFlow.collectLatest { hidden ->
                _uiState.value = _uiState.value.copy(showHiddenFiles = hidden)
            }
        }
        viewModelScope.launch {
            ftpServerUseCase.observeState().collectLatest { state ->
                _uiState.value = _uiState.value.copy(
                    isRunning = state.isRunning,
                    ipAddress = state.ipAddress
                )
            }
        }
    }

    fun onPortChanged(portStr: String) {
        val port = portStr.toIntOrNull() ?: 1524
        _uiState.value = _uiState.value.copy(port = port)
        viewModelScope.launch {
            saveConfig()
        }
    }

    fun onPasswordChanged(password: String) {
        _uiState.value = _uiState.value.copy(password = password)
        viewModelScope.launch {
            saveConfig()
        }
    }

    fun onShowHiddenToggled(checked: Boolean) {
        _uiState.value = _uiState.value.copy(showHiddenFiles = checked)
        viewModelScope.launch {
            saveConfig()
        }
    }

    fun toggleService() {
        viewModelScope.launch {
            val state = _uiState.value
            if (state.isRunning) {
                ftpServerUseCase.stop()
            } else {
                ftpServerUseCase.start(
                    port = state.port,
                    pass = state.password,
                    random = false,
                    showHidden = state.showHiddenFiles
                )
            }
        }
    }

    private suspend fun saveConfig() {
        val s = _uiState.value
        preferenceManager.saveFtpConfig(s.port, s.password, false, s.showHiddenFiles)
    }
}
