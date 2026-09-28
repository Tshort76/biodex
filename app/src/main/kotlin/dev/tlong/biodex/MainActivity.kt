package dev.tlong.biodex

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import dev.tlong.biodex.ui.nav.BioDexNavHost
import dev.tlong.biodex.ui.theme.BioDexTheme
import dev.tlong.biodex.ui.theme.DexTheme

/** The app's single activity (ARCHITECTURE.md 6.1); everything else is a route in the NavHost. */
class MainActivity : ComponentActivity() {

    /** D83: a photo shared in from another app, until the NavHost has opened it on Identify. */
    private val sharedPhotoUri = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A recreated activity has already opened its share; the back stack restores it.
        if (savedInstanceState == null) sharedPhotoUri.value = sharedImageIn(intent)
        setContent {
            BioDexTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DexTheme.colors.bg,
                ) {
                    BioDexNavHost(
                        sharedPhotoUri = sharedPhotoUri.value,
                        onSharedPhotoOpened = { sharedPhotoUri.value = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        sharedImageIn(intent)?.let { sharedPhotoUri.value = it }
    }

    private fun sharedImageIn(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND || intent.type?.startsWith("image/") != true) return null
        return IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.toString()
    }
}
