package dev.tlong.animaldex.domain

/**
 * The user-added species model and — the important part — M21's rule that a hand-edited field
 * survives a later backfill while untouched fields get filled in.
 *
 * All of this is deliberately plain Kotlin with no Room, no network and no Android: it is the
 * subtlest invariant in the slice, and the only way to show it holds without a phone is to
 * make it a function the JVM suite can call directly (the pattern of 3.4, 4.6 and 6.5).
 */

/** The stable field names that appear in `species.userEditedFields` (ARCHITECTURE.md 3.1). */
object SpeciesField {
    const val COMMON_NAME = "commonName"
    const val SCIENTIFIC_NAME = "scientificName"
    const val TAX_CLASS = "taxClass"
    const val HABITAT_TEXT = "habitatText"
    const val DESCRIPTION = "description"
    const val IMAGE_URL = "imageUrl"
    const val CALL_URL = "callUrl"
    const val INFO_URL = "infoUrl"

    /** Every field the confirmation card lets the user edit by hand (M19). */
    val editable = listOf(
        COMMON_NAME,
        SCIENTIFIC_NAME,
        TAX_CLASS,
        HABITAT_TEXT,
        DESCRIPTION,
        IMAGE_URL,
        CALL_URL,
        INFO_URL,
    )
}

/** Everything about a species that a lookup can populate or a user can edit. */
data class SpeciesFields(
    val commonName: String,
    val scientificName: String? = null,
    val taxClass: TaxClass = TaxClass.OTHER_INVERTEBRATE,
    val habitatText: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val imageAttribution: String? = null,
    val callUrl: String? = null,
    val callAttribution: String? = null,
    val infoUrl: String? = null,
) {
    /** The class silhouette is derived, never stored independently (ARCHITECTURE.md 2). */
    val silhouetteRes: String get() = "sil_${taxClass.wireName}"
}

/**
 * What one lookup produced. Every field is nullable because every source can independently
 * find nothing, and "found nothing" must never blank a value the app already has.
 */
data class LookupFields(
    val scientificName: String? = null,
    val taxClass: TaxClass? = null,
    val habitatText: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val imageAttribution: String? = null,
    val callUrl: String? = null,
    val callAttribution: String? = null,
    val infoUrl: String? = null,
)

/**
 * One user-added species row, as the write path sees it. Mirrors `SpeciesEntity` without
 * depending on Room, so the whole accept-and-backfill path is JVM-testable.
 */
data class UserSpeciesRecord(
    val id: String,
    val regionId: String,
    val dexNumber: Int,
    val detailsPending: Boolean,
    val fields: SpeciesFields,
    val userEditedFields: List<String> = emptyList(),
)

/**
 * **M21, the slice's central invariant.** A backfill fills in what the user has not touched
 * and never overwrites what they have.
 *
 * Three rules, each a test:
 *
 * - A field named in [userEdited] is left exactly as it is, whatever the lookup found.
 * - A field not named there takes the lookup's value when the lookup has one — a re-backfill
 *   tracks the newest public data, it is not a one-time null-fill.
 * - A lookup value that is null leaves the existing value alone. A source that failed or found
 *   nothing must not erase good data (which is also why a failed source is `NotFound`, not an
 *   empty string, all the way down).
 *
 * `commonName` is never in the lookup's gift at all: the user supplied the name, and it is the
 * one thing about a user-added species that is theirs by definition.
 *
 * Attribution follows its media: editing `imageUrl` by hand locks `imageAttribution` too,
 * because a credit line that outlives the image it credits is a false claim (M17).
 */
