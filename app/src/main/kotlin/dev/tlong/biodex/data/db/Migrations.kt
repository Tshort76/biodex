package dev.tlong.biodex.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The app's **first real migration** (ARCHITECTURE.md 9, R21), and the first time the "no
 * destructive migration, ever" rule is exercised on a database that holds a life list.
 *
 * What it does: relaxes `captures.photoUri` and `captures.thumbPath` from `NOT NULL` to
 * nullable, so a plant can register without a photograph of the user's own (M41).
 *
 * **What it deliberately does not do: touch a single row.** The user's dex already has plant
 * captures with a photo, a thumbnail and a live persistable grant. Nulling them here would be
 * a data loss nobody asked for, and releasing their grants would need the content resolver,
 * which a Room migration must not reach for. So M41 applies to captures made from this release
 * onward and every existing capture is left exactly as it was — the cost is a mixed model,
 * some plant tiles showing the user's own thumbnail and later ones the reference image, which
 * the tile code handles because `thumbPath == null` is already its branch.
 *
 * SQLite cannot `ALTER COLUMN`, so relaxing a constraint is the four-step table recreate below.
 * The `CREATE TABLE` and the index are copied verbatim from Room's own exported `2.json`, which
 * is what makes Room's identity check pass — a hand-typed near-miss is the classic way this
 * fails, and it fails at the user's next launch rather than here.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `captures_new` (" +
                "`id` TEXT NOT NULL, " +
                "`speciesId` TEXT NOT NULL, " +
                "`photoUri` TEXT, " +
                "`thumbPath` TEXT, " +
                "`localCopyPath` TEXT, " +
                "`takenAt` INTEGER NOT NULL, " +
                "`lat` REAL, " +
                "`lng` REAL, " +
                "`locationLabel` TEXT, " +
                "`note` TEXT, " +
                "`createdAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`speciesId`) REFERENCES `species`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "INSERT INTO `captures_new` (" +
                "`id`, `speciesId`, `photoUri`, `thumbPath`, `localCopyPath`, " +
                "`takenAt`, `lat`, `lng`, `locationLabel`, `note`, `createdAt`) " +
                "SELECT `id`, `speciesId`, `photoUri`, `thumbPath`, `localCopyPath`, " +
                "`takenAt`, `lat`, `lng`, `locationLabel`, `note`, `createdAt` FROM `captures`",
        )
        db.execSQL("DROP TABLE `captures`")
        db.execSQL("ALTER TABLE `captures_new` RENAME TO `captures`")
        // Dropped with the old table, so it has to be recreated by hand — and a missing index
        // is invisible until the detail screen gets slow years later.
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_captures_speciesId` ON `captures` (`speciesId`)")
    }
}

/**
 * The `CREATE TABLE` the migration produces, exposed so a JVM test can compare it against
 * Room's exported schema for version 2 without a device.
 *
 * That comparison is the cheap half of R21's mitigation. The migration itself can only be run
 * by `MigrationTestHelper`, which is an instrumented test and needs a phone; the drift that
 * actually bites — a column type or an ordering that no longer matches what Room expects — is
 * catchable off-device by reading `app/schemas/…/2.json` and checking the string.
 */
const val CAPTURES_V2_CREATE_SQL =
    "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`id` TEXT NOT NULL, `speciesId` TEXT NOT NULL, " +
        "`photoUri` TEXT, `thumbPath` TEXT, `localCopyPath` TEXT, `takenAt` INTEGER NOT NULL, " +
        "`lat` REAL, `lng` REAL, `locationLabel` TEXT, `note` TEXT, " +
        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`speciesId`) REFERENCES " +
        "`species`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
