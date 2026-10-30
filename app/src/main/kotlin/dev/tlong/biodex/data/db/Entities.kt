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
        // Not unique since D96: a renamed species takes its old row's number before the old row
        // is merged away, in the same import.
        Index(value = ["regionId", "dexNumber"]),
        Index(value = ["commonName"]),
        Index(value = ["scientificName"]),
    ],
)
data class SpeciesEntity(
    @PrimaryKey val id: String,
    val regionId: String,
    /**
     * Curated animals 1–224, curated fungi 4001–4030, user-added 9001 upward — see
     * `storedDexNumber`, `FUNGUS_DEX_NUMBER_BASE` and `USER_DEX_NUMBER_BASE`. One sortable
     * column orders the whole grid, which is why each kingdom needs a stored range of its own.
     * The home region's number (D95); a dex reads its own from `species_regions`. (2001–2080
     * held the plants, D59.)
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
    val infoUrl: String? = null,
    val imageAttribution: String? = null,
    /** Drawable resource name, e.g. `sil_bird`; resolved with `getIdentifier` once. */
    val silhouetteRes: String,
    /** M21: field names the user hand-edited; always empty for curated species in v1. */
    val userEditedFields: List<String> = emptyList(),

    // The uses block (D14/D15, D48).

    /** JSON array; `edible` is the only value written since D59. Empty for every fungus. */
    val uses: List<String> = emptyList(),
    /** A note with any `Caution:` sentence; null unless `uses` is non-empty or it is a caution. */
    val usesNote: String? = null,
    /**
     * Retired with the plants (D59): the three Dr. Duke's columns. Every row holds the
     * default. They stay on the table because SQLite has no `DROP COLUMN` at minSdk and a
     * recreate of the species table, with its four dependents, buys nothing a default does
     * not; nothing reads or writes them any more.
     */
    val medicinalActivities: List<String> = emptyList(),
    val medicinalRecordCount: Int = 0,
    val usesAttribution: String? = null,

    /**
     * D34: the cells to shade on this species' range map, indexed over the region's grid
     * (`row * rangeGridWidth + column`). Empty is a real answer, not a missing one — GBIF
     * holds no records for every species, and a user-added species has none until a backfill.
     */
    @ColumnInfo(defaultValue = "[]") val rangeCells: List<Int> = emptyList(),

    /**
     * D36: the GBIF path this species sits on, one column per rank, for the hop count on
     * Nearest Five. Nullable rather than defaulted because a null here is a real answer with
     * two distinct causes — GBIF fills no value at that rank (no ray-finned fish has a
     * class), or the species is user-added and awaiting a backfill — and the distance
     * measure treats those two the same way: it never invents a shared ancestor.
     *
     * Deliberately not named `kingdom` or `taxClass`. Those columns already exist and mean
     * the app's own enums; these are Linnaean rank names.
     */
    val lineageKingdom: String? = null,
    val lineagePhylum: String? = null,
    val lineageClass: String? = null,
    val lineageOrder: String? = null,
    val lineageFamily: String? = null,
)

/**
 * D95: which regional dexes list a species, and under what number. A species is one row
 * whichever dexes carry it, so a catch counts in every region; a region is a filter over this
 * table. `species.regionId` and `species.dexNumber` remain the *home* region (the one whose
 * catalogue writes the row's text) and its number there. Added by [MIGRATION_8_9].
 */
@Entity(
    tableName = "species_regions",
    primaryKeys = ["speciesId", "regionId"],
    indices = [Index(value = ["regionId"])],
    foreignKeys = [
        ForeignKey(
            entity = SpeciesEntity::class,
            parentColumns = ["id"],
            childColumns = ["speciesId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SpeciesRegionEntity(
    val speciesId: String,
    val regionId: String,
    /** Stored form, kingdom base included — the number this species carries in that dex. */
    val dexNumber: Int,
)

/**
 * D103: a web page the owner chose for a species, shown in place of the catalogue's link. A
 * table of its own because a catalogue import rewrites every curated `species` row whole, and
 * nothing here is the catalogue's to write. Added by [MIGRATION_10_11].
 */
@Entity(
    tableName = "species_links",
    foreignKeys = [
        ForeignKey(
            entity = SpeciesEntity::class,
            parentColumns = ["id"],
            childColumns = ["speciesId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SpeciesLinkEntity(
    @PrimaryKey val speciesId: String,
    val url: String,
)

/**
 * The regions the catalogue knows, seeded by the importer from the asset's `regionId` and
 * `regionName` — one row per bundled asset (`pacific` / "Pacific USA", `argentina` /
 * "Argentina", D94), ordered by [sortOrder] in the region picker. The table exists so the
 * header reads a name rather than title-casing an id (ARCHITECTURE.md 6.5, 11.1).
 */
@Entity(tableName = "regions")
data class RegionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sortOrder: Int = 0,
    /**
     * D34's shared map outline: one bit per grid cell, land set, base64. Region-level
     * because every species' map is the same world; only the shading differs. Null on a
     * database imported before range maps existed, and the detail screen draws no map then.
     */
    val landMask: String? = null,
    @ColumnInfo(defaultValue = "0") val rangeGridWidth: Int = 0,
    @ColumnInfo(defaultValue = "0") val rangeGridHeight: Int = 0,
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
    /**
     * M46: the user's own photograph leads the tile and the hero for this species instead of
     * the reference picture. Lives on the entry rather than a capture because it is a choice
     * about the species, and the capture it shows is still the favourite (S04).
     */
    @ColumnInfo(defaultValue = "0") val preferOwnPhoto: Boolean = false,
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
    /**
     * The persisted gallery content URI, stored as `uri.toString()`.
     *
     * **Null for a photoless sighting** — one whose photo the user unlinked (D61), or, before
     * v21, a plant catch (M41, struck): no reference and no persistable grant. Both columns
     * were `NOT NULL` in schema v1 and were relaxed by `MIGRATION_1_2`, which touched no row.
     */
    val photoUri: String? = null,
    /** Relative to `filesDir`, e.g. `thumbnails/<id>.jpg` — relative so a restore resolves. */
    val thumbPath: String? = null,
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
    /**
     * D80's column, added by [MIGRATION_7_8] and unread since D98. Kept because minSdk 29's
     * SQLite has no DROP COLUMN, and the rebuild would rewrite the table holding every sighting.
     */
    @ColumnInfo(defaultValue = "1") val wild: Boolean = true,
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

        /**
         * D94: each region keeps its own imported version, so bumping one asset re-imports that
         * region alone. The Pacific keeps the bare key every install already holds.
         */
        fun catalogueVersionKey(regionId: String): String =
            if (regionId == "pacific") KEY_CATALOGUE_VERSION else "$KEY_CATALOGUE_VERSION:$regionId"
        // `placeBackfillDone` was written once by v22.5's one-time sweep (D67) and is left in
        // place on the owner's phone; nothing reads it.
    }
}
