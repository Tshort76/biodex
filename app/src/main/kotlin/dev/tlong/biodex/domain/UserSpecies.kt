package dev.tlong.biodex.domain

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
    const val INFO_URL = "infoUrl"
    const val KINGDOM = "kingdom"

    /** Every field the confirmation card lets the user edit by hand (M19). */
    val editable = listOf(
        COMMON_NAME,
        SCIENTIFIC_NAME,
        KINGDOM,
        TAX_CLASS,
        HABITAT_TEXT,
        DESCRIPTION,
        IMAGE_URL,
        INFO_URL,
    )
}

/** Everything about a species that a lookup can populate or a user can edit. */
data class SpeciesFields(
    val commonName: String,
    val scientificName: String? = null,
    val kingdom: Kingdom = Kingdom.ANIMAL,
    val taxClass: TaxClass = TaxClass.OTHER_INVERTEBRATE,
    val habitatText: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val imageAttribution: String? = null,
    val infoUrl: String? = null,
    /** The curated *Food source* tag (D14, D48); empty for every user-added species. */
    val uses: Set<SpeciesUse> = emptySet(),
    /** A note with any `Caution:` sentence. Kept per [keptUsesNote]. */
    val usesNote: String? = null,
    /** D36: the Linnaean path, for the hop count. Never the user's to edit. */
    val lineage: Lineage = Lineage.Unknown,
) {
    /** The class silhouette is derived, never stored independently (ARCHITECTURE.md 2). */
    val silhouetteRes: String get() = defaultSilhouetteFor(taxClass)
}

/**
 * The `usesNote` rule, and the one place the app decides whether a warning is allowed to
 * outlive the tag it arrived with.
 *
 * A note is kept whole while the species carries a use tag. With **no** tags, only a `Caution:`
 * sentence survives, and it survives alone: a recorded toxicity is safety information about the
 * species, not a qualifier on a use the user claimed, while the rest of a note describes a use
 * that is no longer tagged and has nowhere to render.
 *
 * **Why the exception exists at all.** Everything about how this app handles a use rests on
 * never letting the absence of a warning imply safety — it is why there is no "toxic" tag
 * (tagging some species would imply the untagged ones are safe, D14). Dropping a recorded
 * toxicity because a tag went away inverts that exactly. The curated fungi are what carry a
 * caution today (M35); the rule is written once so the importer and the backup import agree.
 */
fun keptUsesNote(note: String?, uses: Set<SpeciesUse>): String? {
    val text = note?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (uses.isNotEmpty()) return text
    return UsesNote.cautionSplit(text).second
}

/** Every class is its own drawable. (`TREE` once had two shapes; it left with D59.) */
fun defaultSilhouetteFor(taxClass: TaxClass): String = "sil_${taxClass.wireName}"

/**
 * The write-path invariants of ARCHITECTURE.md 11.1, applied wherever a `SpeciesFields` is
 * about to be shown or saved, so the card cannot preview a shape the save would not produce:
 *
 * - `kingdom == taxClass.kingdom`. The declared kingdom wins and a class that does not belong
 *   to it falls back to that kingdom's default — the same rule as
 *   `CatalogueReconciler.pairKingdomAndClass`, which the importer and the backup import use.
 *   A unit test pins the two against drift.
 * - A user-added species carries no use and no note: the Food source tag is curated (D48)
 *   and no source pre-fills a caution any more (D59). Both are cleared here so an old backup
 *   cannot bring them back.
 * - The names are spelled the catalogue's way (M45: [formatCommonName], [formatScientificName]).
 */
fun SpeciesFields.normalized(): SpeciesFields {
    val pairedClass = if (taxClass.kingdom == kingdom) taxClass else TaxClass.defaultFor(kingdom)
    return copy(
        commonName = formatCommonName(commonName),
        scientificName = formatScientificName(scientificName),
        taxClass = pairedClass,
        uses = emptySet(),
        usesNote = null,
    )
}

/**
 * What one lookup produced. Every field is nullable because every source can independently
 * find nothing, and "found nothing" must never blank a value the app already has.
 */
data class LookupFields(
    val scientificName: String? = null,
    val kingdom: Kingdom? = null,
    val taxClass: TaxClass? = null,
    val habitatText: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val imageAttribution: String? = null,
    val infoUrl: String? = null,
    /** D36. Sourced — a lookup sets it and nothing else does. */
    val lineage: Lineage? = null,
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
 *
 * The lineage is the exception to all of this and is deliberately **not** user-owned: it is
 * what a source says about the species and no field on the card edits it.
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
        kingdom = take(SpeciesField.KINGDOM, lookup.kingdom, existing.kingdom),
        taxClass = take(SpeciesField.TAX_CLASS, lookup.taxClass, existing.taxClass),
        habitatText = take(SpeciesField.HABITAT_TEXT, lookup.habitatText, existing.habitatText),
        description = take(SpeciesField.DESCRIPTION, lookup.description, existing.description),
        imageUrl = take(SpeciesField.IMAGE_URL, lookup.imageUrl, existing.imageUrl),
        imageAttribution = take(SpeciesField.IMAGE_URL, lookup.imageAttribution, existing.imageAttribution),
        infoUrl = take(SpeciesField.INFO_URL, lookup.infoUrl, existing.infoUrl),
        // D36. Sourced data with no field the user can edit, so it merges outside `take`.
        // The `isKnown` guard is load-bearing: a lookup
        // always carries a non-null `Lineage`, and an unclassified one is `Lineage.Unknown`
        // rather than null — so testing nullability alone would let a second backfill that
        // came back empty wipe a path the first one found.
        lineage = lookup.lineage?.takeIf { it.isKnown } ?: existing.lineage,
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

            // Kingdom and class move together in both directions. Toggling the kingdom resets
            // the class to that kingdom's default (11.4), and picking a growth form is also a
            // statement about the kingdom — otherwise a backfill that re-read GBIF's kingdom
            // would drag the hand-picked class back to the other kingdom's default.
            SpeciesField.KINGDOM, SpeciesField.TAX_CLASS -> out.copy(
                kingdom = values.kingdom,
                taxClass = values.taxClass,
            )
            SpeciesField.HABITAT_TEXT -> out.copy(habitatText = values.habitatText)
            SpeciesField.DESCRIPTION -> out.copy(description = values.description)
            SpeciesField.IMAGE_URL -> out.copy(
                imageUrl = values.imageUrl,
                imageAttribution = values.imageAttribution,
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
).normalized()

/**
 * M20's `detailsPending` lifecycle, in one place so "opening it later online presents the
 * card" is decidable.
 *
 * Pending means "a lookup is still owed", and the only thing that discharges the debt is an
 * identity: a scientific name, which is what GBIF resolves and what Wikipedia is keyed by. Accepting a card that still has no scientific name — offline, or a name no
 * source recognises — leaves the row pending, so the next online open tries again. That retry
 * is precisely the re-backfill [mergeLookup] has to survive.
 */
fun detailsPendingFor(fields: SpeciesFields): Boolean = fields.scientificName.isNullOrBlank()

/** The dex number a new user-added species takes (ARCHITECTURE.md 3.1: 1001, 1002, …). */
fun nextUserDexNumber(currentMax: Int?): Int =
    maxOf(currentMax ?: USER_DEX_NUMBER_BASE, USER_DEX_NUMBER_BASE) + 1
