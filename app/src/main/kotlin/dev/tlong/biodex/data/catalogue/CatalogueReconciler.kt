package dev.tlong.biodex.data.catalogue

import dev.tlong.biodex.data.db.EcosystemEntity
import dev.tlong.biodex.data.db.SpeciesEntity
import dev.tlong.biodex.domain.SpeciesSource
import dev.tlong.biodex.domain.TaxClass

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
    val resolvedClass = TaxClass.fromWireName(taxClass)
    return SpeciesEntity(
        id = id,
        regionId = regionId,
        dexNumber = dexNumber,
        source = SpeciesSource.CURATED,
        detailsPending = false,
        commonName = commonName,
        scientificName = scientificName,
        taxClass = resolvedClass,
        habitatText = habitatText,
        description = description,
        imageUrl = imageUrl,
        callUrl = callUrl,
        infoUrl = infoUrl,
        imageAttribution = imageAttribution,
        callAttribution = callAttribution,
        silhouetteRes = silhouetteRes ?: "sil_${resolvedClass.wireName}",
        userEditedFields = emptyList(),
    )
}
