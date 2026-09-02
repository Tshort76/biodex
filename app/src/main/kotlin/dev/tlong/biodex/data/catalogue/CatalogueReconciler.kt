package dev.tlong.biodex.data.catalogue

import dev.tlong.biodex.data.db.EcosystemEntity
import dev.tlong.biodex.data.db.RegionEntity
import dev.tlong.biodex.data.db.SpeciesEntity
import dev.tlong.biodex.domain.Kingdom
import dev.tlong.biodex.domain.PlantUse
import dev.tlong.biodex.domain.SpeciesSource
import dev.tlong.biodex.domain.TaxClass
import dev.tlong.biodex.domain.storedDexNumber

/**
 * The importer's decision-making, as a pure function.
 *
 * Everything that could destroy a user's data is decided here, with no Room, no Android
 * and no I/O, so ARCHITECTURE.md 3.3's invariants are provable in a JVM test rather than
 * only on a phone. [CatalogueImporter] does nothing but read the asset, call [decide],
 * and hand the resulting [ImportPlan] to a [CatalogueStore] to apply in one transaction.
 *
 * The invariants, restated as the rules this function follows:
 *  - A row with `source = 'user'` is never upserted, never deleted, and never has its
 *    ecosystem links rewritten. The plan simply never names it.
 *  - `entries` and `captures` are never named by a plan at all, so import cannot reach
 *    them — except through a cascade, which is why the deletion rule below exists.
 *  - A curated species missing from a new asset is deleted only when it has no entry.
 *    Deleting a caught species would destroy its captures via `ON DELETE CASCADE`.
 *  - Ecosystem links are replaced only for species the asset actually carries. A curated
 *    species kept because it has an entry keeps its existing links untouched.
 *  - The asset numbers each kingdom from 1, and the stored number carries the kingdom's
 *    base — the plant range is what keeps the unique `(regionId, dexNumber)` index from
 *    rejecting plant 47 because animal 47 already exists (11.1).
 *  - `kingdom == taxClass.kingdom` holds on every row this plans. The declared kingdom wins
 *    a disagreement and the class falls back to that kingdom's default, so a curator typo
 *    costs one species its growth form rather than costing the whole import.
 */
object CatalogueReconciler {

    /** The minimum the reconciler needs to know about a row already in the database. */
    data class ExistingSpecies(
        val id: String,
        val source: SpeciesSource,
        val hasEntry: Boolean,
    )

    data class ImportPlan(
        val regionId: String,
        val catalogueVersion: Int,
        val region: RegionEntity,
        val ecosystems: List<EcosystemEntity>,
        val speciesUpserts: List<SpeciesEntity>,
        /** speciesId → the ecosystem ids that replace its current links. */
        val membershipReplacements: Map<String, List<String>>,
        val speciesDeletions: List<String>,
    )

    sealed interface ImportDecision {
        /** `meta.catalogueVersion` already equals the asset's — step 1 of 3.3. */
        data object UpToDate : ImportDecision
        data class Apply(val plan: ImportPlan) : ImportDecision
    }

    fun decide(
        currentVersion: Int?,
        document: CatalogueDocument,
        existing: List<ExistingSpecies>,
    ): ImportDecision =
        if (currentVersion != null && currentVersion == document.catalogueVersion) {
            ImportDecision.UpToDate
        } else {
            ImportDecision.Apply(plan(document, existing))
        }

