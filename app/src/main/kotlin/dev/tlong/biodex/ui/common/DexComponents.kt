package dev.tlong.biodex.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import dev.tlong.biodex.data.photo.ownedFileModel
import dev.tlong.biodex.domain.SpeciesSummary
import dev.tlong.biodex.ui.theme.DexTheme

// ---------------------------------------------------------------------------
// The component vocabulary of ARCHITECTURE.md 6.4, each element matching one
// piece of mockup.html's CSS. Nothing here reaches a repository or a ViewModel:
// every component takes plain values, which is what makes the previews cheap.
// ---------------------------------------------------------------------------

/** `.appbar .region` — the region name as an uppercase warn-on-warnSoft pill. */
@Composable
fun RegionPill(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 1.sp),
        color = DexTheme.colors.warn,
        modifier = modifier
            .clip(CircleShape)
            .background(DexTheme.colors.warnSoft)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/**
 * `.appbar .prog` — `47/120` on accentSoft, tabular so it does not jitter.
 *
 * Two of these sit side by side once a region has plants (M29), which is why the colour is a
 * parameter and the slash has no spaces around it: the animal pill is accent, the plant pill
 * is `ok` with a leaf, and the pair has to fit beside the title on a phone.
 */
@Composable
fun ProgressPill(
    caught: Int,
    total: Int,
    modifier: Modifier = Modifier,
    color: Color = DexTheme.colors.accent,
    glyph: String? = null,
) {
    Text(
        text = if (glyph == null) "$caught/$total" else "$glyph $caught/$total",
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
        maxLines = 1,
        color = color,
        modifier = modifier
            .clip(CircleShape)
            .background(DexTheme.colors.accentSoft)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/** `.chip` / `.chip.on` — outlined when off, accent on accentSoft when selected. */
@Composable
fun DexFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DexTheme.colors
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        color = if (selected) colors.accent else colors.muted,
        maxLines = 1,
        modifier = modifier
            .clip(CircleShape)
            .background(if (selected) colors.accentSoft else Color.Transparent)
            .border(1.dp, if (selected) colors.accent else colors.rule, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** `.sechd` — the small uppercase faint section header used all over the detail screen. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        ),
        color = DexTheme.colors.faint,
        modifier = modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

/** `.attr` — the fine print under the detail screen. */
@Composable
fun AttributionLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 14.sp),
        color = DexTheme.colors.faint,
        modifier = modifier,
    )
}

/**
 * `.cell` — one grid cell (M01). A caught species shows the species' reference picture (D39);
 * an uncaught one shows the class silhouette on `silBg`.
 *
 * The pictures a cell may try, and their order, come from [tileImageSources]: the reference
 * picture first, then the capture's **stored thumbnail** (M11) as the fallback for when the
 * reference has not cached. Neither is the gallery URI — the grid never resolves one, so a
 * broken photo reference stays a one-entry problem rather than a blank collection.
 *
 * When every candidate has failed — the phone is offline with nothing cached, or the thumbnail
 * file went missing because a database was restored without the files beside it — the cell
 * draws the silhouette **the same way the no-photo branch draws it**, sized and centred, rather
 * than through Coil's error slot. The error slot inherits the photo's `Crop` scaling, which
 * blew the silhouette up to fill the tile and made a caught species read as an enlarged
 * uncaught one. The chrome still says caught either way (M12): accent border, accent ground,
 * the tick, and an accent-tinted shape.
 */
@Composable
fun SpeciesCell(
    species: SpeciesSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DexTheme.colors
    val filesDir = LocalContext.current.filesDir
    val tileState = tileStateFor(species)
    val accented = tileWearsAccentChrome(tileState)
    // §5.3.1. The *chrome* is decided before and independently of any picture, which is what
    // makes the offline fallback keep saying "caught". The pictures are walked in D39's
    // order, advancing one place on each load failure until the list runs out.
    val sources = remember(species.imageUrl, species.thumbPath, species.caught) {
        tileImageSources(species)
    }
    var failedCount by remember(sources) { mutableStateOf(0) }
    val imageModel = sources.getOrNull(failedCount)?.let { source ->
        when (source) {
            is TileImage.Reference -> source.url
            is TileImage.OwnThumbnail -> ownedFileModel(filesDir, source.path)
        }
    }
    val imageFailed = imageModel == null
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (accented) colors.accentSoft else colors.card)
            .border(
                1.dp,
                if (accented) colors.accent else colors.rule,
                RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(74.dp)
                .background(if (accented) colors.accentSoft else colors.silBg),
            contentAlignment = Alignment.Center,
        ) {
            if (imageModel != null) {
                // Keyed on the model so a failure advances exactly one place: the next
                // candidate gets a fresh request rather than inheriting a failed painter.
                key(imageModel) {
                    AsyncImage(
                        model = imageModel,
                        contentDescription = null,
                        // Cropped, not fitted (D30 fits the hero): at 74dp a letterboxed
                        // photograph is a stripe, and a tile wants a picture.
                        contentScale = ContentScale.Crop,
                        onState = { state ->
                            if (state is AsyncImagePainter.State.Error) failedCount += 1
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                SilhouetteIcon(
                    silhouetteRes = species.silhouetteRes,
                    taxClass = species.taxClass,
                    size = 56.dp,
                    tint = if (species.caught) colors.accent else colors.sil,
                )
            }
            if (species.caught) {
                // M44. Filled and green when the cell is showing a shape, quiet when it is
                // showing a picture: the tick has to carry "caught" on its own exactly when
                // there is no photograph doing it.
                val loud = tileWearsLoudTick(
                    state = tileState,
                    showingSilhouette = imageFailed,
                )
                Text(
                    text = "✓",
                    style = if (loud) {
                        MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                    } else {
                        MaterialTheme.typography.labelSmall
                    },
                    // On the filled tick the ground is the accent green, so the mark itself
                    // takes the page colour — which keeps it legible in both themes, where a
                    // white one would vanish against the lighter green of the dark palette.
                    color = if (loud) colors.bg else colors.ok,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(if (loud) colors.ok else colors.accentSoft)
                        .padding(
                            horizontal = if (loud) 7.dp else 5.dp,
                            vertical = if (loud) 2.dp else 1.dp,
                        ),
                )
            }
        }
        Column(
            modifier = Modifier
                .background(if (accented) colors.accentSoft else colors.card)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            tileGlyph(tileState)?.let { glyph ->
                Text(
                    text = "$glyph $NO_OWN_PHOTO_MARK",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = colors.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = species.displayNumber,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = colors.faint,
            )
            Text(
                text = species.commonName,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = if (species.caught) colors.fg else colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** `.linkrow` — the outbound "Learn more" row; disabled-looking when there is no URL. */
@Composable
fun LinkRow(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DexTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, colors.rule, RoundedCornerShape(10.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = if (enabled) colors.accent else colors.faint,
        )
        Text(
            text = "↗",
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) colors.accent else colors.faint,
        )
    }
}

/** `.caughtchip` — `✓ Caught · Aug 30, 2026`. */
@Composable
fun CaughtChip(dateLabel: String, modifier: Modifier = Modifier) {
    Text(
        text = "✓ Caught · $dateLabel",
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = DexTheme.colors.ok,
        modifier = modifier
            .clip(CircleShape)
            .background(DexTheme.colors.accentSoft)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/** `.sci` — the italic scientific name under a species title. */
@Composable
fun ScientificName(name: String, modifier: Modifier = Modifier) {
    Text(
        text = name,
        style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
        color = DexTheme.colors.muted,
        modifier = modifier,
    )
}
