package dev.tlong.biodex.domain

/**
 * The plain models the UI consumes (ARCHITECTURE.md section 2). Nothing here knows
 * about Room, kotlinx-serialization or Android — mapping lives in `data/db/Mappers.kt`
 * and `data/catalogue/`.
 */

/**
 * The two kingdoms BioDex counts separately (DESIGN.md D12). Never null on a species row:
 * a details-pending user-added species is an animal until a backfill says otherwise.
 */
enum class Kingdom(val wireName: String) {
    ANIMAL("animal"),
    PLANT("plant"),
    ;

    companion object {
        private val byWireName = entries.associateBy { it.wireName }

        /** Unknown or missing values fall back to `animal`, the shipped v1 behaviour. */
        fun fromWireName(value: String?): Kingdom =
            byWireName[value?.trim()?.lowercase()] ?: ANIMAL
    }
}

/**
 * DESIGN.md §2: bird / mammal / reptile / amphibian / fish / insect / other invertebrate,
 * plus the four plant growth forms of D12.
 *
 * Every member carries its [kingdom], and `kingdom == taxClass.kingdom` is an invariant the
 * importer, the registrar and the backup import all enforce. `TaxClass.entries.filter` on it
 * is how a chip row or a picker gets its list — nothing iterates `entries` raw any more, or a
 * user-added sparrow would be offered "tree".
 */
enum class TaxClass(val wireName: String, val kingdom: Kingdom) {
    BIRD("bird", Kingdom.ANIMAL),
    MAMMAL("mammal", Kingdom.ANIMAL),
    REPTILE("reptile", Kingdom.ANIMAL),
    AMPHIBIAN("amphibian", Kingdom.ANIMAL),
    FISH("fish", Kingdom.ANIMAL),
    INSECT("insect", Kingdom.ANIMAL),
    OTHER_INVERTEBRATE("other_invertebrate", Kingdom.ANIMAL),
    TREE("tree", Kingdom.PLANT),
    SHRUB("shrub", Kingdom.PLANT),
    HERB("herb", Kingdom.PLANT),
    FERN("fern", Kingdom.PLANT),
    ;

    companion object {
        private val byWireName = entries.associateBy { it.wireName }

        /** Unknown or missing values fall back to `other_invertebrate` rather than throwing. */
        fun fromWireName(value: String?): TaxClass =
            byWireName[value?.trim()?.lowercase()] ?: OTHER_INVERTEBRATE

        /** The class a species falls back to when only its kingdom is known. */
        fun defaultFor(kingdom: Kingdom): TaxClass = when (kingdom) {
            Kingdom.ANIMAL -> OTHER_INVERTEBRATE
            Kingdom.PLANT -> HERB
        }

        fun of(kingdom: Kingdom): List<TaxClass> = entries.filter { it.kingdom == kingdom }
    }
}

/**
 * A documented use of a plant (D14). `EDIBLE` is curated; `MEDICINAL` is derived from Dr.
 * Duke's ethnobotanical database at build time and stored, so the filter stays a plain
 * membership test rather than a join.
 */
enum class PlantUse(val wireName: String) {
    EDIBLE("edible"),
    MEDICINAL("medicinal"),
    ;

