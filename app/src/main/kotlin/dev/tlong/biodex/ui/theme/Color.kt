package dev.tlong.biodex.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The mockup's design tokens (ARCHITECTURE.md 6.4), one field per CSS custom property.
 * Bespoke components read these; Material components read the ColorScheme in Theme.kt,
 * which is mapped from the same values.
 */
@Immutable
data class BioDexColors(
    val bg: Color,
    val fg: Color,
    val muted: Color,
    val faint: Color,
    val rule: Color,
    val card: Color,
    val codeBg: Color,
    val accent: Color,
    val accentSoft: Color,
    val ok: Color,
    val warn: Color,
    val warnSoft: Color,
    val stop: Color,
    val stopSoft: Color,
    val sil: Color,
    val silBg: Color,
)

val LightDexColors = BioDexColors(
    bg = Color(0xFFFBFAF7),
    fg = Color(0xFF22282E),
    muted = Color(0xFF5D6670),
    faint = Color(0xFF8B939B),
    rule = Color(0xFFDCDFD9),
    card = Color(0xFFFFFFFF),
    codeBg = Color(0xFFEEF0EA),
    accent = Color(0xFF0E6E63),
    accentSoft = Color(0xFFE3EFEC),
    ok = Color(0xFF2F6B4F),
    warn = Color(0xFF9A6A1C),
    warnSoft = Color(0xFFF6ECD9),
    stop = Color(0xFF9C3A3A),
    stopSoft = Color(0xFFF6E6E4),
    sil = Color(0xFF3A4148),
    silBg = Color(0xFFE7E9E3),
)

val DarkDexColors = BioDexColors(
    bg = Color(0xFF171C21),
    fg = Color(0xFFD9DEE2),
    muted = Color(0xFF97A1AA),
    faint = Color(0xFF7C858E),
    rule = Color(0xFF323A41),
    card = Color(0xFF1E242A),
    codeBg = Color(0xFF232A30),
    accent = Color(0xFF4CBCAB),
    accentSoft = Color(0xFF1E2F2C),
    ok = Color(0xFF7FC6A0),
    warn = Color(0xFFD5A04A),
    warnSoft = Color(0xFF2E2718),
    stop = Color(0xFFE08B84),
    stopSoft = Color(0xFF33201E),
    sil = Color(0xFF11151A),
    silBg = Color(0xFF2B333B),
)

val LocalDexColors = staticCompositionLocalOf { LightDexColors }
