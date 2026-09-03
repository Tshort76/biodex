package dev.tlong.biodex.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import dev.tlong.biodex.R
import dev.tlong.biodex.domain.TaxClass
import dev.tlong.biodex.ui.theme.DexTheme

/**
 * Maps a catalogue `silhouetteRes` string onto a drawable id (ARCHITECTURE.md 3.1: "resolved
 * with getIdentifier once, cached"). v1 ships one silhouette per taxonomic class; a future
 * per-species drawable only has to exist under the name the asset already carries.
 *
 * A name that resolves to nothing falls back to the species' class silhouette rather than
 * throwing, so a bad asset string degrades to a generic shape instead of an empty grid.
 */
object Silhouettes {

    private val byClass = mapOf(
        TaxClass.BIRD to R.drawable.sil_bird,
        TaxClass.MAMMAL to R.drawable.sil_mammal,
        TaxClass.REPTILE to R.drawable.sil_reptile,
        TaxClass.AMPHIBIAN to R.drawable.sil_amphibian,
        TaxClass.FISH to R.drawable.sil_fish,
        TaxClass.INSECT to R.drawable.sil_insect,
        TaxClass.OTHER_INVERTEBRATE to R.drawable.sil_other_invertebrate,
        // The class fallback for a tree is the broadleaf shape; the conifer one is chosen
        // per species by the pipeline, which writes `sil_tree_conifer` into the asset's
        // `silhouetteRes` and reaches this map only when that name fails to resolve (11.4).
        TaxClass.TREE to R.drawable.sil_tree_broadleaf,
        TaxClass.SHRUB to R.drawable.sil_shrub,
        TaxClass.HERB to R.drawable.sil_herb,
        TaxClass.FERN to R.drawable.sil_fern,
        // The three fungal growth forms. Unlike the trees, no fungal class picks between
        // two shapes per species, so the pipeline writes `sil_<taxClass>` and this map and
        // that string always agree.
        TaxClass.MUSHROOM to R.drawable.sil_mushroom,
        TaxClass.BRACKET to R.drawable.sil_bracket,
        TaxClass.OTHER_FUNGUS to R.drawable.sil_other_fungus,
    )

    private val resolved = mutableMapOf<String, Int>()

    fun forClass(taxClass: TaxClass): Int =
        byClass[taxClass] ?: R.drawable.sil_other_invertebrate

    fun resolve(context: android.content.Context, name: String, taxClass: TaxClass): Int {
        val cached = resolved[name]
        if (cached != null && cached != 0) return cached
        @Suppress("DiscouragedApi")
        val id = context.resources.getIdentifier(name, "drawable", context.packageName)
        if (id != 0) {
            resolved[name] = id
            return id
        }
        return forClass(taxClass)
    }
}

/**
 * The silhouette treatment of 6.4: the class shape tinted [tint] and centred in its art area.
 * Callers own the background — `silBg` for an uncaught cell, a photo behind it once slice 5
 * renders thumbnails.
 */
@Composable
fun SilhouetteIcon(
    silhouetteRes: String,
    taxClass: TaxClass,
    size: Dp,
    modifier: Modifier = Modifier,
    tint: Color = DexTheme.colors.sil,
) {
    val context = LocalContext.current
    val drawableId = remember(silhouetteRes, taxClass) {
        Silhouettes.resolve(context, silhouetteRes, taxClass)
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Icon(
            painter = painterResource(drawableId),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size),
        )
    }
}
