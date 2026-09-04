# Add project specific ProGuard rules here.
# AGP already merges in the default rules from proguard-android-optimize.txt
# (see build.gradle.kts) plus every library's own bundled consumer-rules.pro —
# Hilt/Dagger, Room, Coil, Compose, kotlinx.coroutines and Media3 all ship
# their own, so most of what would normally go here is already handled.
# What's below covers the libraries in this app that either don't ship
# complete consumer rules or lean on reflection R8 can't see into statically.

# Kotlin metadata / reflection used by kotlinx.serialization-adjacent and
# general Kotlin reflection (KClass, data class componentN, etc.) across the
# app's own model classes.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keepclassmembers class kotlin.Metadata { *; }

# --- App's own domain/data model classes ---
# Hand-rolled JSON (org.json) in FolderCacheManager and elsewhere reads/writes
# these by field name via reflection-free explicit put()/get() calls, so they
# don't strictly need keeping for that — but Room entities and anything
# Parcelable/Serializable passed through SavedStateHandle or Intent extras do.
-keep class com.antigravity.filemanager.domain.model.** { *; }
-keep class com.antigravity.filemanager.data.local.db.** { *; }

# --- Google API client (Drive) ---
# com.google.api.client.json.GenericJson subclasses are populated via
# reflection over their declared fields — R8 will otherwise strip or rename
# fields it never sees a direct reference to, silently breaking Drive
# responses at runtime instead of failing to compile.
-keep class com.google.api.services.drive.** { *; }
-keep class com.google.api.client.** { *; }
-keepclassmembers class * extends com.google.api.client.json.GenericJson {
    <fields>;
}
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**

# --- Dropbox SDK ---
-keep class com.dropbox.core.** { *; }
-dontwarn com.dropbox.core.**

# --- Apache FtpServer / MINA / SLF4J ---
# FtpServer resolves several of its own command/listener implementations by
# class name (java.lang.Class.forName-style plugin loading), which R8's
# static analysis can't follow.
-keep class org.apache.ftpserver.** { *; }
-keep class org.apache.mina.** { *; }
-keep interface org.apache.ftpserver.** { *; }
-dontwarn org.apache.ftpserver.**
-dontwarn org.apache.mina.**
-dontwarn org.slf4j.**

# --- zip4j ---
-keep class net.lingala.zip4j.** { *; }
-dontwarn net.lingala.zip4j.**

# --- OkHttp / Okio ---
-dontwarn okhttp3.**
-dontwarn okio.**

# --- Media3 / ExoPlayer ---
# Codec/extension classes are looked up by name for optional/decoder
# extensions that aren't even on this app's classpath.
-dontwarn com.google.android.exoplayer2.**
-dontwarn androidx.media3.**

# Keep default no-arg constructors on anything Parcelable/Serializable so
# CREATOR / deserialization reflection keeps working.
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
}

# Line numbers in stack traces from a minified build are still useful for
# crash reports — keep them instead of stripping to raw obfuscated offsets.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
