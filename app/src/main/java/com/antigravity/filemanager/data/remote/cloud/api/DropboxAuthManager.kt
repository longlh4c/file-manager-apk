package com.antigravity.filemanager.data.remote.cloud.api

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.antigravity.filemanager.BuildConfig
import com.dropbox.core.DbxAppInfo
import com.dropbox.core.DbxAuthFinish
import com.dropbox.core.DbxPKCEWebAuth
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.DbxSessionStore
import com.dropbox.core.DbxWebAuth
import com.dropbox.core.TokenAccessType

// Dropbox OAuth2/PKCE authorization flow manager via Custom Tabs.
object DropboxAuthManager {
    const val APP_KEY = BuildConfig.DROPBOX_APP_KEY
    const val REDIRECT_URI = BuildConfig.DROPBOX_REDIRECT_URI
    private const val PREFS_NAME = "dropbox_auth_prefs"
    private const val KEY_CSRF_STATE = "csrf_state"

    private var pkceWebAuth: DbxPKCEWebAuth? = null

    private fun sessionStore(context: Context): DbxSessionStore {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return object : DbxSessionStore {
            override fun get(): String? = prefs.getString(KEY_CSRF_STATE, null)
            override fun set(value: String?) { prefs.edit().putString(KEY_CSRF_STATE, value).apply() }
            override fun clear() { prefs.edit().remove(KEY_CSRF_STATE).apply() }
        }
    }

    fun startAuth(context: Context) {
        val requestConfig = DbxRequestConfig.newBuilder("FileManagerPlus/1.0").build()
        val appInfo = DbxAppInfo(APP_KEY)
        val webAuth = DbxPKCEWebAuth(requestConfig, appInfo)
        pkceWebAuth = webAuth

        val request = DbxWebAuth.newRequestBuilder()
            .withRedirectUri(REDIRECT_URI, sessionStore(context))
            .withTokenAccessType(TokenAccessType.OFFLINE)
            .build()
        val authorizeUrl = webAuth.authorize(request)

        val customTabsIntent = CustomTabsIntent.Builder().build()
        customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        customTabsIntent.launchUrl(context, Uri.parse(authorizeUrl))
    }

    /** Returns null if there is no in-memory auth attempt to finish (e.g. process was killed). */
    fun handleRedirect(context: Context, redirectUri: Uri): DbxAuthFinish? {
        val webAuth = pkceWebAuth ?: return null
        val params = mutableMapOf<String, Array<String>>()
        for (name in redirectUri.queryParameterNames) {
            redirectUri.getQueryParameter(name)?.let { params[name] = arrayOf(it) }
        }
        val finish = webAuth.finishFromRedirect(REDIRECT_URI, sessionStore(context), params)
        pkceWebAuth = null
        return finish
    }
}
