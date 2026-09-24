package dev.tlong.biodex.ui.capture

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * D72/D78. Opens [photoUri] in Google Lens, or — on a phone without the Google app — the share
 * sheet Lens would otherwise be picked from. Lens returns nothing to the caller: the name comes
 * back through the clipboard (D78).
 */
fun openInLens(context: Context, photoUri: String) {
    try {
        context.startActivity(lensIntentFor(photoUri))
    } catch (_: ActivityNotFoundException) {
        context.startActivity(lensChooserFor(photoUri))
    }
}

/**
 * S06's Google Lens hand-off. There is no Lens-specific API worth using here: an ordinary
 * image share is what the user's actual workflow already does, and Lens is one of the targets
 * the chooser offers. `FLAG_GRANT_READ_URI_PERMISSION` is what lets the receiving app open a
 * URI this app only has a read grant on.
 */
private fun lensChooserFor(photoUri: String): Intent =
    Intent.createChooser(imageShare(photoUri), "Identify this photo")

/**
 * D72. The same share, addressed to Lens's own share target in the Google app, so the 🔍 opens
 * Lens rather than a sheet of contacts. Started directly rather than resolved first — resolving
 * another package needs a `<queries>` entry — so a phone without the Google app throws
 * `ActivityNotFoundException` and the caller falls back to [lensChooserFor].
 */
private fun lensIntentFor(photoUri: String): Intent =
    imageShare(photoUri).setClassName(
        "com.google.android.googlequicksearchbox",
        "com.google.android.apps.search.lens.LensShareEntryPointActivity",
    )

private fun imageShare(photoUri: String): Intent = Intent(Intent.ACTION_SEND).apply {
    type = "image/*"
    putExtra(Intent.EXTRA_STREAM, Uri.parse(photoUri))
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