    fun plan(document: CatalogueDocument, existing: List<ExistingSpecies>): ImportPlan {
        val existingById = existing.associateBy { it.id }
        val declaredEcosystemIds = document.ecosystems.map { it.id }.toSet()

        // A user row can never be overwritten, even if the asset somehow carries its id.
        val importable = document.species.filter { assetSpecies ->
            existingById[assetSpecies.id]?.source != SpeciesSource.USER
        }
        val assetIds = importable.map { it.id }.toSet()

        val speciesUpserts = importable.map { it.toEntity(document.regionId) }

        // Unknown ecosystem ids are dropped rather than allowed to fail the whole import
        // on a foreign-key violation; the pipeline validates them, this is the backstop.
        val membershipReplacements = importable.associate { assetSpecies ->
            assetSpecies.id to assetSpecies.ecosystemIds
                .filter { it in declaredEcosystemIds }
                .distinct()
        }

        val speciesDeletions = existing
            .filter { it.source == SpeciesSource.CURATED && it.id !in assetIds && !it.hasEntry }
            .map { it.id }

        return ImportPlan(
            regionId = document.regionId,
            catalogueVersion = document.catalogueVersion,
            region = RegionEntity(
                id = document.regionId,
                name = document.regionName,
                sortOrder = 0,
            ),
            ecosystems = document.ecosystems.map {
                EcosystemEntity(
                    id = it.id,
                    regionId = document.regionId,
                    name = it.name,
                    sortOrder = it.sortOrder,
                )
            },
            speciesUpserts = speciesUpserts,
            membershipReplacements = membershipReplacements,
            speciesDeletions = speciesDeletions,
        )
    }
}

/**
 * Curated species are not user-editable in v1 (DESIGN.md §7), so `userEditedFields` is
 * always empty and every curated field may be overwritten wholesale (ARCHITECTURE.md 3.3).
 */
internal fun CatalogueSpecies.toEntity(regionId: String): SpeciesEntity {
    val (resolvedKingdom, resolvedClass) = pairKingdomAndClass(kingdom, taxClass)
    // `uses` is a closed vocabulary: an unrecognised value is dropped rather than stored,
    // because a filter chip that can never match is worse than a missing tag.
    val resolvedUses = PlantUse.setFromWireNames(uses)
        .sortedBy { it.ordinal }
        .map { it.wireName }
    val hasDukeRecord = medicinalRecordCount > 0 || medicinalActivities.isNotEmpty()
    return SpeciesEntity(
        id = id,
        regionId = regionId,
        dexNumber = storedDexNumber(resolvedKingdom, dexNumber),
        source = SpeciesSource.CURATED,
        detailsPending = false,
        commonName = commonName,
        scientificName = scientificName,
        taxClass = resolvedClass,
        kingdom = resolvedKingdom,
        habitatText = habitatText,
        description = description,
        imageUrl = imageUrl,
        callUrl = callUrl,
        infoUrl = infoUrl,
        imageAttribution = imageAttribution,
        callAttribution = callAttribution,
        silhouetteRes = silhouetteRes ?: "sil_${resolvedClass.wireName}",
        userEditedFields = emptyList(),
        uses = resolvedUses,
        // A note with no use behind it would render as an orphaned paragraph under a
        // section header that never appears; the write paths all drop it (11.1).
        usesNote = usesNote?.takeIf { resolvedUses.isNotEmpty() },
        medicinalActivities = medicinalActivities,
        medicinalRecordCount = medicinalRecordCount,
        // The credit line belongs to Duke's data. Without the data it is a claim about a
        // source that contributed nothing, so it goes.
        usesAttribution = usesAttribution?.takeIf { hasDukeRecord },
    )
}

/**
 * The kingdom/class pairing invariant of 11.1, as a pure function so the importer, the
 * registrar and the backup import can all reach the same rule.
 *
 * The declared kingdom wins. A class that does not belong to it — `tree` on an animal, or
 * `bird` on a plant — is replaced by that kingdom's default class. The pipeline validates
 * the pairing before shipping an asset, so reaching this is a curator typo; the response is
 * to lose one species' growth form, never to reject the row and leave a gap in the dex.
 */
fun pairKingdomAndClass(kingdomWireName: String?, taxClassWireName: String?): Pair<Kingdom, TaxClass> {
    val kingdom = Kingdom.fromWireName(kingdomWireName)
    val taxClass = TaxClass.fromWireName(taxClassWireName)
    return kingdom to if (taxClass.kingdom == kingdom) taxClass else TaxClass.defaultFor(kingdom)
}
