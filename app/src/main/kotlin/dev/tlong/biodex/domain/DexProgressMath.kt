package dev.tlong.biodex.domain

/**
 * Progress math (M15 / D9 *(revised, see D29)*), kept as a pure function so it is testable
 * on the JVM.
 *
 * The rules it encodes:
 *  - A caught user-added species counts in its kingdom's fraction, on both sides of it: a
 *    dex of 120 animals plus two of the user's own reads `2/122`, not `0/120 +2` (D29).
 *  - Kingdoms are still never blended into one fraction (D13).
 *  - [Meter.userAdded] survives as the count of how many of those caught species are the
 *    user's own. It is now a breakdown of the numerator rather than an addendum beside it.
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
        // in practice it is always caught; the filter keeps the count honest anyway. An
        // uncaught one — only reachable through a backup import — enters no fraction at all,
        // because it would inflate the denominator with something nobody has found.
        val userAdded = species.filter { it.source == SpeciesSource.USER && it.caught }
        // D29: the two lists are one counted set. Everything below counts `counted`, and
        // `userAdded` is carried alongside only to say how much of it is the user's own.
        val counted = curated + userAdded

        fun meterFor(kingdom: Kingdom) = Meter(
            caught = counted.count { it.kingdom == kingdom && it.caught },
            total = counted.count { it.kingdom == kingdom },
            userAdded = userAdded.count { it.kingdom == kingdom },
        )

        val perClass = TaxClass.entries.mapNotNull { taxClass ->
            val inClass = counted.filter { it.taxClass == taxClass }
            if (inClass.isEmpty()) {
                null
            } else {
                taxClass to Meter(
                    caught = inClass.count { it.caught },
                    total = inClass.size,
                    userAdded = userAdded.count { it.taxClass == taxClass },
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
            // D29: the same counted set as the kingdom meters, so an ecosystem row and the
            // header pill can never disagree about whether the user's own bird exists.
            val countedMembers = members.filter {
                it.source == SpeciesSource.CURATED || it.caught
            }
            fun kingdomMeter(kingdom: Kingdom) = Meter(
                caught = countedMembers.count { it.kingdom == kingdom && it.caught },
                total = countedMembers.count { it.kingdom == kingdom },
                userAdded = countedMembers.count {
                    it.source == SpeciesSource.USER && it.kingdom == kingdom
                },
            )
            EcosystemProgress(
                ecosystem = ecosystem,
                animals = kingdomMeter(Kingdom.ANIMAL),
                plants = kingdomMeter(Kingdom.PLANT),
                fungi = kingdomMeter(Kingdom.FUNGUS),
            )
        }

        return DexProgress(
            regionId = regionId,
            regionName = regionName,
            animals = meterFor(Kingdom.ANIMAL),
            plants = meterFor(Kingdom.PLANT),
            perClass = perClass,
            perEcosystem = perEcosystem,
            // Defaulted rather than required, so a caller that predates the third kingdom
            // still compiles — which is exactly why this was missed: the Stats screen read
            // a fungi meter that nothing ever filled, and rendered nothing, silently.
            fungi = meterFor(Kingdom.FUNGUS),
        )
    }
}
