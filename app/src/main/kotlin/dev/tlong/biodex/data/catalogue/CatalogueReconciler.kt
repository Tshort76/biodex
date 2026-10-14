package dev.tlong.biodex.data.catalogue

import dev.tlong.biodex.data.db.EcosystemEntity
import dev.tlong.biodex.data.db.RegionEntity
import dev.tlong.biodex.data.db.SpeciesEntity
import dev.tlong.biodex.data.db.SpeciesRegionEntity
import dev.tlong.biodex.domain.Kingdom
import dev.tlong.biodex.domain.SearchMatch
import dev.tlong.biodex.domain.SpeciesUse
import dev.tlong.biodex.domain.SpeciesSource
import dev.tlong.biodex.domain.TaxClass
import dev.tlong.biodex.domain.keptUsesNote
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
 *  - A row with `source = 'user'` is never upserted and never has its ecosystem links
 *    rewritten. It is deleted only when it is **merged** (D89): the asset now carries the
 *    same species — same genus and species name — so its sightings and its entry move onto
 *    the curated row first, and only then does the user row go. Nothing caught is lost.
 *  - `entries` and `captures` are never named by a plan at all, so import cannot reach
 *    them — except through a cascade, which is why the deletion rule below exists.
 *  - A curated species missing from a new asset is deleted only when it has no entry.
 *    Deleting a caught species would destroy its captures via `ON DELETE CASCADE`.
 *  - Ecosystem links are replaced only for species the asset actually carries. A curated
 *    species kept because it has an entry keeps its existing links untouched.
 *  - The asset numbers each kingdom from 1, and the stored number carries the kingdom's
 *    base — the plant range is what keeps the unique `(regionId, dexNumber)` index from
 *    rejecting plant 47 because animal 47 already exists (11.1).
 *  - D95: one species is one row, whichever dexes list it, and `species_regions` says which.
 *    A region's import replaces that region's listing and its own ecosystems' links, and
 *    writes a row's text only when it is the row's home region (or the home no longer lists
 *    it). A curated row only this region listed, which the asset now carries under another
 *    id — the old `ar-` duplicate of a Pacific species — is merged like a user row.
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
        val scientificName: String? = null,
        /** `species.regionId`: the region whose catalogue writes this row's text (D95). */
        val homeRegionId: String = "pacific",
        /** D95: every dex that lists this species → its stored number there. */
        val regions: Map<String, Int> = mapOf(homeRegionId to 0),
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
        /** D89: user species id → the curated species id it is folded into. */
        val merges: Map<String, String> = emptyMap(),
        /** D95: this region's whole listing, which replaces the stored one. */
        val regionMembers: List<SpeciesRegionEntity> = emptyList(),
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
        val regionId = document.regionId
        val existingById = existing.associateBy { it.id }
        val declaredEcosystemIds = document.ecosystems.map { it.id }.toSet()

        // A user row can never be overwritten, even if the asset somehow carries its id.
        val importable = document.species.filter { assetSpecies ->
            existingById[assetSpecies.id]?.source != SpeciesSource.USER
        }
        val assetIds = importable.map { it.id }.toSet()
        val entities = importable.map { it.toEntity(regionId) }

        // D95: a species another dex is home to keeps that dex's text; this one only lists it.
        val speciesUpserts = entities.filter { entity ->
            val held = existingById[entity.id]
            held == null || held.homeRegionId == regionId || held.homeRegionId !in held.regions
        }
        val listed = existing.filter { regionId in it.regions }

        // Unknown ecosystem ids are dropped rather than allowed to fail the whole import
        // on a foreign-key violation; the pipeline validates them, this is the backstop.
        val membershipReplacements = importable.associate { assetSpecies ->
            assetSpecies.id to assetSpecies.ecosystemIds
                .filter { it in declaredEcosystemIds }
                .distinct()
        }

        val merges = merges(importable, listed, regionId, assetIds)
        // Dropped from this dex: a row another dex still lists only loses this listing, and a
        // caught one keeps it, because deleting a caught species would cascade to its captures.
        val dropped = listed.filter {
            it.source == SpeciesSource.CURATED && it.id !in assetIds && it.id !in merges
        }
        val speciesDeletions = dropped.filter { it.regions.size == 1 && !it.hasEntry }.map { it.id }
        val kept = listed.filter {
            it.id !in assetIds && it.id !in merges && it.id !in speciesDeletions &&
                (it.source == SpeciesSource.USER || it.regions.size == 1)
        }
        val regionMembers = entities.map { SpeciesRegionEntity(it.id, regionId, it.dexNumber) } +
            kept.map { SpeciesRegionEntity(it.id, regionId, it.regions.getValue(regionId)) }

        return ImportPlan(
            regionId = document.regionId,
            catalogueVersion = document.catalogueVersion,
            region = RegionEntity(
                id = document.regionId,
                name = document.regionName,
                sortOrder = 0,
                // D34's map outline rides on the region row, so an upgrade that bumps
                // `catalogueVersion` delivers it to an install that already exists — which
                // is the only way a phone with the old schema ever gets a map.
                landMask = document.landMask,
                rangeGridWidth = document.rangeGridWidth,
                rangeGridHeight = document.rangeGridHeight,
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
            merges = merges,
            regionMembers = regionMembers,
        )
    }

    /**
     * D89. Each user species whose genus and species name the asset now carries, mapped to the
     * curated species it becomes — and, D95, each curated row only this region listed that
     * the asset now carries under another region's id.
     */
    private fun merges(
        importable: List<CatalogueSpecies>,
        listed: List<ExistingSpecies>,
        regionId: String,
        assetIds: Set<String>,
    ): Map<String, String> {
        val curatedByName = importable
            .mapNotNull { s -> s.scientificName?.let { SearchMatch.binomial(it) to s.id } }
            .toMap()
        return listed
            .filter {
                it.source == SpeciesSource.USER ||
                    (it.id !in assetIds && it.regions.keys == setOf(regionId))
            }
            .mapNotNull { user ->
                val into = user.scientificName?.let { curatedByName[SearchMatch.binomial(it)] }
                into?.let { user.id to it }
            }
            .toMap()
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
    val resolvedUses = SpeciesUse.setFromWireNames(uses)
        .sortedBy { it.ordinal }
        .map { it.wireName }
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
        infoUrl = infoUrl,
        imageAttribution = imageAttribution,
        silhouetteRes = silhouetteRes ?: "sil_${resolvedClass.wireName}",
        userEditedFields = emptyList(),
        uses = resolvedUses,
        // A note with no use behind it would render as an orphaned paragraph under a section
        // header that never appears, so it goes — **except a `Caution:` sentence**, which
        // survives with no tags at all (11.1, and `keptUsesNote` for why). A recorded toxicity
        // is a fact about the species rather than a qualifier on a use somebody claimed, and
        // dropping it here is what made the pipeline's poison rule exempt untagged species.
        usesNote = keptUsesNote(usesNote, SpeciesUse.setFromWireNames(resolvedUses)),
        // D34. Overwritten wholesale like every other curated field, so a rebuilt catalogue
        // corrects a range the same way it corrects a habitat paragraph.
        rangeCells = rangeCells,
        // D36. Overwritten wholesale like every other curated field: a rebuilt catalogue
        // corrects a lineage the same way it corrects a habitat paragraph, and a rank GBIF
        // has since dropped goes back to null rather than lingering.
        lineageKingdom = lineage?.kingdom,
        lineagePhylum = lineage?.phylum,
        lineageClass = lineage?.taxonClass,
        lineageOrder = lineage?.order,
        lineageFamily = lineage?.family,
    )
}

/**
 * The kingdom/class pairing invariant of 11.1, as a pure function so the importer, the
 * registrar and the backup import can all reach the same rule.
 *
 * The declared kingdom wins. A class that does not belong to it — `tree` on an animal, or
 * `bird` on a fungus — is replaced by that kingdom's default class. The pipeline validates
 * the pairing before shipping an asset, so reaching this is a curator typo; the response is
 * to lose one species' growth form, never to reject the row and leave a gap in the dex.
 */
fun pairKingdomAndClass(kingdomWireName: String?, taxClassWireName: String?): Pair<Kingdom, TaxClass> {
    val kingdom = Kingdom.fromWireName(kingdomWireName)
    val taxClass = TaxClass.fromWireName(taxClassWireName)
    return kingdom to if (taxClass.kingdom == kingdom) taxClass else TaxClass.defaultFor(kingdom)
}
