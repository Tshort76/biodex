package dev.tlong.biodex.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Every DAO read is a `Flow`, every write is `suspend` (ARCHITECTURE.md 3.1).
 *
 * The grid's cell state is assembled from three small flows rather than one wide join:
 * species rows, ecosystem membership rows, and one [EntryStatusRow] per caught species.
 * At 120-plus rows the combine is free, and it keeps the assembly a pure function the JVM
 * tests can reach — the same reasoning section 6.2 uses for search and filters.
 */

/**
 * Everything the grid needs to know about a caught species, derived rather than stored:
 * the capture count and the thumbnail of the favorite capture (falling back to the earliest
 * capture that still has one, per S04 — a photoless sighting, D61, is skipped, so unlinking
 * the favourite's photo does not blank a tile another photo could fill).
 */
data class EntryStatusRow(
    val speciesId: String,
    val caughtAt: Long,
    val captureCount: Int,
    val thumbPath: String?,
    /** M46. */
    val preferOwnPhoto: Boolean = false,
)


/**
 * D75. An entry's caught date is the date its earliest photo was taken — the capture's EXIF
 * `takenAt`, which falls back to registration time. Applied after every write that adds or
 * removes a capture, and once to the whole table by [MIGRATION_6_7]. An entry with no capture
 * left is deleted by the same writes, so the `EXISTS` only guards a row mid-transaction.
 */
const val SYNC_CAUGHT_AT_SQL =
    "UPDATE entries SET caughtAt = " +
        "(SELECT MIN(c.takenAt) FROM captures c WHERE c.speciesId = entries.speciesId) " +
        "WHERE EXISTS (SELECT 1 FROM captures c WHERE c.speciesId = entries.speciesId)"

@Dao
interface SpeciesDao {

    /** D95: every species; a region's dex is a filter over this through `species_regions`. */
    @Query("SELECT * FROM species ORDER BY regionId, dexNumber ASC")
    fun observeAllSpecies(): Flow<List<SpeciesEntity>>

    /** The species a region's dex lists (D95), whichever region is their home. */
    @Query(
        """
        SELECT s.* FROM species s INNER JOIN species_regions r ON r.speciesId = s.id
        WHERE r.regionId = :regionId ORDER BY r.dexNumber ASC
        """,
    )
    suspend fun speciesOnce(regionId: String): List<SpeciesEntity>

    @Query("SELECT * FROM species WHERE id = :speciesId")
    fun observeSpeciesById(speciesId: String): Flow<SpeciesEntity?>

    /** D94: a backup carries every region's species, not only the one on screen. */
    @Query("SELECT * FROM species ORDER BY regionId, dexNumber ASC")
    suspend fun allSpeciesOnce(): List<SpeciesEntity>

    /** Slice 7's backfill reads the stored row — including `userEditedFields` — before merging. */
    @Query("SELECT * FROM species WHERE id = :speciesId")
    suspend fun speciesOnceById(speciesId: String): SpeciesEntity?

    /**
     * Slice 7 allocates the next U-number from this. Null when no user species exist yet.
     * Across every region (D95), so the All view never shows two species under one U-number.
     */
    @Query("SELECT MAX(dexNumber) FROM species WHERE source = 'user'")
    suspend fun maxUserDexNumber(): Int?

    /** D45's sweep reads only the rows the user added; curated spelling is the catalogue's. */
    @Query("SELECT * FROM species WHERE source = 'user'")
    suspend fun userSpeciesOnce(): List<SpeciesEntity>

    /** D45: names only — every other column, `userEditedFields` included, is left alone. */
    @Query("UPDATE species SET commonName = :commonName, scientificName = :scientificName WHERE id = :id")
    suspend fun rename(id: String, commonName: String, scientificName: String?)

    @Upsert
    suspend fun upsertAll(species: List<SpeciesEntity>)

    @Upsert
    suspend fun upsert(species: SpeciesEntity)

