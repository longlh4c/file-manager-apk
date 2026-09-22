package com.antigravity.filemanager.presentation.network

import android.content.ClipData
import kotlinx.coroutines.flow.update
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.filemanager.data.local.preferences.PreferenceManager
import com.antigravity.filemanager.domain.usecase.FtpServerUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NetworkAccessUiState(
    val isRunning: Boolean = false,
    val ipAddress: String? = null,
    val port: Int = 1524,
    val httpPort: Int = 8080,
    val portInput: String = "1524",
    val httpPortInput: String = "8080",
    val password: String = "",
    val copiedMessage: String? = null,
    // While running: the ports the servers actually bound (the fields above may be edited or
    // adjusted, e.g. a Web port equal to the FTP port is moved) and which of them came up.
    val runningPort: Int = 1524,
    val runningHttpPort: Int = 8080,
    val ftpRunning: Boolean = false,
    val httpRunning: Boolean = false
) {
    val ftpAccessUrl: String
        get() = if (ipAddress != null) "ftp://$ipAddress:$port" else ""

    val httpAccessUrl: String
        get() = if (ipAddress != null) "http://$ipAddress:$httpPort" else ""

    val accessUrl: String
        get() = httpAccessUrl.ifEmpty { ftpAccessUrl }
}

@HiltViewModel
class NetworkAccessViewModel @Inject constructor(
    private val ftpServerUseCase: FtpServerUseCase,
    private val preferenceManager: PreferenceManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(NetworkAccessUiState())
    val uiState: StateFlow<NetworkAccessUiState> = _uiState.asStateFlow()

    init {
        // Saved settings are read once. Collecting them continuously echoed every keystroke's
        // (asynchronous) save back into the field being typed in, so typing quickly dropped
        // characters: "owl123" ended up as "o12".
        viewModelScope.launch {
            val port = preferenceManager.ftpPortFlow.first()
            val httpPort = preferenceManager.httpPortFlow.first()
            val pass = preferenceManager.ftpPasswordFlow.first().take(8)
            _uiState.update { old -> old.copy(
                port = port,
                portInput = port.toString(),
                httpPort = httpPort,
                httpPortInput = httpPort.toString(),
                password = pass
            ) }
        }
        viewModelScope.launch {
            // An error that was already there when this screen opened was shown before.
            var shownErrorId = ftpServerUseCase.getState().errorId
            ftpServerUseCase.observeState().collectLatest { state ->
                val newError = state.error?.takeIf { state.errorId != shownErrorId }
                shownErrorId = state.errorId
                _uiState.update { old -> old.copy(
                    isRunning = state.isRunning,
                    ipAddress = state.ipAddress,
                    runningPort = state.port,
                    runningHttpPort = state.httpPort,
                    ftpRunning = state.ftpRunning,
                    httpRunning = state.httpRunning,
                    copiedMessage = newError ?: old.copiedMessage
                ) }
                if (newError != null) {
                    // Cleared again so the same error can show on the next failed start.
                    launch {
                        delay(4000)
                        _uiState.update { old -> if (old.copiedMessage == newError) old.copy(copiedMessage = null) else old }
                    }
                }
            }
        }
    }

    fun onPortChanged(portStr: String) {
        val filtered = portStr.filter { it.isDigit() }.take(5)
        val portInt = filtered.toIntOrNull()
        _uiState.update { old -> old.copy(
            portInput = filtered,
            port = portInt ?: _uiState.value.port
        ) }
        if (portInt != null && portInt in 1024..65535) {
            viewModelScope.launch {
                saveConfig()
            }
        }
    }

    fun onHttpPortChanged(httpPortStr: String) {
        val filtered = httpPortStr.filter { it.isDigit() }.take(5)
        val portInt = filtered.toIntOrNull()
        _uiState.update { old -> old.copy(
            httpPortInput = filtered,
            httpPort = portInt ?: _uiState.value.httpPort
        ) }
        if (portInt != null && portInt in 1024..65535) {
            viewModelScope.launch {
                saveConfig()
            }
        }
    }

    fun onPasswordChanged(password: String) {
        val filtered = password.filter { it.isLetterOrDigit() }.take(8)
        _uiState.update { old -> old.copy(password = filtered) }
        viewModelScope.launch {
            saveConfig()
        }
    }

    fun copyToClipboard(context: Context, text: String, label: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        _uiState.update { old -> old.copy(copiedMessage = "Copied $label to clipboard!") }
        viewModelScope.launch {
            delay(2500)
            if (_uiState.value.copiedMessage != null) {
                _uiState.update { old -> old.copy(copiedMessage = null) }
            }
        }
    }

    fun toggleService() {
        viewModelScope.launch {
            val state = _uiState.value
            if (state.isRunning) {
                ftpServerUseCase.stop()
            } else {
                val effectiveFtpPort = if (state.portInput.isBlank()) {
                    1524
                } else {
                    (state.portInput.toIntOrNull() ?: 1524).coerceIn(1024, 65535)
                }
                var effectiveHttpPort = if (state.httpPortInput.isBlank()) {
                    8080
                } else {
                    (state.httpPortInput.toIntOrNull() ?: 8080).coerceIn(1024, 65535)
                }
                if (effectiveHttpPort == effectiveFtpPort) {
                    effectiveHttpPort = if (effectiveFtpPort == 8080) 8081 else 8080
                }
                _uiState.update { old ->
                    old.copy(
                        port = effectiveFtpPort,
                        portInput = effectiveFtpPort.toString(),
                        httpPort = effectiveHttpPort,
                        httpPortInput = effectiveHttpPort.toString()
                    )
                }
                preferenceManager.saveNetworkConfig(
                    ftpPort = effectiveFtpPort,
                    httpPort = effectiveHttpPort,
                    password = state.password,
                    isRandom = false
                )
                ftpServerUseCase.start(
                    port = effectiveFtpPort,
                    pass = state.password,
                    random = false,
                    httpPort = effectiveHttpPort
                )
            }
        }
    }

    private suspend fun saveConfig() {
        val s = _uiState.value
        val ftpP = if (s.portInput.isBlank()) 1524 else (s.portInput.toIntOrNull() ?: 1524).coerceIn(1024, 65535)
        val httpP = if (s.httpPortInput.isBlank()) 8080 else (s.httpPortInput.toIntOrNull() ?: 8080).coerceIn(1024, 65535)
        preferenceManager.saveNetworkConfig(ftpP, httpP, s.password, false)
    }
}
