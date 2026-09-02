package dev.tlong.biodex.data.catalogue

import java.io.File

/**
 * The **shipped** Duke's asset, read from `app/src/main/assets/` by the tests that assert
 * things about its contents.
 *
 * This deliberately couples the suite to the catalogue pipeline's output, and the coupling is
 * the point. A hand-copied fixture of this data is a test that keeps passing after the thing it
 * describes stops being true — which is the failure this project hit twice in one day, once on
 * a species count that grew from 120 to 200 and once on an invariant that turned into a
 * statement of a bug when the schema moved under it. Nothing failed either time. Here, the
 * dataset is the one where being quietly wrong can hurt somebody, so a regeneration that breaks
 * an assumption should cost a re-run rather than a silence.
 *
 * The counterpart obligation is on the assertions: they name real species, because that is what
 * gives them teeth, but they assert the *shape* that matters — "found only through a synonym",
 * "carries a poison record and no medicinal tag" — wherever a hard number would fail a
 * legitimate recompaction for no reason.
 */
object RealDukeAsset {

    val path: File by lazy {
        // Unit tests run from `app/`, but a runner rooted at the repository is not worth a
        // mystery failure over.
        listOf("src/main/assets", "app/src/main/assets")
            .map { File("$it/$DUKE_ASSET_PATH") }
            .firstOrNull { it.isFile }
            ?: error("the shipped Duke's asset is missing; the catalogue pipeline must run first")
    }

    fun index(): DukeIndex = DukeIndex(assets = { path.inputStream() })

    fun taxa(): Map<String, DukeRecord> = parseDukeIndex(path.readText())
}
