package dev.tlong.biodex.domain

/**
 * Progress math (M15 / D9), kept as a pure function so it is testable on the JVM.
 *
 * The rules it encodes, all from DESIGN.md D9:
 *  - Curated species alone form every fraction; each kingdom's meter is `caught / total`,
 *    and the two are never blended (D13).
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
        val kingdom: Kingdom,
        val caught: Boolean,
    )

    /** One `species_ecosystems` row. */
    data class MembershipRow(
        val speciesId: String,
        val ecosystemId: String,
    )

    fun compute(
        regionId: String,
        regionName: String,
        species: List<SpeciesRow>,
        memberships: List<MembershipRow>,
        ecosystems: List<Ecosystem>,
    ): DexProgress {
        val curated = species.filter { it.source == SpeciesSource.CURATED }
        // A user-added species exists only because the user registered a photo of it, so
        // in practice it is always caught; the filter keeps the addendum honest anyway.
        val userAdded = species.filter { it.source == SpeciesSource.USER && it.caught }

        fun meterFor(kingdom: Kingdom) = Meter(
            caught = curated.count { it.kingdom == kingdom && it.caught },
            total = curated.count { it.kingdom == kingdom },
            userAdded = userAdded.count { it.kingdom == kingdom },
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
            fun kingdomMeter(kingdom: Kingdom) = Meter(
                caught = curatedMembers.count { it.kingdom == kingdom && it.caught },
                total = curatedMembers.count { it.kingdom == kingdom },
                userAdded = members.count {
                    it.source == SpeciesSource.USER && it.caught && it.kingdom == kingdom
                },
            )
            EcosystemProgress(
                ecosystem = ecosystem,
                animals = kingdomMeter(Kingdom.ANIMAL),
                plants = kingdomMeter(Kingdom.PLANT),
            )
        }

        return DexProgress(
            regionId = regionId,
            regionName = regionName,
            animals = meterFor(Kingdom.ANIMAL),
            plants = meterFor(Kingdom.PLANT),
            perClass = perClass,
            perEcosystem = perEcosystem,
        )
    }
}
