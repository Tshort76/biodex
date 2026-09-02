package dev.tlong.biodex.domain

/**
 * Progress math (M15 / D9), kept as a pure function so it is testable on the JVM.
 *
 * The rules it encodes, all from DESIGN.md D9:
 *  - Curated species alone form every fraction; the region's meter is `caught / total`.
 *  - A user-added species never enters a fraction. It is an addendum ("+3 of your own"),
 *    counted in [Meter.userAdded].
 *  - A species belonging to several ecosystems counts in each of them, so ecosystem
 *    totals sum past the catalogue size. Each meter is internally consistent.
 */
object DexProgressMath {

    /** The one row per species this computation needs — a projection, not a full model. */
    data class SpeciesRow(
        val id: String,
        val source: SpeciesSource,
        val taxClass: TaxClass,
        val caught: Boolean,
    )

    /** One `species_ecosystems` row. */
    data class MembershipRow(
        val speciesId: String,
        val ecosystemId: String,
    )

    fun compute(
        regionId: String,
        species: List<SpeciesRow>,
        memberships: List<MembershipRow>,
        ecosystems: List<Ecosystem>,
    ): DexProgress {
        val curated = species.filter { it.source == SpeciesSource.CURATED }
        // A user-added species exists only because the user registered a photo of it, so
        // in practice it is always caught; the filter keeps the addendum honest anyway.
        val userAdded = species.filter { it.source == SpeciesSource.USER && it.caught }

        val overall = Meter(
            caught = curated.count { it.caught },
            total = curated.size,
            userAdded = userAdded.size,
        )

        val perClass = TaxClass.entries.mapNotNull { taxClass ->
            val inClass = curated.filter { it.taxClass == taxClass }
            val addenda = userAdded.count { it.taxClass == taxClass }
            if (inClass.isEmpty() && addenda == 0) {
                null
            } else {
                taxClass to Meter(
                    caught = inClass.count { it.caught },
                    total = inClass.size,
                    userAdded = addenda,
                )
            }
        }

        val byId = species.associateBy { it.id }
        // Duplicate join rows must not double-count, so membership is a set per ecosystem.
        val membersOf = mutableMapOf<String, MutableSet<String>>()
        memberships.forEach { row ->
            if (byId.containsKey(row.speciesId)) {
                membersOf.getOrPut(row.ecosystemId) { mutableSetOf() }.add(row.speciesId)
            }
        }

        val perEcosystem = ecosystems.sortedBy { it.sortOrder }.map { ecosystem ->
            val members = membersOf[ecosystem.id].orEmpty().mapNotNull { byId[it] }
            val curatedMembers = members.filter { it.source == SpeciesSource.CURATED }
            EcosystemProgress(
                ecosystem = ecosystem,
                meter = Meter(
                    caught = curatedMembers.count { it.caught },
                    total = curatedMembers.size,
                    userAdded = members.count { it.source == SpeciesSource.USER && it.caught },
                ),
            )
        }

        return DexProgress(
            regionId = regionId,
            overall = overall,
            perClass = perClass,
            perEcosystem = perEcosystem,
        )
    }
}
