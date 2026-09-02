package dev.tlong.animaldex.data.backup

/**
 * The database half of export and import. `DexRepository` implements it, beside
 * `CaptureStore` and `UserSpeciesStore` — the pattern slices 5 and 7 established.
 */
interface BackupStore {

    /** Everything an archive contains, read in one pass. */
    suspend fun backupSnapshot(): BackupSnapshot

    /** What the merge needs to know about this database (never its contents in full). */
    suspend fun localSnapshot(): LocalSnapshot

    /**
     * Applies a plan in one transaction. Files are already on disk by the time this runs,
     * so a capture row can never point at a photo that is not there — the same ordering
     * rule registration follows (ARCHITECTURE.md 4.1).
     */
    suspend fun applyImport(plan: ImportPlan)
}

data class BackupSnapshot(
    val regionId: String,
    val species: List<BackupSpecies>,
    val entries: List<BackupEntry>,
    val captures: List<dev.tlong.animaldex.domain.Capture>,
)
