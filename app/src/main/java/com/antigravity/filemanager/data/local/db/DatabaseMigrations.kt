package com.antigravity.filemanager.data.local.db

import androidx.room.migration.Migration

/**
 * Schema migrations for [AppDatabase]. The database holds the recycle-bin index (without it the
 * files under .filemanager_trash can never be restored) and every connected cloud account, so a
 * version bump must never fall back to wiping it: add a Migration(n, n + 1) here for each bump.
 * The exported schemas under app/schemas are what DatabaseMigrationsTest checks this list against.
 */
object DatabaseMigrations {
    /** First version whose schema is exported; older versions shipped without one. */
    const val FIRST_EXPORTED_VERSION = 6

    /** Versions from before schemas were exported. No migration can be written for them, so they
     * are the only ones still allowed to be rebuilt from scratch. */
    val LEGACY_VERSIONS: IntArray = (1 until FIRST_EXPORTED_VERSION).toList().toIntArray()

    val ALL: Array<Migration> = arrayOf()
}
