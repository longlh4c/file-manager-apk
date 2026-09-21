package com.antigravity.filemanager.presentation.cloud

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.antigravity.filemanager.data.remote.cloud.api.DropboxAuthManager
import com.antigravity.filemanager.domain.model.CloudAccount
import com.antigravity.filemanager.domain.model.CloudProvider
import com.antigravity.filemanager.domain.usecase.CloudStorageUseCase
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.v2.DbxClientV2
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

// Activity handling the Dropbox OAuth2/PKCE redirect and saving the account.
@AndroidEntryPoint
class DropboxAuthActivity : ComponentActivity() {

    @Inject lateinit var cloudUseCase: CloudStorageUseCase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri == null) {
            finish()
            return
        }

        lifecycleScope.launch {
            // finishFromRedirect throws when the user denies access or the CSRF state doesn't match;
            // uncaught here that crashed the app instead of just reporting a failed login.
            val finish = try {
                withContext(Dispatchers.IO) { DropboxAuthManager.handleRedirect(this@DropboxAuthActivity, uri) }
            } catch (e: Exception) {
                android.util.Log.w("DropboxAuthActivity", "Dropbox login failed", e)
                null
            }
            if (finish == null) {
                Toast.makeText(this@DropboxAuthActivity, "Dropbox login was cancelled or expired, please try again", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }

            val requestConfig = DbxRequestConfig.newBuilder("FileManagerPlus/1.0").build()
            val client = DbxClientV2(requestConfig, finish.accessToken)
            val email = try {
                withContext(Dispatchers.IO) { client.users().currentAccount.email }
            } catch (e: Exception) {
                "account@dropbox.com"
            }

            // Signing in again to an already connected Dropbox account refreshes its tokens in place
            // instead of adding a duplicate entry.
            val existing = cloudUseCase.getAccounts().firstOrNull {
                it.provider == CloudProvider.DROPBOX && it.email.equals(email, ignoreCase = true) && email != "account@dropbox.com"
            }
            val account = CloudAccount(
                id = existing?.id ?: UUID.randomUUID().toString(),
                provider = CloudProvider.DROPBOX,
                accountName = "Dropbox",
                email = email,
                totalSpaceBytes = 2L * 1024 * 1024 * 1024,
                usedSpaceBytes = 0L,
                accessToken = finish.accessToken,
                refreshToken = finish.refreshToken,
                sessionHandle = finish.expiresAt?.toString()
            )
            cloudUseCase.addAccount(account)
            Toast.makeText(this@DropboxAuthActivity, "Connected to Dropbox ($email)", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
