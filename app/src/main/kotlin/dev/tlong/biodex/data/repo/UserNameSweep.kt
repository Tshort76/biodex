package dev.tlong.biodex.data.repo

import androidx.room.withTransaction
import dev.tlong.biodex.data.db.AppDatabase
import dev.tlong.biodex.domain.NamedSpecies
import dev.tlong.biodex.domain.nameCorrections

/**
 * D45: spells the user-added species already in the store the way the door spells a new one
 * (M45). Runs once per start, after the catalogue import, and is idempotent — the second run
 * finds nothing to change. The decision is [nameCorrections]; this class is the read and the
 * write around it.
 */
class UserNameSweep(
    private val db: AppDatabase,
    private val regionId: String = DEFAULT_REGION_ID,
) {

    /** Returns how many rows were respelled. */
    suspend fun run(): Int {
        val rows = db.speciesDao().userSpeciesOnce(regionId)
            .map { NamedSpecies(it.id, it.commonName, it.scientificName) }
        val corrections = nameCorrections(rows)
        if (corrections.isEmpty()) return 0
        db.withTransaction {
            for (c in corrections) db.speciesDao().rename(c.id, c.commonName, c.scientificName)
        }
        return corrections.size
    }
}
