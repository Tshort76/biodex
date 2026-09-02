package dev.tlong.biodex.data.repo

import dev.tlong.biodex.domain.UserSpeciesRecord

/**
 * The persistence the user-added flow needs, as an interface so the whole write path runs in
 * the JVM suite against an in-memory fake — the split slice 5 introduced for `CaptureStore`
 * and slice 3 for `CatalogueStore` (ARCHITECTURE.md 3.4, 4.6).
 *
 * Note what is *not* here: nothing touches curated species. A user-added row is written by id
 * and its ecosystem memberships are replaced wholesale for that id only, so this store cannot
 * reach the catalogue any more than the importer can reach user rows (3.3's mirror image).
 */
interface UserSpeciesStore {

    /** Null when no user-added species exists yet; the first one becomes 1001 (U01). */
    suspend fun maxUserDexNumber(regionId: String): Int?

    suspend fun userSpecies(speciesId: String): UserSpeciesRecord?

    /** One transaction: the species row plus its ecosystem memberships. */
    suspend fun upsertUserSpecies(record: UserSpeciesRecord, ecosystemIds: List<String>?)

    /** Used only to roll back a species whose photo turned out to be unreadable. */
    suspend fun deleteUserSpecies(speciesId: String)
}
