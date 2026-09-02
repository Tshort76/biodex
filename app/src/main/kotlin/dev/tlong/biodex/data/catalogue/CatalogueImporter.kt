package dev.tlong.biodex.data.catalogue

import android.content.Context
import android.util.Log
import java.io.IOException
import java.io.InputStream

/**
 * Reads one bundled asset. An interface so the JVM tests can feed the importer a fixture
 * (and a missing asset, and a corrupt one) without an Android `AssetManager`.
 */
fun interface AssetReader {
    /** Returns null when the asset does not exist. Throws only on a genuine read failure. */
    fun open(path: String): InputStream?
}

/** Reads from the APK's `assets/` directory. */
class AndroidAssetReader(private val context: Context) : AssetReader {
    override fun open(path: String): InputStream? =
        try {
            context.assets.open(path)
        } catch (e: IOException) {
            // AssetManager signals "no such asset" with a plain FileNotFoundException.
            null
        }
}

sealed interface ImportOutcome {
    /** `meta.catalogueVersion` already matches the asset; nothing was written. */
    data object UpToDate : ImportOutcome

    data class Imported(
        val catalogueVersion: Int,
        val speciesUpserted: Int,
        val speciesDeleted: Int,
        val ecosystems: Int,
    ) : ImportOutcome

    /**
     * The asset is not in the APK. The database is left exactly as it was — for a fresh
     * install that means empty, and the grid renders its normal empty state.
     */
    data object AssetMissing : ImportOutcome

    /** The asset exists but is not readable as a catalogue. Same non-destructive stance. */
    data class ParseFailed(val cause: Throwable) : ImportOutcome
}

/**
 * Runs on `Application` start, on a background dispatcher (ARCHITECTURE.md 3.3).
 *
 * [import] never throws: a missing or malformed asset must not take the app down, so both
 * are logged and returned as an outcome, leaving the database untouched.
 */
class CatalogueImporter(
    private val assets: AssetReader,
    private val store: CatalogueStore,
    private val assetPath: String = CATALOGUE_ASSET_PATH,
) {

    suspend fun import(): ImportOutcome {
        val document = when (val parsed = readDocument()) {
            is ReadResult.Missing -> {
                Log.w(TAG, "Catalogue asset '$assetPath' is not bundled; leaving the database empty.")
                return ImportOutcome.AssetMissing
            }
            is ReadResult.Failed -> {
                Log.e(TAG, "Catalogue asset '$assetPath' could not be parsed.", parsed.cause)
                return ImportOutcome.ParseFailed(parsed.cause)
            }
            is ReadResult.Ok -> parsed.document
        }

        val existing = store.existingSpecies(document.regionId)
        return when (
            val decision =
                CatalogueReconciler.decide(store.catalogueVersion(), document, existing)
        ) {
            is CatalogueReconciler.ImportDecision.UpToDate -> {
                Log.i(TAG, "Catalogue v${document.catalogueVersion} already imported.")
                ImportOutcome.UpToDate
            }

            is CatalogueReconciler.ImportDecision.Apply -> {
                val plan = decision.plan
                store.apply(plan)
                Log.i(
                    TAG,
                    "Imported catalogue v${plan.catalogueVersion}: " +
                        "${plan.speciesUpserts.size} species, " +
                        "${plan.ecosystems.size} ecosystems, " +
                        "${plan.speciesDeletions.size} removed.",
                )
                ImportOutcome.Imported(
                    catalogueVersion = plan.catalogueVersion,
                    speciesUpserted = plan.speciesUpserts.size,
                    speciesDeleted = plan.speciesDeletions.size,
                    ecosystems = plan.ecosystems.size,
                )
            }
        }
    }

    private sealed interface ReadResult {
        data class Ok(val document: CatalogueDocument) : ReadResult
        data object Missing : ReadResult
        data class Failed(val cause: Throwable) : ReadResult
    }

    private fun readDocument(): ReadResult =
        try {
            val stream = assets.open(assetPath) ?: return ReadResult.Missing
            val text = stream.use { it.readBytes().decodeToString() }
            ReadResult.Ok(catalogueJson.decodeFromString<CatalogueDocument>(text))
        } catch (e: Exception) {
            ReadResult.Failed(e)
        }

    private companion object {
        const val TAG = "CatalogueImporter"
    }
}
