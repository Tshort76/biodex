package dev.tlong.biodex.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import dev.tlong.biodex.domain.RangeGrid
import dev.tlong.biodex.ui.theme.DexTheme

/**
 * D34's range mini-map: a flat world, the same one on every species, with the cells where
 * GBIF holds records of this species picked out in the accent.
 *
 * The outline and the shading are the same grid, which is the whole design — a cell index
 * means one patch of the world, and the only thing that differs between two species is which
 * cells are lit. That is why there is no vector map here and no per-species image: the map is
 * 1KB of bitmask shared by the region, and a species costs a list of small integers.
 *
 * **What the shading means, and what it does not.** It is where the species has been
 * *recorded*, not a range polygon from a field guide. Somewhere nobody goes is blank whether
 * or not the animal lives there, and the caption says so rather than letting the picture
 * imply a precision it does not have.
 *
 * Drawn cell by cell rather than as a path: at 128x64 that is 8192 rectangles of which about
 * a third are land, once per frame, on a static screen — cheaper than building a path, and it
 * keeps the land and the range on exactly the same pixel edges, which a traced outline would
 * not.
 */
@Composable
fun RangeMap(
    grid: RangeGrid,
    cells: Set<Int>,
    modifier: Modifier = Modifier,
) {
    if (!grid.isUsable) return
    val colors = DexTheme.colors
    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                // The grid is equirectangular, so the frame has to be 2:1 or the world is
                // stretched. Every other aspect ratio is a different projection by accident.
                .aspectRatio(grid.width.toFloat() / grid.height.toFloat())
                .clip(RoundedCornerShape(10.dp))
                .background(colors.codeBg),
        ) {
            val cellWidth = size.width / grid.width
            val cellHeight = size.height / grid.height
            // A hair over one cell, so neighbours meet instead of leaving seams between them
            // at fractional pixel sizes.
            val patch = Size(cellWidth + 0.5f, cellHeight + 0.5f)
            for (row in 0 until grid.height) {
                for (column in 0 until grid.width) {
                    val cell = row * grid.width + column
                    val lit = cell in cells
                    if (!lit && !grid.land[cell]) continue
                    drawRect(
                        color = if (lit) colors.accent else colors.sil,
                        topLeft = Offset(column * cellWidth, row * cellHeight),
                        size = patch,
                        alpha = if (lit) 1f else 0.35f,
                    )
                }
            }
        }
        Text(
            text = "Where this species has been recorded · GBIF",
            style = MaterialTheme.typography.labelSmall,
            color = colors.faint,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
