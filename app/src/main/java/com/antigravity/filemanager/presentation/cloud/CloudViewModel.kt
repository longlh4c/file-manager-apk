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
import com.antigravity.filemanager.data.remote.cloud.api.TeraBoxApiClient

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
    private val teraBoxApiClient: TeraBoxApiClient,
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
                if (!_uiState.value.isReorderMode) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        accounts = list
                    )
                }
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
                    } else if (account.provider == CloudProvider.TERABOX && (account.email.startsWith("user@") || account.email.startsWith("account@") || account.email.isBlank() || account.email == "terabox_user" || account.email == "TeraBox User")) {
                        launch(kotlinx.coroutines.Dispatchers.IO) {
                            val rawCookie = account.sessionHandle.takeIf { !it.isNullOrBlank() } ?: account.accessToken ?: ""
                            val userInfoRes = teraBoxApiClient.getUserInfo(rawCookie)
                            val uInfo = userInfoRes.getOrNull()
                            if (uInfo != null) {
                                val displayEmail = if (!uInfo.email.isNullOrBlank()) {
                                    uInfo.email
                                } else if (!uInfo.uname.isNullOrBlank() && uInfo.uname != "TeraBox User") {
                                    uInfo.uname
                                } else {
                                    null
                                }
                                if (!displayEmail.isNullOrBlank() && displayEmail != account.email) {
                                    cloudUseCase.addAccount(account.copy(email = displayEmail))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private var originalAccountsBeforeEdit: List<CloudAccount> = emptyList()

    fun enterReorderMode() {
        originalAccountsBeforeEdit = _uiState.value.accounts
        _uiState.value = _uiState.value.copy(isReorderMode = true)
    }

    fun confirmReorder() {
        val accountsToSave = _uiState.value.accounts
        _uiState.value = _uiState.value.copy(isReorderMode = false)
        viewModelScope.launch {
            cloudUseCase.reorderAccounts(accountsToSave)
        }
    }

    fun cancelReorder() {
        _uiState.value = _uiState.value.copy(
            isReorderMode = false,
            accounts = originalAccountsBeforeEdit
        )
    }

    fun toggleReorderMode() {
        if (_uiState.value.isReorderMode) {
            confirmReorder()
        } else {
            enterReorderMode()
        }
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

    fun moveAccountToTop(index: Int) {
        if (index <= 0) return
        val list = _uiState.value.accounts.toMutableList()
        val item = list.removeAt(index)
        list.add(0, item)
        _uiState.value = _uiState.value.copy(accounts = list)
    }

    fun moveAccountToBottom(index: Int) {
        if (index >= _uiState.value.accounts.size - 1) return
        val list = _uiState.value.accounts.toMutableList()
        val item = list.removeAt(index)
        list.add(item)
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
                CloudProvider.TERABOX -> 1024L * 1024 * 1024 * 1024 // 1 TB
            }

            var resolvedToken = token
            var masterKey: String? = null
            var dbSessionHandle: String? = null

            val accountId = UUID.randomUUID().toString()

            if (provider == CloudProvider.TERABOX) {
                val rawCookie = if (!session.isNullOrBlank() && session.contains("ndus=")) session!! else (token ?: session ?: "")
                val cleanNdus = teraBoxApiClient.extractCleanNdus(rawCookie)
                if (cleanNdus.isBlank()) {
                    _uiState.value = _uiState.value.copy(
                        isAddingAccount = false,
                        addAccountError = "Invalid TeraBox session token (ndus is required)"
                    )
                    return@launch
                }
                // Use full rawCookie (or fallback to ndus) to authenticate
                val effectiveCookie = if (rawCookie.contains("ndus=")) rawCookie else "ndus=$cleanNdus"
                val quotaRes = teraBoxApiClient.getQuota(effectiveCookie)
                if (quotaRes.isFailure) {
                    _uiState.value = _uiState.value.copy(
                        isAddingAccount = false,
                        addAccountError = quotaRes.exceptionOrNull()?.message ?: "Failed to connect to TeraBox. Please verify your token."
                    )
                    return@launch
                }
                val quota = quotaRes.getOrNull()
                val actualTotal = if (quota != null && quota.totalBytes > 0) quota.totalBytes else totalBytes
                val actualUsed = quota?.usedBytes ?: 0L
                val userInfoRes = teraBoxApiClient.getUserInfo(effectiveCookie)
                val uInfo = userInfoRes.getOrNull()
                val resolvedEmail = if (!uInfo?.email.isNullOrBlank()) {
                    uInfo!!.email!!
                } else if (!uInfo?.uname.isNullOrBlank() && uInfo!!.uname != "TeraBox User") {
                    uInfo!!.uname
                } else if (email.isNotBlank() && !email.startsWith("account@") && email != "terabox_user" && email != "TeraBox User") {
                    email
                } else {
                    uInfo?.uname?.takeIf { it.isNotBlank() } ?: "terabox_user"
                }

                val newAccount = CloudAccount(
                    id = accountId,
                    provider = CloudProvider.TERABOX,
                    accountName = name.ifBlank { "TeraBox" },
                    email = resolvedEmail,
                    displayOrder = _uiState.value.accounts.size,
                    totalSpaceBytes = actualTotal,
                    usedSpaceBytes = actualUsed,
                    accessToken = cleanNdus,
                    sessionHandle = effectiveCookie,
                    refreshToken = null
                )
                cloudUseCase.addAccount(newAccount)
                _uiState.value = _uiState.value.copy(showAddDialog = false, isAddingAccount = false)
                onSuccess(accountId, newAccount.accountName)
                return@launch
            }

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
