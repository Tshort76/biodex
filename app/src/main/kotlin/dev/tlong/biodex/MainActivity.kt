package dev.tlong.biodex

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dev.tlong.biodex.ui.nav.BioDexNavHost
import dev.tlong.biodex.ui.theme.BioDexTheme
import dev.tlong.biodex.ui.theme.DexTheme

/** The app's single activity (ARCHITECTURE.md 6.1); everything else is a route in the NavHost. */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            BioDexTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DexTheme.colors.bg,
                ) {
                    BioDexNavHost()
                }
            }
        }
    }
}
