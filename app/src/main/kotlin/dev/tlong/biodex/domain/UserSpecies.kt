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
    const val USES = "uses"
    const val USES_NOTE = "usesNote"

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
        USES,
        USES_NOTE,
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
    /** Plant-only (D14); empty for every animal and for a plant with no recorded use. */
    val uses: Set<PlantUse> = emptySet(),
    /** The part-and-season note with any `Caution:` sentence. Kept per [keptUsesNote]. */
    val usesNote: String? = null,
    /** Duke's activity names, most-cited first. Source data, never the user's to edit. */
    val medicinalActivities: List<String> = emptyList(),
    val medicinalRecordCount: Int = 0,
    val usesAttribution: String? = null,
    /**
     * The one silhouette choice a lookup can make that the class alone cannot: GBIF's
     * `Pinopsida` / `Pinales` picks the conifer shape over the broadleaf one (11.3 step 1).
     * Read only while the class is still `TREE`, so re-picking the growth form drops it.
     */
    val silhouetteResOverride: String? = null,
) {
    /** The class silhouette is derived, never stored independently (ARCHITECTURE.md 2). */
    val silhouetteRes: String
        get() = silhouetteResOverride?.takeIf { taxClass == TaxClass.TREE }
            ?: defaultSilhouetteFor(taxClass)
}

/**
 * The `usesNote` rule, and the one place the app decides whether a warning is allowed to
 * outlive the tag it arrived with.
 *
 * A note is kept whole while the plant carries a use tag. With **no** tags, only a `Caution:`
 * sentence survives, and it survives alone: a recorded toxicity is safety information about the
 * species, not a qualifier on a use the user claimed, while the rest of a note describes a use
 * that is no longer tagged and has nowhere to render.
 *
 * **Why the exception exists at all.** Everything about how this app handles plants rests on
 * never letting the absence of a warning imply safety — it is why there is no "toxic" tag
 * (tagging some species would imply the untagged ones are safe, D14) and why the pipeline makes
 * a missing caution a build failure rather than a lint. Dropping a recorded toxicity because
 * the user did not tick "edible" inverts that exactly, and the person it strands is the one who
 * photographed something unfamiliar, tagged nothing, and comes back to it months later. The
 * confirm card's warning cannot cover this: the card lasts one session, and the note is what
 * persists.
 */
fun keptUsesNote(note: String?, uses: Set<PlantUse>): String? {
    val text = note?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (uses.isNotEmpty()) return text
    return UsesNote.cautionSplit(text).second
}

/**
 * `TREE` is the one class with two shapes (`sil_tree_conifer` / `sil_tree_broadleaf`, 11.4), so
 * it cannot be spelled `sil_${wireName}` like the rest; broadleaf is the fallback the class map
 * uses too. Every other class is its own name.
 */
fun defaultSilhouetteFor(taxClass: TaxClass): String =
    if (taxClass == TaxClass.TREE) "sil_tree_broadleaf" else "sil_${taxClass.wireName}"

/**
 * The write-path invariants of ARCHITECTURE.md 11.1, applied wherever a `SpeciesFields` is
 * about to be shown or saved, so the card cannot preview a shape the save would not produce:
 *
 * - `kingdom == taxClass.kingdom`. The declared kingdom wins and a class that does not belong
 *   to it falls back to that kingdom's default — the same rule as
 *   `CatalogueReconciler.pairKingdomAndClass`, which the importer and the backup import use.
 *   A unit test pins the two against drift.
 * - Uses are plant-only, so an animal carries no uses, no note and no Duke's columns.
 * - `usesNote` is null when `uses` is empty **except for a caution** — see [keptUsesNote].
 * - `usesAttribution` is null unless there is Duke's data to attribute.
 */
fun SpeciesFields.normalized(): SpeciesFields {
    val plant = kingdom == Kingdom.PLANT
    val pairedClass = if (taxClass.kingdom == kingdom) taxClass else TaxClass.defaultFor(kingdom)
    val keptUses = if (plant) uses else emptySet()
    val activities = if (plant) medicinalActivities else emptyList()
    val recordCount = if (plant) medicinalRecordCount else 0
    return copy(
        taxClass = pairedClass,
        uses = keptUses,
        // An animal has no uses slot to render a note in at all, caution or not.
        usesNote = if (plant) keptUsesNote(usesNote, keptUses) else null,
        medicinalActivities = activities,
        medicinalRecordCount = recordCount,
        usesAttribution = usesAttribution?.takeIf { activities.isNotEmpty() || recordCount > 0 },
        silhouetteResOverride = silhouetteResOverride?.takeIf { pairedClass == TaxClass.TREE },
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
    /**
     * The medicinal half only: `{MEDICINAL}` when the bundled Duke's index clears the
     * three-activity rule, otherwise an empty set for a plant and null for an animal. Edible
     * is never derived — it is the user's to set, and D14/M30 are why.
     */
    val uses: Set<PlantUse>? = null,
    /** The `Caution:` sentence a Duke's `Poison` record pre-fills (M27). */
    val usesNote: String? = null,
    val medicinalActivities: List<String>? = null,
    val medicinalRecordCount: Int? = null,
    val usesAttribution: String? = null,
    val silhouetteResOverride: String? = null,
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
 * The three Duke's columns are the exception to all of this and are deliberately **not**
 * user-owned: they are what a source says about the species, they are re-derived from the
 * bundled index on every lookup, and the card shows them read-only. A user who turns the
 * medicinal toggle off owns `uses`, not Duke's record count.
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
        silhouetteResOverride = take(
            SpeciesField.TAX_CLASS,
            lookup.silhouetteResOverride,
            existing.silhouetteResOverride,
        ),
        habitatText = take(SpeciesField.HABITAT_TEXT, lookup.habitatText, existing.habitatText),
        description = take(SpeciesField.DESCRIPTION, lookup.description, existing.description),
        imageUrl = take(SpeciesField.IMAGE_URL, lookup.imageUrl, existing.imageUrl),
        imageAttribution = take(SpeciesField.IMAGE_URL, lookup.imageAttribution, existing.imageAttribution),
        infoUrl = take(SpeciesField.INFO_URL, lookup.infoUrl, existing.infoUrl),
        uses = take(SpeciesField.USES, lookup.uses, existing.uses),
        usesNote = take(SpeciesField.USES_NOTE, lookup.usesNote, existing.usesNote),
        medicinalActivities = lookup.medicinalActivities ?: existing.medicinalActivities,
        medicinalRecordCount = lookup.medicinalRecordCount ?: existing.medicinalRecordCount,
        usesAttribution = lookup.usesAttribution ?: existing.usesAttribution,
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
                silhouetteResOverride = values.silhouetteResOverride,
            )
            SpeciesField.HABITAT_TEXT -> out.copy(habitatText = values.habitatText)
            SpeciesField.DESCRIPTION -> out.copy(description = values.description)
            SpeciesField.IMAGE_URL -> out.copy(
                imageUrl = values.imageUrl,
                imageAttribution = values.imageAttribution,
            )

            SpeciesField.INFO_URL -> out.copy(infoUrl = values.infoUrl)
            SpeciesField.USES -> out.copy(uses = values.uses)
            SpeciesField.USES_NOTE -> out.copy(usesNote = values.usesNote)
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
