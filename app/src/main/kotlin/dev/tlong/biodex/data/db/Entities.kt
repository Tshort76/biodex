package dev.tlong.biodex.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.tlong.biodex.domain.Kingdom
import dev.tlong.biodex.domain.SpeciesSource
import dev.tlong.biodex.domain.TaxClass

/**
 * The complete Room schema from ARCHITECTURE.md 3.1 — every table, including ones no
 * screen uses yet. Slice 3 creates these once; per section 9 they are not edited again
 * until a real post-v1 migration, which is what keeps slices 4–8 from colliding.
 */

@Entity(
    tableName = "species",
    indices = [
        Index(value = ["regionId", "dexNumber"], unique = true),
        Index(value = ["commonName"]),
        Index(value = ["scientificName"]),
    ],
)
data class SpeciesEntity(
    @PrimaryKey val id: String,
    val regionId: String,
    /**
     * Curated animals 1–120, curated plants 2001–2080, user-added 9001 upward — see
     * `storedDexNumber`, `PLANT_DEX_NUMBER_BASE` and `USER_DEX_NUMBER_BASE`. One sortable
     * column orders the whole grid, and `(regionId, dexNumber)` is unique, which is exactly
     * why the plants need a stored range of their own.
     */
    val dexNumber: Int,
    val source: SpeciesSource,
    /** User-added only (M20): a lookup is still owed for this row. */
    val detailsPending: Boolean = false,
    val commonName: String,
    val scientificName: String? = null,
    val taxClass: TaxClass,
    /** Never null. A details-pending user-added species is `animal` until a backfill (11.1). */
    val kingdom: Kingdom = Kingdom.ANIMAL,
    val habitatText: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val callUrl: String? = null,
    val infoUrl: String? = null,
    val imageAttribution: String? = null,
    val callAttribution: String? = null,
    /** Drawable resource name, e.g. `sil_bird`; resolved with `getIdentifier` once. */
    val silhouetteRes: String,
    /** M21: field names the user hand-edited; always empty for curated species in v1. */
    val userEditedFields: List<String> = emptyList(),

    // The uses block (D14/D15). Kept together and every column defaulted, because the
    // medicinal half is sourced data whose shape follows Dr. Duke's rather than this app:
    // adding one more nullable column here later costs a field and a default, nothing more.

    /** JSON array of `edible` | `medicinal`. Empty for every animal. */
    val uses: List<String> = emptyList(),
    /** The curated part-and-season note with any `Caution:` sentence; null unless `uses` is non-empty. */
    val usesNote: String? = null,
    /** Up to eight Duke's activity names, most-cited first; empty when Duke's has nothing. */
    val medicinalActivities: List<String> = emptyList(),
    /** Duke's record count; 0 for animals and for plants with no record. */
    val medicinalRecordCount: Int = 0,
    /** The Duke's credit line, non-null exactly when the two columns above are populated. */
    val usesAttribution: String? = null,
)

/**
 * The regions the catalogue knows, seeded by the importer from the asset's `regionId` and
 * `regionName`. v1 ships exactly one row (`pacific` / "Pacific USA"); the table exists so
 * the header reads a name rather than title-casing an id, which is what the `regionLabelFor`
 * shim used to do (ARCHITECTURE.md 6.5, 11.1).
 */
@Entity(tableName = "regions")
data class RegionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sortOrder: Int = 0,
)

@Entity(tableName = "ecosystems")
data class EcosystemEntity(
    @PrimaryKey val id: String,
    val regionId: String,
    val name: String,
    val sortOrder: Int,
)

@Entity(
    tableName = "species_ecosystems",
    primaryKeys = ["speciesId", "ecosystemId"],
    indices = [Index(value = ["ecosystemId"])],
    foreignKeys = [
        ForeignKey(
            entity = SpeciesEntity::class,
            parentColumns = ["id"],
            childColumns = ["speciesId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = EcosystemEntity::class,
            parentColumns = ["id"],
            childColumns = ["ecosystemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SpeciesEcosystemCrossRef(
    val speciesId: String,
    val ecosystemId: String,
)

/**
 * Zero or one per species. `captureCount` is deliberately not a column — it is a
 * `COUNT(*)` in the DAO, because a stored copy would drift (ARCHITECTURE.md 3.1).
 *
 * `favoriteCaptureId` carries no foreign key on purpose: captures reference species and
 * entries reference captures, and declaring both makes an insert-order cycle. Whoever
 * deletes a capture must null this column itself (slice 5).
 */
@Entity(
    tableName = "entries",
    foreignKeys = [
        ForeignKey(
            entity = SpeciesEntity::class,
            parentColumns = ["id"],
            childColumns = ["speciesId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class EntryEntity(
    @PrimaryKey val speciesId: String,
    val caughtAt: Long,
    val favoriteCaptureId: String? = null,
)

@Entity(
    tableName = "captures",
    indices = [Index(value = ["speciesId"])],
    foreignKeys = [
        ForeignKey(
            entity = SpeciesEntity::class,
            parentColumns = ["id"],
            childColumns = ["speciesId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class CaptureEntity(
    @PrimaryKey val id: String,
    val speciesId: String,
    /** The persisted gallery content URI, stored as `uri.toString()`. */
    val photoUri: String,
    /** Relative to `filesDir`, e.g. `thumbnails/<id>.jpg` — relative so a restore resolves. */
    val thumbPath: String,
    /** Relative to `filesDir`, set only when "keep a local copy" (S03) is on. */
    val localCopyPath: String? = null,
    /** Epoch millis: EXIF `DateTimeOriginal`, else registration time. */
    val takenAt: Long,
    val lat: Double? = null,
    val lng: Double? = null,
    val locationLabel: String? = null,
    val note: String? = null,
    /** Registration time. `isFirst` is derived (`MIN(createdAt)` per species), not stored. */
    val createdAt: Long,
)

/** Single-row key/value table; the importer's version handshake lives here. */
@Entity(tableName = "meta")
data class MetaEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "value") val value: String,
) {
    companion object {
        const val KEY_CATALOGUE_VERSION = "catalogueVersion"
        const val KEY_SCHEMA_SEEDED_AT = "schemaSeededAt"
    }
}
