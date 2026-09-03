package dev.tlong.biodex.data.catalogue

import java.io.File

/**
 * The **shipped** `pacific.json`, read from `app/src/main/assets/` by the tests that assert
 * things about its contents.
 *
 * Same stance and same reasoning as [RealDukeAsset] beside it: a hand-copied fixture of this
 * data is a test that keeps passing after the thing it describes stops being true, which
 * this project hit twice in one day — once on a species count that grew from 120 to 200, and
 * once on an invariant that turned into a statement of a bug when the schema moved under it.
 * Nothing failed either time.
 *
 * The fungal assertions are the reason it exists now. `curated_fungi.json` is the one input
 * with no dataset behind it (Dr. Duke's has no fungal taxa), so "no mushroom ships with a
 * use tag" has to be checked against what actually ships rather than against a fixture that
 * agrees with it by construction.
 */
object RealCatalogueAsset {

    val path: File by lazy {
        // Unit tests run from `app/`, but a runner rooted at the repository is not worth a
        // mystery failure over.
        listOf("src/main/assets", "app/src/main/assets")
            .map { File("$it/$CATALOGUE_ASSET_PATH") }
            .firstOrNull { it.isFile }
            ?: error("the shipped catalogue asset is missing; the pipeline must run first")
    }

    val document: CatalogueDocument by lazy {
        catalogueJson.decodeFromString(path.readText())
    }

    /** An [AssetReader] serving the shipped asset, for driving the real importer. */
    fun reader() = AssetReader { requested ->
        if (requested == CATALOGUE_ASSET_PATH) path.inputStream() else null
    }

    fun speciesOf(kingdom: String): List<CatalogueSpecies> =
        document.species.filter { it.kingdom == kingdom }
}
