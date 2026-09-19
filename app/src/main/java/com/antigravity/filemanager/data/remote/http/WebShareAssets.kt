package com.antigravity.filemanager.data.remote.http

import android.content.Context

object WebShareAssets {
    private var cachedHtml: String? = null

    fun getIndexHtml(context: Context): String {
        cachedHtml?.let { return it }
        return try {
            val content = context.assets.open("webshare/index.html").bufferedReader().use { it.readText() }
            cachedHtml = content
            content
        } catch (e: Exception) {
            android.util.Log.e("WebShareAssets", "Failed to load webshare/index.html", e)
            "<!DOCTYPE html><html><body><h3>Failed to load Web Share UI</h3></body></html>"
        }
    }
}
