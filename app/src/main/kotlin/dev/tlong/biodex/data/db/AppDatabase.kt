package dev.tlong.biodex.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The whole schema, created once in slice 3 (ARCHITECTURE.md section 9).
 *
 * **No `fallbackToDestructiveMigration`, ever.** The user's life list must never be wiped
 * by an upgrade (3.1). While the app is pre-first-install the schema may be bumped freely
 * by uninstalling; from the first real capture on the phone every change ships a
 * hand-written `Migration`, writable because `exportSchema = true` keeps the schema JSON
 * in `app/schemas/` under git.
 *
 * **v2 is the first time that rule has been exercised** (R21). It relaxes the two photo
 * columns on `captures` so a plant can be caught without a photograph of the user's own
 * (M41); see [MIGRATION_1_2], which changes the columns and not one row.
 */
@Database(
    entities = [
        SpeciesEntity::class,
        EcosystemEntity::class,
        SpeciesEcosystemCrossRef::class,
        EntryEntity::class,
        CaptureEntity::class,
        MetaEntity::class,
        RegionEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun speciesDao(): SpeciesDao
    abstract fun ecosystemDao(): EcosystemDao
    abstract fun entryDao(): EntryDao
    abstract fun captureDao(): CaptureDao
    abstract fun metaDao(): MetaDao
    abstract fun regionDao(): RegionDao

    companion object {
        const val NAME = "biodex.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
