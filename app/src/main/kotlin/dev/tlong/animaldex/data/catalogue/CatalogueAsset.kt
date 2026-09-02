package dev.tlong.animaldex.data.catalogue

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
    val ecosystemIds: List<String> = emptyList(),
    val habitatText: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val callUrl: String? = null,
    val infoUrl: String? = null,
    val imageAttribution: String? = null,
    val callAttribution: String? = null,
    val silhouetteRes: String? = null,
)

/** The asset path the app reads at runtime (ARCHITECTURE.md 3.2). */
const val CATALOGUE_ASSET_PATH = "catalogue/pacific.json"

val catalogueJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}
