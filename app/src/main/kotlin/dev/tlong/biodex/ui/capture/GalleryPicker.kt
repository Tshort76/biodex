package dev.tlong.biodex.ui.capture

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import dev.tlong.biodex.appContainer
import dev.tlong.biodex.data.photo.hasPhotoLibraryAccess
import dev.tlong.biodex.data.photo.photoLibraryPermissions

/**
 * D76/D78. The gallery door: the entry's Capture! button and the grid's ＋ both open it.
 * Returns the tap.
 *
 * Two rules. The grant is taken on the way in
 * (ARCHITECTURE.md 4.1 step 1), before anything else touches the URI. And the photo-library
 * permission is asked for on the first tap (D63), so the media-store original — GPS and all —
 * is readable behind the picker id; the picker opens whatever the answer.
 */
@Composable
fun rememberGalleryPicker(onPicked: (PickedPhoto) -> Unit): () -> Unit {
    val context = LocalContext.current
    val gateway = context.appContainer.photoGateway
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        gateway.persistGrant(uri.toString())
        onPicked(PickedPhoto(uri = uri.toString(), displayName = gateway.displayName(uri.toString())))
    }
    val openPicker = {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    val libraryThenPick = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _: Map<String, Boolean> -> openPicker() }
    return {
        if (hasPhotoLibraryAccess(context)) openPicker() else libraryThenPick.launch(photoLibraryPermissions())
    }
}