fun mergeLookup(
    existing: SpeciesFields,
    lookup: LookupFields,
    userEdited: Set<String>,
): SpeciesFields {
    fun <T> take(field: String, incoming: T?, current: T): T =
        if (field in userEdited || incoming == null) current else incoming

    return existing.copy(
        scientificName = take(SpeciesField.SCIENTIFIC_NAME, lookup.scientificName, existing.scientificName),
        taxClass = take(SpeciesField.TAX_CLASS, lookup.taxClass, existing.taxClass),
        habitatText = take(SpeciesField.HABITAT_TEXT, lookup.habitatText, existing.habitatText),
        description = take(SpeciesField.DESCRIPTION, lookup.description, existing.description),
        imageUrl = take(SpeciesField.IMAGE_URL, lookup.imageUrl, existing.imageUrl),
        imageAttribution = take(SpeciesField.IMAGE_URL, lookup.imageAttribution, existing.imageAttribution),
        callUrl = take(SpeciesField.CALL_URL, lookup.callUrl, existing.callUrl),
        callAttribution = take(SpeciesField.CALL_URL, lookup.callAttribution, existing.callAttribution),
        infoUrl = take(SpeciesField.INFO_URL, lookup.infoUrl, existing.infoUrl),
    )
}

/**
 * Copies the hand-edited fields out of [values] onto [base], and only those. The confirm card
 * keeps the user's typing in a separate overlay so that swapping to a different GBIF candidate
 * takes the new species' habitat and picture while leaving the field the user rewrote alone.
 */
fun applyFieldEdits(
    base: SpeciesFields,
    values: SpeciesFields?,
    edited: Collection<String>,
): SpeciesFields {
    if (values == null || edited.isEmpty()) return base
    var out = base
    for (field in edited) {
        out = when (field) {
            SpeciesField.COMMON_NAME -> out.copy(commonName = values.commonName)
            SpeciesField.SCIENTIFIC_NAME -> out.copy(scientificName = values.scientificName)
            SpeciesField.TAX_CLASS -> out.copy(taxClass = values.taxClass)
            SpeciesField.HABITAT_TEXT -> out.copy(habitatText = values.habitatText)
            SpeciesField.DESCRIPTION -> out.copy(description = values.description)
            SpeciesField.IMAGE_URL -> out.copy(
                imageUrl = values.imageUrl,
                imageAttribution = values.imageAttribution,
            )

            SpeciesField.CALL_URL -> out.copy(
                callUrl = values.callUrl,
                callAttribution = values.callAttribution,
            )

            SpeciesField.INFO_URL -> out.copy(infoUrl = values.infoUrl)
            else -> out
        }
    }
    return out
}

/**
 * What the confirm card shows and what the write path stores, computed the same way in both
 * places so the preview cannot promise something the save does not do: apply this session's
 * hand-edits, then let the lookup fill everything nobody has claimed.
 */
fun previewFields(
    stored: SpeciesFields,
    lookup: LookupFields?,
    lockedFields: Set<String>,
    editValues: SpeciesFields?,
    editedNow: Set<String>,
): SpeciesFields = mergeLookup(
    existing = applyFieldEdits(stored, editValues, editedNow),
    lookup = lookup ?: LookupFields(),
    userEdited = lockedFields,
)

/**
 * M20's `detailsPending` lifecycle, in one place so "opening it later online presents the
 * card" is decidable.
 *
 * Pending means "a lookup is still owed", and the only thing that discharges the debt is an
 * identity: a scientific name, which is what GBIF resolves and what Wikipedia and Xeno-canto
 * are keyed by. Accepting a card that still has no scientific name — offline, or a name no
 * source recognises — leaves the row pending, so the next online open tries again. That retry
 * is precisely the re-backfill [mergeLookup] has to survive.
 */
fun detailsPendingFor(fields: SpeciesFields): Boolean = fields.scientificName.isNullOrBlank()

/** The dex number a new user-added species takes (ARCHITECTURE.md 3.1: 1001, 1002, …). */
fun nextUserDexNumber(currentMax: Int?): Int =
    maxOf(currentMax ?: USER_DEX_NUMBER_BASE, USER_DEX_NUMBER_BASE) + 1