    companion object {
        private val byWireName = entries.associateBy { it.wireName }

        /** Null for an unrecognised value: an unknown use is dropped, never guessed at. */
        fun fromWireName(value: String?): PlantUse? = byWireName[value?.trim()?.lowercase()]

        fun setFromWireNames(values: Collection<String>): Set<PlantUse> =
            values.mapNotNull { fromWireName(it) }.toSet()
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
 * One sortable integer column orders the whole grid (ARCHITECTURE.md 3.1, 11.1): curated
 * animals 1–120, curated plants 2001–2080, user-added 9001 upward. Presentation subtracts
 * the base and renders `#021`, `P012` or `U01`.
 *
 * The bases are wide apart on purpose. A catalogue that grows past 120 animals must not
 * walk into the plant range, and the unique `(regionId, dexNumber)` index is what would
 * fail the whole import if it did.
 */
const val USER_DEX_NUMBER_BASE = 9000

const val PLANT_DEX_NUMBER_BASE = 2000

/**
 * The stored number for a curated species the catalogue asset numbers per kingdom (1..n).
 * The asset stays readable — the curator writes 47, not 2047 — and the importer is the only
 * caller.
 */
fun storedDexNumber(kingdom: Kingdom, dexNumber: Int): Int = when (kingdom) {
    Kingdom.ANIMAL -> dexNumber
    Kingdom.PLANT -> PLANT_DEX_NUMBER_BASE + dexNumber
}

/** `#021` for a curated animal, `P012` for a curated plant, `U01` for a user-added one (M02). */
fun displayDexNumber(dexNumber: Int, source: SpeciesSource, kingdom: Kingdom): String =
    when (source) {
        SpeciesSource.USER ->
            "U" + (dexNumber - USER_DEX_NUMBER_BASE).toString().padStart(2, '0')

        SpeciesSource.CURATED -> when (kingdom) {
            Kingdom.ANIMAL -> "#" + dexNumber.toString().padStart(3, '0')
            Kingdom.PLANT ->
                "P" + (dexNumber - PLANT_DEX_NUMBER_BASE).toString().padStart(3, '0')
        }
    }

/**
 * The `Caution:` rule (S09), written once so the detail screen and the confirm card
 * emphasise the same words. A note's caution sentence is rendered above the rest of it in
 * the stop colour, which only works if both screens split the string identically.
 */
object UsesNote {

    /**
     * Splits [note] into (the rest of the note, the caution sentence) at the first sentence
     * that begins `Caution:`. The match is case-insensitive and anchored at a sentence
     * start — the beginning of the string, or just after `.`, `!`, `?` or a newline — so the
     * word appearing mid-sentence ("use caution: it stains") is left alone.
     *
     * Returns the whole note as the first element and null as the second when there is no
     * caution sentence, so a caller can render both halves without a null check on the body.
     */
    fun cautionSplit(note: String?): Pair<String, String?> {
        val text = note?.trim().orEmpty()
        if (text.isEmpty()) return "" to null
        val match = CAUTION.find(text) ?: return text to null
        val start = match.range.first + match.groupValues[1].length
        val body = text.substring(0, start).trim().trimEnd(' ')
        val caution = text.substring(start).trim()
        return body to caution.ifEmpty { null }
    }

    private val CAUTION = Regex("""(^|[.!?\n]\s*)caution:""", RegexOption.IGNORE_CASE)
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
    val kingdom: Kingdom,
    /** Empty for every animal, and for a plant with no documented use (D14). */
    val uses: Set<PlantUse> = emptySet(),
    val silhouetteRes: String,
    val ecosystemIds: List<String>,
    val caughtAt: Long?,
    /** Relative path under `filesDir` of the favorite (else first) capture's thumbnail. */
    val thumbPath: String?,
    val captureCount: Int,
) {
    val caught: Boolean get() = caughtAt != null
    val displayNumber: String get() = displayDexNumber(dexNumber, source, kingdom)
}

/** Everything the detail screen renders about one species (M04/M05). */
data class SpeciesDetail(
    val summary: SpeciesSummary,
    val habitatText: String?,
    val description: String?,
    val imageUrl: String?,
    val infoUrl: String?,
    val imageAttribution: String?,
    /** The curated part-and-season note, with any `Caution:` sentence. Null when no uses. */
    val usesNote: String? = null,
    /**
     * The sourced half of a plant's uses, kept apart from the curated half all the way down
     * because the two carry different confidence and the screen says which is which (M24).
     * Up to eight Duke's activity names, most-cited first; empty when Duke's has nothing.
     */
    val medicinalActivities: List<String> = emptyList(),
    val medicinalRecordCount: Int = 0,
    /** The Duke's credit line, non-null exactly when the two fields above are populated. */
    val usesAttribution: String? = null,
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

/** One ecosystem's row on the Stats screen: the two kingdoms are counted separately (D13). */
data class EcosystemProgress(
    val ecosystem: Ecosystem,
    val animals: Meter,
    val plants: Meter = Meter(0, 0, 0),
)

/**
 * Derived, never stored (DESIGN.md §2 "Dex"). Shared by the grid header and Stats (6.3).
 *
 * The two kingdoms have their own meters and never mix (D13): 47/120 animals and 3/80
 * plants are two life lists that happen to share a region, and a single blended fraction
 * would hide which of them the user is actually working on.
 */
data class DexProgress(
    val regionId: String,
    /** Read from the `regions` table, so no screen has to guess a label from an id. */
    val regionName: String,
    val animals: Meter,
    val plants: Meter,
    val perClass: List<Pair<TaxClass, Meter>>,
    val perEcosystem: List<EcosystemProgress>,
) {
    /** Both kingdoms together — what "is there anything to show yet" asks. */
    val totalSpecies: Int get() = animals.total + plants.total
    val caughtCount: Int get() = animals.caught + plants.caught
    val userAddedCount: Int get() = animals.userAdded + plants.userAdded

    fun meterFor(kingdom: Kingdom): Meter = when (kingdom) {
        Kingdom.ANIMAL -> animals
        Kingdom.PLANT -> plants
    }

    companion object {
        val Empty = DexProgress("", "", Meter(0, 0, 0), Meter(0, 0, 0), emptyList(), emptyList())
    }
}
