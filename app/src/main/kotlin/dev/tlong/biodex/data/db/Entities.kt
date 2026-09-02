package dev.tlong.biodex.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
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
    /** Curated: 1–120. User-added: 1001, 1002, … (see `USER_DEX_NUMBER_BASE`). */
    val dexNumber: Int,
    val source: SpeciesSource,
    /** User-added only (M20): a lookup is still owed for this row. */
    val detailsPending: Boolean = false,
    val commonName: String,
    val scientificName: String? = null,
    val taxClass: TaxClass,
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
