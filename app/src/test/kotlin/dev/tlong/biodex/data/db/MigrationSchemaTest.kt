package dev.tlong.biodex.data.db

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R21's cheap half. The migration itself needs `MigrationTestHelper`, which is an instrumented
 * test and needs a phone — **`MIGRATION_1_2` has never been run**. What can be checked here is
 * the failure that actually bites: the hand-written `CREATE TABLE` drifting from what Room
 * expects for version 2, which Room notices as an identity mismatch at the user's *next
 * launch* rather than at build time.
 *
 * It reads the exported schema JSON directly, which is why `exportSchema = true` and the
 * checked-in `app/schemas/` matter beyond documentation.
 */
class MigrationSchemaTest {

    private val capturesV2 by lazy {
        val file = File("schemas/dev.tlong.biodex.data.db.AppDatabase/2.json")
        assertTrue("schema v2 has not been exported — run assembleDebug", file.exists())
        Json.parseToJsonElement(file.readText())
            .jsonObject.getValue("database")
            .jsonObject.getValue("entities")
            .jsonArray
            .map { it.jsonObject }
            .single { it.getValue("tableName").jsonPrimitive.content == "captures" }
    }

    @Test
    fun `the migration's captures table matches Room's exported v2 schema`() {
        // A stray column, a changed type or a different constraint order all show up here
        // rather than on the user's phone.
        assertEquals(
            capturesV2.getValue("createSql").jsonPrimitive.content,
            CAPTURES_V2_CREATE_SQL,
        )
    }

    @Test
    fun `v2 relaxes exactly the two photo columns and nothing else`() {
        val nullable = capturesV2.getValue("fields").jsonArray
            .map { it.jsonObject }
            // Room omits `notNull` rather than writing false, so absent means nullable.
            .filterNot { it["notNull"]?.jsonPrimitive?.content?.toBoolean() == true }
            .map { it.getValue("fieldPath").jsonPrimitive.content }
            .toSet()

        assertEquals(
            setOf("photoUri", "thumbPath", "localCopyPath", "lat", "lng", "locationLabel", "note"),
            nullable,
        )
    }

    /**
     * D34's columns. The failure this catches is the same one, one version later: an
     * `ADD COLUMN` whose `DEFAULT` does not match the `@ColumnInfo(defaultValue = …)` Room
     * exported, which passes every test here and then fails Room's identity check on the
     * user's phone at the next launch.
     */
    @Test
    fun `the range columns added by MIGRATION_2_3 match Room's exported v3 schema`() {
        val v3 = File("schemas/dev.tlong.biodex.data.db.AppDatabase/3.json")
        assertTrue("schema v3 has not been exported — run assembleDebug", v3.exists())
        val entities = Json.parseToJsonElement(v3.readText())
            .jsonObject.getValue("database")
            .jsonObject.getValue("entities")
            .jsonArray
            .map { it.jsonObject }

        fun field(table: String, name: String) = entities
            .single { it.getValue("tableName").jsonPrimitive.content == table }
            .getValue("fields").jsonArray
            .map { it.jsonObject }
            .single { it.getValue("fieldPath").jsonPrimitive.content == name }

        fun defaultOf(table: String, name: String) =
            field(table, name)["defaultValue"]?.jsonPrimitive?.content

        fun notNull(table: String, name: String) =
            field(table, name)["notNull"]?.jsonPrimitive?.content?.toBoolean() == true

        // The exact strings the migration writes.
        assertEquals("'[]'", defaultOf("species", "rangeCells"))
        assertEquals("0", defaultOf("regions", "rangeGridWidth"))
        assertEquals("0", defaultOf("regions", "rangeGridHeight"))
        assertTrue(notNull("species", "rangeCells"))
        assertTrue(notNull("regions", "rangeGridWidth"))

        // The outline is nullable, which is what lets an install that predates the maps sit
        // on this schema with no map rather than an empty one.
        assertTrue(!notNull("regions", "landMask"))
        assertEquals(null, defaultOf("regions", "landMask"))
    }

    @Test
    fun `the speciesId index survives the table recreate`() {
        // Dropping the table drops its indices, so the migration recreates this one by hand.
        // A missing index is invisible until the detail screen gets slow years later.
        val indices = capturesV2.getValue("indices").jsonArray
            .map { it.jsonObject.getValue("name").jsonPrimitive.content }

        assertEquals(listOf("index_captures_speciesId"), indices)
    }
}
