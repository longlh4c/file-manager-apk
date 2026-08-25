package com.antigravity.filemanager.presentation.cloud

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.filemanager.domain.model.CloudAccount
import com.antigravity.filemanager.domain.model.CloudProvider
import com.antigravity.filemanager.domain.usecase.CloudStorageUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject

import com.antigravity.filemanager.data.remote.cloud.CloudManager
import com.antigravity.filemanager.data.remote.cloud.api.MegaApiClient

data class CloudUiState(
    val isLoading: Boolean = false,
    val accounts: List<CloudAccount> = emptyList(),
    val isReorderMode: Boolean = false,
    val showAddDialog: Boolean = false,
    val isAddingAccount: Boolean = false,
    val addAccountError: String? = null
)

@HiltViewModel
class CloudViewModel @Inject constructor(
    private val cloudUseCase: CloudStorageUseCase,
    private val megaApiClient: MegaApiClient,
    private val cloudManager: CloudManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(CloudUiState(isLoading = true))
    val uiState: StateFlow<CloudUiState> = _uiState.asStateFlow()

    init {
        loadAccounts()
    }

    private fun loadAccounts() {
        viewModelScope.launch {
            cloudUseCase.observeAccounts().collect { list ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    accounts = list
                )
                // Auto-resolve missing or placeholder emails (e.g. user@mega.com)
                list.forEach { account ->
                    if (account.provider == CloudProvider.MEGA && (account.email.startsWith("user@") || account.email.startsWith("account@") || account.email.isBlank())) {
                        launch(kotlinx.coroutines.Dispatchers.IO) {
                            val realEmailRes = megaApiClient.getUserEmail(account)
                            val realEmail = realEmailRes.getOrNull()
                            if (!realEmail.isNullOrBlank() && realEmail != account.email) {
                                cloudUseCase.addAccount(account.copy(email = realEmail))
                            }
                        }
                    }
                }
            }
        }
    }

    fun toggleReorderMode() {
        val current = _uiState.value.isReorderMode
        if (current) {
            // Save new order to database
            viewModelScope.launch {
                cloudUseCase.reorderAccounts(_uiState.value.accounts)
            }
        }
        _uiState.value = _uiState.value.copy(isReorderMode = !current)
    }

    fun moveAccountUp(index: Int) {
        if (index <= 0) return
        val list = _uiState.value.accounts.toMutableList()
        Collections.swap(list, index, index - 1)
        _uiState.value = _uiState.value.copy(accounts = list)
    }

    fun moveAccountDown(index: Int) {
        if (index >= _uiState.value.accounts.size - 1) return
        val list = _uiState.value.accounts.toMutableList()
        Collections.swap(list, index, index + 1)
        _uiState.value = _uiState.value.copy(accounts = list)
    }

    fun addAccount(
        provider: CloudProvider,
        name: String,
        email: String,
        token: String? = null,
        session: String? = null,
        onSuccess: (String, String) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isAddingAccount = true, addAccountError = null)

            val totalBytes = when (provider) {
                CloudProvider.MEGA -> 20L * 1024 * 1024 * 1024
                CloudProvider.GOOGLE_DRIVE -> 15L * 1024 * 1024 * 1024
                CloudProvider.DROPBOX -> 2L * 1024 * 1024 * 1024
            }

            var resolvedToken = token
            var masterKey: String? = null
            var dbSessionHandle: String? = null

            val accountId = UUID.randomUUID().toString()

            // A short, non-JSON, non-"mega_session_" string is a raw MEGA password: perform a
            // real email+password login (the only way this app can obtain a genuine master
            // key). The password itself must never be persisted — only the resulting session id.
            val isMegaPasswordLogin = provider == CloudProvider.MEGA && !session.isNullOrBlank() &&
                session.length < 60 && !session.startsWith("mega_session_") && !session.startsWith("{")

            if (isMegaPasswordLogin) {
                val loginRes = megaApiClient.login(email, session!!)
                if (loginRes.isFailure) {
                    _uiState.value = _uiState.value.copy(
                        isAddingAccount = false,
                        addAccountError = loginRes.exceptionOrNull()?.message ?: "MEGA login failed"
                    )
                    return@launch
                }
                val pair = loginRes.getOrNull()
                resolvedToken = pair?.first
                dbSessionHandle = pair?.first
                masterKey = pair?.second
            } else if (!session.isNullOrBlank() && session.startsWith("{")) {
                val json = try { org.json.JSONObject(session) } catch (e: Exception) { null }
                masterKey = json?.optString("masterKey", "")?.takeIf { it.isNotBlank() }
                val sid = json?.optString("sid", "") ?: ""
                dbSessionHandle = if (sid.isNotBlank()) sid else "session_active"
                cloudManager.saveSessionPayload(accountId, session)
            } else if (!session.isNullOrBlank() && session.length > 300) {
                cloudManager.saveSessionPayload(accountId, session)
                dbSessionHandle = "session_active"
            } else {
                dbSessionHandle = session
            }

            val newAccount = CloudAccount(
                id = accountId,
                provider = provider,
                accountName = name.ifBlank { provider.name },
                email = email.ifBlank { "account@${provider.name.lowercase()}.com" },
                displayOrder = _uiState.value.accounts.size,
                totalSpaceBytes = totalBytes,
                usedSpaceBytes = 0L,
                accessToken = resolvedToken,
                sessionHandle = dbSessionHandle,
                refreshToken = masterKey
            )
            cloudUseCase.addAccount(newAccount)
            _uiState.value = _uiState.value.copy(showAddDialog = false, isAddingAccount = false)
            onSuccess(accountId, newAccount.accountName)
        }
    }

    fun clearAddAccountError() {
        _uiState.value = _uiState.value.copy(addAccountError = null)
    }

    fun removeAccount(id: String) {
        viewModelScope.launch {
            val account = _uiState.value.accounts.find { it.id == id }
            cloudManager.deleteSessionPayload(id)
            cloudUseCase.removeAccount(id)
            if (account != null) {
                cloudManager.clearProviderAuthData(account.provider)
            }
        }
    }

    fun setShowAddDialog(show: Boolean) {
        _uiState.value = _uiState.value.copy(showAddDialog = show, addAccountError = null)
    }
}
