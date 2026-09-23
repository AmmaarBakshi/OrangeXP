package com.orangexp.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** The OrangeXP palette: an ember orange on warm, near-black neutrals. */
internal object OxPalette {
    val Ember50 = Color(0xFFFFF3EA)
    val Ember100 = Color(0xFFFFE0CA)
    val Ember200 = Color(0xFFFDBB8C)
    val Ember300 = Color(0xFFFA944F)
    val Ember400 = Color(0xFFF77A2A)
    val Ember500 = Color(0xFFF2661B)
    val Ember600 = Color(0xFFD65212)
    val Ember700 = Color(0xFFA83E0E)
    val Ember800 = Color(0xFF6E2A0C)
    val Ember900 = Color(0xFF3A1808)

    val Ink950 = Color(0xFF0E0D0C)
    val Ink900 = Color(0xFF161412)
    val Ink850 = Color(0xFF1C1A17)
    val Ink800 = Color(0xFF24211E)
    val Ink700 = Color(0xFF34302B)
    val Ink500 = Color(0xFF6F675E)
    val Ink300 = Color(0xFFB3AAA0)
    val Ink100 = Color(0xFFEDE7E1)
    val Paper = Color(0xFFFBF8F5)
    val PaperDim = Color(0xFFF2EDE8)

    val Green = Color(0xFF34B36A)
    val Amber = Color(0xFFF29A2E)
    val Red = Color(0xFFE5484D)
    val Black = Color(0xFF000000)
}

/** Colors beyond Material's scheme: heatmap intensities and day-state tones. */
@Immutable
data class OxExtendedColors(
    /** Index 0 = no activity ... 5 = very high. */
    val heat: List<Color>,
    val stateGood: Color,
    val stateModerate: Color,
    val stateBad: Color,
    val stateCritical: Color,
    /** Outline that keeps the black state visible on dark surfaces. */
    val stateCriticalOutline: Color,
    val subtle: Color,
    val divider: Color,
)

internal val DarkExtendedColors = OxExtendedColors(
    heat = listOf(
        Color(0xFF26221F),
        Color(0xFF4A2814),
        Color(0xFF7D3813),
        Color(0xFFB44C12),
        Color(0xFFE8631A),
        Color(0xFFFF8F45),
    ),
    stateGood = OxPalette.Green,
    stateModerate = OxPalette.Amber,
    stateBad = OxPalette.Red,
    stateCritical = OxPalette.Black,
    stateCriticalOutline = OxPalette.Ink500,
    subtle = OxPalette.Ink300,
    divider = OxPalette.Ink800,
)

internal val LightExtendedColors = OxExtendedColors(
    heat = listOf(
        Color(0xFFEAE3DC),
        Color(0xFFFDD8BC),
        Color(0xFFFBB07C),
        Color(0xFFF5873F),
        Color(0xFFE2621A),
        Color(0xFFAF440B),
    ),
    stateGood = Color(0xFF218A4F),
    stateModerate = Color(0xFFD9800F),
    stateBad = Color(0xFFCC2F35),
    stateCritical = OxPalette.Black,
    stateCriticalOutline = OxPalette.Black,
    subtle = OxPalette.Ink500,
    divider = OxPalette.Ink100,
)

val LocalOxColors = staticCompositionLocalOf { DarkExtendedColors }
