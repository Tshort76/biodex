package dev.tlong.biodex.data.identify

import dev.tlong.biodex.data.net.FetchResult
import dev.tlong.biodex.domain.Kingdom
import dev.tlong.biodex.domain.SpeciesSource
import dev.tlong.biodex.domain.SpeciesSummary
import dev.tlong.biodex.domain.TaxClass
import dev.tlong.biodex.domain.storedDexNumber

/**
 * **These fixtures are constructed, not captured**, and that is the one thing about them worth
 * knowing. Every payload under `test/resources/net/` is a real response fetched from a live
 * API (see `Fixtures`); Pl@ntNet needs a key nobody has yet (R16), so the four Pl@ntNet bodies
 * here are written from the documented response shape instead.
 *
 * What that costs is precise: the *field names and nesting* are the contract this parser
 * assumes, and they are unverified until someone runs it with a real key. What it does not
 * cost is the logic — the drop rules, the outcome mapping and the catalogue match are driven
 * by real GBIF payloads either way, and those are where the failures the design worries about
 * actually live.
 *
 * The three GBIF bodies here are constructed too, for names the captured set has no response
 * for. They are modelled field-for-field on the real captures beside them.
 */
internal object IdentifyFixtures {

    fun read(name: String): String {
        val stream = checkNotNull(
            IdentifyFixtures::class.java.classLoader?.getResourceAsStream("identify/$name"),
        ) { "missing fixture identify/$name" }
        return stream.use { it.readBytes().decodeToString() }
    }

    /** The captured GBIF responses, which live with the rest of the real ones. */
    fun readNet(name: String): String {
        val stream = checkNotNull(
            IdentifyFixtures::class.java.classLoader?.getResourceAsStream("net/$name"),
        ) { "missing fixture net/$name" }
        return stream.use { it.readBytes().decodeToString() }
    }

    val someBytes = UploadImage(byteArrayOf(1, 2, 3))

    /**
     * A catalogue in the shape the resolver matches against: the real Oregon Grape row from
     * `pacific.json` (P048, *Mahonia aquifolium*) and the real Roosevelt Elk row (#072), whose
     * **trinomial** is what M33's two-token comparison exists for.
     */
    val catalogue = listOf(
        summary("p048", 48, "Oregon Grape", "Mahonia aquifolium", Kingdom.PLANT, TaxClass.SHRUB),
        summary(
            "a072", 72, "Roosevelt Elk", "Cervus canadensis roosevelti",
            Kingdom.ANIMAL, TaxClass.MAMMAL,
        ),
    )

    private fun summary(
        id: String,
        dexNumber: Int,
        commonName: String,
        scientificName: String,
        kingdom: Kingdom,
        taxClass: TaxClass,
    ) = SpeciesSummary(
        id = id,
        regionId = "pacific",
        dexNumber = storedDexNumber(kingdom, dexNumber),
        source = SpeciesSource.CURATED,
        detailsPending = false,
        commonName = commonName,
        scientificName = scientificName,
        taxClass = taxClass,
        kingdom = kingdom,
        silhouetteRes = "sil_shrub",
        ecosystemIds = emptyList(),
        caughtAt = null,
        thumbPath = null,
        captureCount = 0,
    )
}

/** An [IdentifyTransport] that answers with one fixed result and records what it was sent. */
internal class FakeTransport(private val result: FetchResult) : IdentifyTransport {

    var lastUrl: String? = null
    var lastImage: UploadImage? = null
    var calls: Int = 0

    override suspend fun post(url: String, image: UploadImage): FetchResult {
        calls++
        lastUrl = url
        lastImage = image
        return result
    }
}
