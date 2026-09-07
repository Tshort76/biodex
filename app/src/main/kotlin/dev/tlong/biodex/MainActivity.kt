package dev.tlong.biodex

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dev.tlong.biodex.ui.nav.BioDexNavHost
import dev.tlong.biodex.ui.nav.ShareIntake
import dev.tlong.biodex.ui.nav.shareIntakeFrom
import dev.tlong.biodex.ui.theme.BioDexTheme
import dev.tlong.biodex.ui.theme.DexTheme

/** The app's single activity (ARCHITECTURE.md 6.1); everything else is a route in the NavHost. */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // M45. Read once, at creation: a share arrives as its own launch, so a second share is
        // a second activity rather than a new intent into this one.
        val intake = intent.shareIntake()
        setContent {
            BioDexTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DexTheme.colors.bg,
                ) {
                    BioDexNavHost(intake = intake)
                }
            }
        }
    }
}

/**
 * The Android half of M45: pull the two extras out of the intent and hand them to the rule that
 * decides what they mean. Everything judgemental lives in [shareIntakeFrom], which is ordinary
 * Kotlin the JVM suite drives; this function only knows how to open the envelope.
 */
private fun Intent.shareIntake(): ShareIntake? = shareIntakeFrom(
    action = action,
    streamUri = sharedStreamUri()?.toString(),
    text = getStringExtra(Intent.EXTRA_TEXT),
)

@Suppress("DEPRECATION")
private fun Intent.sharedStreamUri(): Uri? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        getParcelableExtra(Intent.EXTRA_STREAM)
    }
