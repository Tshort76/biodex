package dev.tlong.animaldex.data.db

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
 * the capture count and the thumbnail of the favorite capture (falling back to the first,
 * per S04).
 */
data class EntryStatusRow(
    val speciesId: String,
    val caughtAt: Long,
    val captureCount: Int,
    val thumbPath: String?,
)

@Dao
interface SpeciesDao {

    @Query("SELECT * FROM species WHERE regionId = :regionId ORDER BY dexNumber ASC")
    fun observeSpecies(regionId: String): Flow<List<SpeciesEntity>>

    @Query("SELECT * FROM species WHERE id = :speciesId")
    fun observeSpeciesById(speciesId: String): Flow<SpeciesEntity?>

    @Query("SELECT * FROM species WHERE regionId = :regionId ORDER BY dexNumber ASC")
    suspend fun speciesOnce(regionId: String): List<SpeciesEntity>

    /** Slice 7's backfill reads the stored row — including `userEditedFields` — before merging. */
    @Query("SELECT * FROM species WHERE id = :speciesId")
    suspend fun speciesOnceById(speciesId: String): SpeciesEntity?

    @Query("SELECT COUNT(*) FROM species WHERE regionId = :regionId AND source = 'curated'")
    fun observeCuratedCount(regionId: String): Flow<Int>

    /** Slice 7 allocates the next U-number from this. Null when no user species exist yet. */
    @Query("SELECT MAX(dexNumber) FROM species WHERE regionId = :regionId AND source = 'user'")
    suspend fun maxUserDexNumber(regionId: String): Int?

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

    @Upsert
    suspend fun upsertAll(ecosystems: List<EcosystemEntity>)

    @Query(
        """
        SELECT x.* FROM species_ecosystems x
        INNER JOIN species s ON s.id = x.speciesId
        WHERE s.regionId = :regionId
        """,
    )
    fun observeMemberships(regionId: String): Flow<List<SpeciesEcosystemCrossRef>>

    @Query(
        """
        SELECT x.* FROM species_ecosystems x
        INNER JOIN species s ON s.id = x.speciesId
        WHERE s.regionId = :regionId
        """,
    )
    suspend fun membershipsOnce(regionId: String): List<SpeciesEcosystemCrossRef>

    @Upsert
    suspend fun upsertMemberships(rows: List<SpeciesEcosystemCrossRef>)

    @Query("DELETE FROM species_ecosystems WHERE speciesId IN (:speciesIds)")
    suspend fun deleteMembershipsFor(speciesIds: List<String>)
}

@Dao
interface EntryDao {

    @Query(
        """
        SELECT e.speciesId AS speciesId,
               e.caughtAt AS caughtAt,
               (SELECT COUNT(*) FROM captures c WHERE c.speciesId = e.speciesId) AS captureCount,
               COALESCE(
                   (SELECT cf.thumbPath FROM captures cf
                      WHERE cf.id = e.favoriteCaptureId AND cf.speciesId = e.speciesId),
                   (SELECT c2.thumbPath FROM captures c2
                      WHERE c2.speciesId = e.speciesId
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

    @Query("UPDATE entries SET favoriteCaptureId = :captureId WHERE speciesId = :speciesId")
    suspend fun setFavoriteCapture(speciesId: String, captureId: String?)
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

    /** S08's "recently caught" strip. */
    @Query("SELECT * FROM captures ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<CaptureEntity>>

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
interface MetaDao {

    @Query("SELECT value FROM meta WHERE `key` = :key")
    suspend fun value(key: String): String?

    @Upsert
    suspend fun put(meta: MetaEntity)
}
