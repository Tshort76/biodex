package dev.tlong.animaldex.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

private fun materialScheme(c: AnimalDexColors, dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = c.accent,
        onPrimary = c.bg,
        primaryContainer = c.accentSoft,
        onPrimaryContainer = c.accent,
        secondary = c.warn,
        onSecondary = c.bg,
        secondaryContainer = c.warnSoft,
        onSecondaryContainer = c.warn,
        background = c.bg,
        onBackground = c.fg,
        surface = c.card,
        onSurface = c.fg,
        surfaceVariant = c.codeBg,
        onSurfaceVariant = c.muted,
        outline = c.rule,
        outlineVariant = c.rule,
        error = c.stop,
        onError = c.bg,
        errorContainer = c.stopSoft,
        onErrorContainer = c.stop,
    )
} else {
    lightColorScheme(
        primary = c.accent,
        onPrimary = c.card,
        primaryContainer = c.accentSoft,
        onPrimaryContainer = c.accent,
        secondary = c.warn,
        onSecondary = c.card,
        secondaryContainer = c.warnSoft,
        onSecondaryContainer = c.warn,
        background = c.bg,
        onBackground = c.fg,
        surface = c.card,
        onSurface = c.fg,
        surfaceVariant = c.codeBg,
        onSurfaceVariant = c.muted,
        outline = c.rule,
        outlineVariant = c.rule,
        error = c.stop,
        onError = c.card,
        errorContainer = c.stopSoft,
        onErrorContainer = c.stop,
    )
}

/**
 * Follows the system dark setting; there is no in-app toggle (ARCHITECTURE.md 6.4).
 * Dynamic color is deliberately not used — the mockup's palette is the identity.
 */
@Composable
fun AnimalDexTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val dexColors = if (darkTheme) DarkDexColors else LightDexColors
    CompositionLocalProvider(LocalDexColors provides dexColors) {
        MaterialTheme(
            colorScheme = materialScheme(dexColors, darkTheme),
            typography = DexTypography,
            content = content,
        )
    }
}

/** Shorthand for the bespoke token set: `DexTheme.colors.accent`. */
object DexTheme {
    val colors: AnimalDexColors
        @Composable get() = LocalDexColors.current
}