    /** Cascades to `species_ecosystems`, `entries` and `captures`. */
    @Query("DELETE FROM species WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}

@Dao
interface EcosystemDao {

    @Query("SELECT * FROM ecosystems WHERE regionId = :regionId ORDER BY sortOrder ASC")
    fun observeEcosystems(regionId: String): Flow<List<EcosystemEntity>>

    @Query("SELECT * FROM ecosystems WHERE regionId = :regionId ORDER BY sortOrder ASC")
    suspend fun ecosystemsOnce(regionId: String): List<EcosystemEntity>

    /** D95's All view: every region's ecosystems, region by region. */
    @Query(
        """
        SELECT e.* FROM ecosystems e LEFT JOIN regions r ON r.id = e.regionId
        ORDER BY r.sortOrder ASC, e.sortOrder ASC
        """,
    )
    fun observeAllEcosystems(): Flow<List<EcosystemEntity>>

    @Query("SELECT * FROM ecosystems")
    suspend fun allEcosystemsOnce(): List<EcosystemEntity>

    @Query("SELECT * FROM species_ecosystems")
    suspend fun allMembershipsOnce(): List<SpeciesEcosystemCrossRef>

    @Upsert
    suspend fun upsertAll(ecosystems: List<EcosystemEntity>)

    /** Every region's links: a shared species carries ecosystems from each dex that lists it. */
    /** One region's links: those to its own ecosystems. */
    @Query(
        """
        SELECT x.* FROM species_ecosystems x
        INNER JOIN ecosystems e ON e.id = x.ecosystemId
        WHERE e.regionId = :regionId
        """,
    )
    suspend fun membershipsOnce(regionId: String): List<SpeciesEcosystemCrossRef>

    @Query("SELECT * FROM species_ecosystems")
    fun observeAllMemberships(): Flow<List<SpeciesEcosystemCrossRef>>

    @Upsert
    suspend fun upsertMemberships(rows: List<SpeciesEcosystemCrossRef>)

    @Query("DELETE FROM species_ecosystems WHERE speciesId IN (:speciesIds)")
    suspend fun deleteMembershipsFor(speciesIds: List<String>)

    /** D95: a region's import rewrites its own ecosystems' links and leaves another region's. */
    @Query(
        """
        DELETE FROM species_ecosystems WHERE speciesId IN (:speciesIds)
        AND ecosystemId IN (SELECT id FROM ecosystems WHERE regionId = :regionId)
        """,
    )
    suspend fun deleteRegionMembershipsFor(speciesIds: List<String>, regionId: String)
}

@Dao
interface EntryDao {

    @Query(
        """
        SELECT e.speciesId AS speciesId,
               e.caughtAt AS caughtAt,
               e.preferOwnPhoto AS preferOwnPhoto,
               (SELECT COUNT(*) FROM captures c WHERE c.speciesId = e.speciesId) AS captureCount,
               COALESCE(
                   (SELECT cf.thumbPath FROM captures cf
                      WHERE cf.id = e.favoriteCaptureId AND cf.speciesId = e.speciesId),
                   (SELECT c2.thumbPath FROM captures c2
                      WHERE c2.speciesId = e.speciesId AND c2.thumbPath IS NOT NULL
                      ORDER BY c2.createdAt ASC
                      LIMIT 1)
               ) AS thumbPath
        FROM entries e
        """,
    )
    fun observeEntryStatuses(): Flow<List<EntryStatusRow>>

    @Query("SELECT * FROM entries WHERE speciesId = :speciesId")
    fun observeEntry(speciesId: String): Flow<EntryEntity?>

    @Query("SELECT * FROM entries WHERE speciesId = :speciesId")
    suspend fun entryOnce(speciesId: String): EntryEntity?

    /** What the importer needs: which species must never be deleted (ARCHITECTURE.md 3.3). */
    @Query("SELECT speciesId FROM entries")
    suspend fun speciesIdsWithEntries(): List<String>

    /** S01's export reads the whole life list in one pass. */
    @Query("SELECT * FROM entries")
    suspend fun entriesOnce(): List<EntryEntity>

    @Upsert
    suspend fun upsert(entry: EntryEntity)

    @Query("DELETE FROM entries WHERE speciesId = :speciesId")
    suspend fun deleteBySpeciesId(speciesId: String)

    /** D75: this species' caught date follows its earliest photo. */
    @Query("$SYNC_CAUGHT_AT_SQL AND speciesId = :speciesId")
    suspend fun syncCaughtAt(speciesId: String)

    /** D75, for every entry at once — after an import. */
    @Query(SYNC_CAUGHT_AT_SQL)
    suspend fun syncAllCaughtAt()

    @Query("UPDATE entries SET favoriteCaptureId = :captureId WHERE speciesId = :speciesId")
    suspend fun setFavoriteCapture(speciesId: String, captureId: String?)

    /** M46: which picture leads for this species. */
    @Query("UPDATE entries SET preferOwnPhoto = :preferOwnPhoto WHERE speciesId = :speciesId")
    suspend fun setPreferOwnPhoto(speciesId: String, preferOwnPhoto: Boolean)
}

@Dao
interface CaptureDao {

    @Query("SELECT * FROM captures WHERE speciesId = :speciesId ORDER BY createdAt DESC")
    fun observeCaptures(speciesId: String): Flow<List<CaptureEntity>>

    @Query("SELECT * FROM captures WHERE id = :captureId")
    fun observeCapture(captureId: String): Flow<CaptureEntity?>

    @Query("SELECT * FROM captures WHERE id = :captureId")
    suspend fun captureOnce(captureId: String): CaptureEntity?

    @Query("SELECT COUNT(*) FROM captures WHERE speciesId = :speciesId")
    suspend fun countForSpecies(speciesId: String): Int

    /**
     * The shared-grant check (slice 5). The same gallery photo can be registered against two
     * species; releasing its persistable grant when one of them is deleted would break the
     * other's reference too, so release is conditional on this count.
     */
    @Query("SELECT COUNT(*) FROM captures WHERE photoUri = :photoUri")
    suspend fun countForUri(photoUri: String): Int

    /** Re-link (ARCHITECTURE.md 4.2): a new reference and thumbnail under the same capture id. */
    @Query("UPDATE captures SET photoUri = :photoUri, thumbPath = :thumbPath WHERE id = :captureId")
    suspend fun updateReference(captureId: String, photoUri: String, thumbPath: String)

    /** D67. `COALESCE` keeps a label the user typed; a null [label] changes nothing there. */
    @Query(
        "UPDATE captures SET lat = :lat, lng = :lng, " +
            "locationLabel = COALESCE(locationLabel, :label) WHERE id = :captureId",
    )
    suspend fun fillPlace(captureId: String, lat: Double, lng: Double, label: String?)

    /** D89: every sighting of one species becomes a sighting of another. */
    @Query("UPDATE captures SET speciesId = :toSpeciesId WHERE speciesId = :fromSpeciesId")
    suspend fun moveToSpecies(fromSpeciesId: String, toSpeciesId: String)

    /** D61: the photograph goes, every other column of the sighting stays. */
    @Query(
        "UPDATE captures SET photoUri = NULL, thumbPath = NULL, localCopyPath = NULL " +
            "WHERE id = :captureId",
    )
    suspend fun clearReference(captureId: String)

    /** S08's "recently caught" strip. */
    @Query("SELECT * FROM captures ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<CaptureEntity>>

    /**
     * D68. The places this collection already uses, most recently used first — the first tier
     * of the place prompt's suggestions, and the only one that can hold a trail or a back
     * garden the bundled gazetteer has never heard of.
     */
    @Query(
        "SELECT locationLabel FROM captures WHERE locationLabel IS NOT NULL " +
            "GROUP BY locationLabel ORDER BY MAX(createdAt) DESC LIMIT :limit",
    )
    fun observePlaceLabels(limit: Int): Flow<List<String>>

    /** S01's export, oldest first so the archive reads in the order the catches happened. */
    @Query("SELECT * FROM captures ORDER BY createdAt ASC")
    suspend fun capturesOnce(): List<CaptureEntity>

    @Query("SELECT id FROM captures")
    suspend fun captureIdsOnce(): List<String>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(capture: CaptureEntity)

    @Upsert
    suspend fun upsert(capture: CaptureEntity)

    @Delete
    suspend fun delete(capture: CaptureEntity)

    @Query("DELETE FROM captures WHERE id = :captureId")
    suspend fun deleteById(captureId: String)
}

@Dao
interface RegionDao {

    @Query("SELECT * FROM regions WHERE id = :regionId")
    fun observeRegion(regionId: String): Flow<RegionEntity?>

    @Query("SELECT * FROM regions ORDER BY sortOrder ASC")
    suspend fun regionsOnce(): List<RegionEntity>

    @Query("SELECT * FROM regions ORDER BY sortOrder ASC")
    fun observeRegions(): Flow<List<RegionEntity>>

    @Upsert
    suspend fun upsertAll(regions: List<RegionEntity>)
}

@Dao
interface MetaDao {

    @Query("SELECT value FROM meta WHERE `key` = :key")
    suspend fun value(key: String): String?

    @Upsert
    suspend fun put(meta: MetaEntity)
}

/** D95: which dexes list each species. */
@Dao
interface SpeciesRegionDao {

    @Query("SELECT * FROM species_regions")
    fun observeAll(): Flow<List<SpeciesRegionEntity>>

    @Query("SELECT * FROM species_regions")
    suspend fun allOnce(): List<SpeciesRegionEntity>

    @Query("DELETE FROM species_regions WHERE regionId = :regionId")
    suspend fun deleteRegion(regionId: String)

    @Upsert
    suspend fun upsertAll(rows: List<SpeciesRegionEntity>)

    /** A user species' listing: written once, and never moved to a curated number. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(row: SpeciesRegionEntity)
}
