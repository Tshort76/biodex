package dev.tlong.biodex.domain

/**
 * The plain models the UI consumes (ARCHITECTURE.md section 2). Nothing here knows
 * about Room, kotlinx-serialization or Android — mapping lives in `data/db/Mappers.kt`
 * and `data/catalogue/`.
 */

/** DESIGN.md §2: bird / mammal / reptile / amphibian / fish / insect / other invertebrate. */
enum class TaxClass(val wireName: String) {
    BIRD("bird"),
    MAMMAL("mammal"),
    REPTILE("reptile"),
    AMPHIBIAN("amphibian"),
    FISH("fish"),
    INSECT("insect"),
    OTHER_INVERTEBRATE("other_invertebrate"),
    ;

    companion object {
        private val byWireName = entries.associateBy { it.wireName }

        /** Unknown or missing values fall back to `other_invertebrate` rather than throwing. */
        fun fromWireName(value: String?): TaxClass =
            byWireName[value?.trim()?.lowercase()] ?: OTHER_INVERTEBRATE
    }
}

enum class SpeciesSource(val wireName: String) {
    CURATED("curated"),
    USER("user"),
    ;

    companion object {
        private val byWireName = entries.associateBy { it.wireName }

        fun fromWireName(value: String?): SpeciesSource =
            byWireName[value?.trim()?.lowercase()] ?: CURATED
    }
}

/**
 * User-added species carry dex numbers from [USER_DEX_NUMBER_BASE] + 1 upward so a single
 * sortable integer column orders the whole grid (ARCHITECTURE.md 3.1). Presentation
 * subtracts the base and renders `U01`.
 */
const val USER_DEX_NUMBER_BASE = 1000

/** `#021` for curated species, `U01` for user-added ones (DESIGN.md §2, M02). */
fun displayDexNumber(dexNumber: Int, source: SpeciesSource): String = when (source) {
    SpeciesSource.CURATED -> "#" + dexNumber.toString().padStart(3, '0')
    SpeciesSource.USER -> "U" + (dexNumber - USER_DEX_NUMBER_BASE).toString().padStart(2, '0')
}

data class Ecosystem(
    val id: String,
    val regionId: String,
    val name: String,
    val sortOrder: Int,
)

/** One capture: a single registration event (DESIGN.md §2). */
data class Capture(
    val id: String,
    val speciesId: String,
    val photoUri: String,
    val thumbPath: String,
    val localCopyPath: String? = null,
    val takenAt: Long,
    val lat: Double? = null,
    val lng: Double? = null,
    val locationLabel: String? = null,
    val note: String? = null,
    val createdAt: Long,
)

/** The user's relationship to a species; exists once the species has a capture. */
data class Entry(
    val speciesId: String,
    val caughtAt: Long,
    val favoriteCaptureId: String? = null,
    val captureCount: Int,
)

/**
 * What the grid needs for one cell (M01/M02): identity, art, and whether it is caught.
 * Deliberately does not carry habitat/description prose — the grid never renders it.
 */
data class SpeciesSummary(
    val id: String,
    val regionId: String,
    val dexNumber: Int,
    val source: SpeciesSource,
    val detailsPending: Boolean,
    val commonName: String,
    val scientificName: String?,
    val taxClass: TaxClass,
    val silhouetteRes: String,
    val ecosystemIds: List<String>,
    val caughtAt: Long?,
    /** Relative path under `filesDir` of the favorite (else first) capture's thumbnail. */
    val thumbPath: String?,
    val captureCount: Int,
) {
    val caught: Boolean get() = caughtAt != null
    val displayNumber: String get() = displayDexNumber(dexNumber, source)
}

/** Everything the detail screen renders about one species (M04/M05). */
data class SpeciesDetail(
    val summary: SpeciesSummary,
    val habitatText: String?,
    val description: String?,
    val imageUrl: String?,
    val callUrl: String?,
    val infoUrl: String?,
    val imageAttribution: String?,
    val callAttribution: String?,
    val userEditedFields: List<String>,
)

/**
 * One meter. [caught] / [total] is the curated fraction; [userAdded] is D9's addendum
 * ("12/24 +1") and is deliberately outside the fraction.
 */
data class Meter(
    val caught: Int,
    val total: Int,
    val userAdded: Int = 0,
) {
    val fraction: Float get() = if (total == 0) 0f else caught.toFloat() / total.toFloat()
}

data class EcosystemProgress(
    val ecosystem: Ecosystem,
    val meter: Meter,
)

/** Derived, never stored (DESIGN.md §2 "Dex"). Shared by the grid header and Stats (6.3). */
data class DexProgress(
    val regionId: String,
    val overall: Meter,
    val perClass: List<Pair<TaxClass, Meter>>,
    val perEcosystem: List<EcosystemProgress>,
) {
    val totalSpecies: Int get() = overall.total
    val caughtCount: Int get() = overall.caught
    val userAddedCount: Int get() = overall.userAdded

    companion object {
        val Empty = DexProgress("", Meter(0, 0, 0), emptyList(), emptyList())
    }
}
