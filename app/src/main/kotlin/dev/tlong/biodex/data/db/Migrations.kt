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
 * D34's range maps: three columns on `regions` for the shared map outline and its grid, and
 * one on `species` for the cells that species shades.
 *
 * Every one is an `ADD COLUMN`, which SQLite does support — so unlike [MIGRATION_1_2] there
 * is no table recreate here and no chance of a hand-typed `CREATE TABLE` drifting from
 * Room's. The two rules that matter are that a `NOT NULL` column added to a table that has
 * rows needs a `DEFAULT`, and that the default must match the `@ColumnInfo(defaultValue = …)`
 * on the entity exactly — otherwise Room's identity check fails at the user's next launch
 * rather than here.
 *
 * No row is touched. An existing install lands on this schema with no outline and no cells,
 * which every screen reads as "no map"; the catalogue reconciler then fills both in from the
 * asset, which is what the `catalogueVersion` bump to 4 is for.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `regions` ADD COLUMN `landMask` TEXT")
        db.execSQL("ALTER TABLE `regions` ADD COLUMN `rangeGridWidth` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `regions` ADD COLUMN `rangeGridHeight` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `species` ADD COLUMN `rangeCells` TEXT NOT NULL DEFAULT '[]'")
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

/**
 * D36's lineage: five nullable columns on `species` holding the GBIF path — kingdom, phylum,
 * class, order, family — that Nearest Five measures hops over.
 *
 * All five are `ADD COLUMN` and all five are nullable, so unlike [MIGRATION_2_3] there is
 * not even a `DEFAULT` to keep in step with the entity: SQLite fills an added nullable
 * column with NULL and Room's `@ColumnInfo` carries no default to disagree with. No row is
 * touched.
 *
 * An existing install lands here with every lineage null, which every screen reads as "not
 * classified", and the catalogue reconciler then fills the curated species in from the asset
 * — which is what the `catalogueVersion` bump to 5 is for. A species the user added
 * themselves stays null until its own backfill, and shows no neighbours rather than a wrong
 * distance.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `species` ADD COLUMN `lineageKingdom` TEXT")
        db.execSQL("ALTER TABLE `species` ADD COLUMN `lineagePhylum` TEXT")
        db.execSQL("ALTER TABLE `species` ADD COLUMN `lineageClass` TEXT")
        db.execSQL("ALTER TABLE `species` ADD COLUMN `lineageOrder` TEXT")
        db.execSQL("ALTER TABLE `species` ADD COLUMN `lineageFamily` TEXT")
    }
}

/**
 * M46's picture preference: one `NOT NULL DEFAULT 0` column on `entries`, the same shape as
 * [MIGRATION_2_3]'s counters, with the same rule — the `DEFAULT` here and the
 * `@ColumnInfo(defaultValue = "0")` on [EntryEntity] must agree to the character, or Room's
 * identity check fails at the user's next launch. `MigrationSchemaTest` compares the two.
 *
 * No row is touched. Every caught species lands preferring the reference picture, which is
 * what the grid has drawn since D39.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `entries` ADD COLUMN `preferOwnPhoto` INTEGER NOT NULL DEFAULT 0")
    }
}
