package dev.tlong.animaldex

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dev.tlong.animaldex.ui.nav.AnimalDexNavHost
import dev.tlong.animaldex.ui.theme.AnimalDexTheme
import dev.tlong.animaldex.ui.theme.DexTheme

/** The app's single activity (ARCHITECTURE.md 6.1); everything else is a route in the NavHost. */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AnimalDexTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DexTheme.colors.bg,
                ) {
                    AnimalDexNavHost()
                }
            }
        }
    }
}
