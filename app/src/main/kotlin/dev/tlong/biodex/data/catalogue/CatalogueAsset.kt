package dev.tlong.biodex.data.catalogue

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The bundled catalogue asset's shape, exactly as ARCHITECTURE.md 3.2 specifies it.
 *
 * The asset's `provenance` block is deliberately absent from these models: it exists in
 * the file for auditability and is never imported. `ignoreUnknownKeys` is what drops it,
 * and it also means a later pipeline can add fields without breaking an older app.
 */
@Serializable
data class CatalogueDocument(
    val catalogueVersion: Int,
    val regionId: String,
    val regionName: String,
    val ecosystems: List<CatalogueEcosystem> = emptyList(),
    val species: List<CatalogueSpecies> = emptyList(),
    /**
     * D34's shared map outline: one bit per grid cell, land set, base64. It is region-level
     * because it is the same world on every species' map — only the shading differs.
     *
     * Defaulted like everything else here, so an asset built before the range maps existed
     * still imports; the detail screen simply draws no map.
     */
    val landMask: String? = null,
    val rangeGridWidth: Int = 0,
    val rangeGridHeight: Int = 0,
)

@Serializable
data class CatalogueEcosystem(
    val id: String,
    val name: String,
    val sortOrder: Int,
)

@Serializable
data class CatalogueSpecies(
    val id: String,
    val dexNumber: Int,
    val commonName: String,
    val scientificName: String? = null,
    @SerialName("taxClass") val taxClass: String,
    /**
     * Absent in the v1 asset, which is 120 animals — hence the default. The importer pairs
     * it with [taxClass] and applies the per-kingdom dex-number base (ARCHITECTURE.md 11.1).
     */
    val kingdom: String = "animal",
    val ecosystemIds: List<String> = emptyList(),
    val habitatText: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val infoUrl: String? = null,
    val imageAttribution: String? = null,
    val silhouetteRes: String? = null,
    // The uses block (11.1). Every field defaults, so the v1 asset — which has none of
    // them — imports unchanged, and a later pipeline can add one more without a code change
    // here beyond the field itself.
    val uses: List<String> = emptyList(),
    val usesNote: String? = null,
    val medicinalActivities: List<String> = emptyList(),
    val medicinalRecordCount: Int = 0,
    val usesAttribution: String? = null,
    /**
     * D34: the grid cells to shade on this species' map, indexed
     * `row * rangeGridWidth + column` over the region's grid. Empty is a real answer — GBIF
     * holds no records for everything — and means the map is not drawn at all.
     */
    val rangeCells: List<Int> = emptyList(),
)

/** The asset path the app reads at runtime (ARCHITECTURE.md 3.2). */
const val CATALOGUE_ASSET_PATH = "catalogue/pacific.json"

val catalogueJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}
