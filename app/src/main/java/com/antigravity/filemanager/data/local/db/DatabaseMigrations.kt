package com.antigravity.filemanager.data.local.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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

    /** 7: the recent_files table behind Recent > Opened. */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `recent_files` (`path` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                    "`lastOpenedAt` INTEGER NOT NULL, `openCount` INTEGER NOT NULL, PRIMARY KEY(`path`))"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_recent_files_lastOpenedAt` ON `recent_files` (`lastOpenedAt`)")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_6_7)
}
