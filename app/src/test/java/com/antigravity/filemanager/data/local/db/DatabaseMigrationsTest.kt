package com.antigravity.filemanager.data.local.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards against a version bump without a migration. Room exports a schema file per version
 * on every build, so the newest exported schema is the current version: every step from
 * [DatabaseMigrations.FIRST_EXPORTED_VERSION] up to it must be covered by exactly one migration.
 */
class DatabaseMigrationsTest {

    private val schemaDir = File("schemas/${AppDatabase::class.java.name}")

    private fun exportedVersions(): List<Int> =
        schemaDir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { it.nameWithoutExtension.toIntOrNull() }
            .sorted()

    @Test
    fun everyExportedVersionHasItsSchemaCheckedIn() {
        val versions = exportedVersions()
        assertTrue("No exported schemas found in ${schemaDir.absolutePath}", versions.isNotEmpty())
        assertEquals(DatabaseMigrations.FIRST_EXPORTED_VERSION, versions.first())
        assertEquals(
            "A schema version is missing between ${versions.first()} and ${versions.last()}",
            (versions.first()..versions.last()).toList(),
            versions
        )
    }

    @Test
    fun migrationsChainFromTheFirstExportedVersionToTheCurrentOne() {
        val current = exportedVersions().last()
        val steps = DatabaseMigrations.ALL.map { it.startVersion to it.endVersion }.sortedBy { it.first }
        val expected = (DatabaseMigrations.FIRST_EXPORTED_VERSION until current).map { it to it + 1 }
        assertEquals("Each version bump needs a Migration(n, n + 1) in DatabaseMigrations.ALL", expected, steps)
    }

    @Test
    fun onlyPreExportVersionsMayBeRebuiltFromScratch() {
        assertTrue(DatabaseMigrations.LEGACY_VERSIONS.all { it < DatabaseMigrations.FIRST_EXPORTED_VERSION })
    }
}
