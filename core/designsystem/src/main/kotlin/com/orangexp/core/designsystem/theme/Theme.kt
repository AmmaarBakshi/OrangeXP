package com.orangexp.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.dp

private val DarkScheme = darkColorScheme(
    primary = OxPalette.Ember500,
    onPrimary = OxPalette.Ink950,
    primaryContainer = OxPalette.Ember800,
    onPrimaryContainer = OxPalette.Ember100,
    secondary = OxPalette.Ember300,
    onSecondary = OxPalette.Ink950,
    secondaryContainer = OxPalette.Ember900,
    onSecondaryContainer = OxPalette.Ember100,
    tertiary = OxPalette.Ember200,
    tertiaryContainer = OxPalette.Ember800,
    onTertiaryContainer = OxPalette.Ember50,
    background = OxPalette.Ink950,
    onBackground = OxPalette.Ink100,
    surface = OxPalette.Ink950,
    onSurface = OxPalette.Ink100,
    surfaceVariant = OxPalette.Ink850,
    onSurfaceVariant = OxPalette.Ink300,
    surfaceContainerLowest = OxPalette.Ink950,
    surfaceContainerLow = OxPalette.Ink900,
    surfaceContainer = OxPalette.Ink850,
    surfaceContainerHigh = OxPalette.Ink800,
    surfaceContainerHighest = OxPalette.Ink700,
    outline = OxPalette.Ink700,
    outlineVariant = OxPalette.Ink800,
    error = OxPalette.Red,
)

private val LightScheme = lightColorScheme(
    primary = OxPalette.Ember600,
    onPrimary = OxPalette.Paper,
    primaryContainer = OxPalette.Ember100,
    onPrimaryContainer = OxPalette.Ember900,
    secondary = OxPalette.Ember700,
    secondaryContainer = OxPalette.Ember100,
    onSecondaryContainer = OxPalette.Ember900,
    tertiary = OxPalette.Ember400,
    tertiaryContainer = OxPalette.Ember50,
    onTertiaryContainer = OxPalette.Ember800,
    background = OxPalette.Paper,
    onBackground = OxPalette.Ink950,
    surface = OxPalette.Paper,
    onSurface = OxPalette.Ink950,
    surfaceVariant = OxPalette.PaperDim,
    onSurfaceVariant = OxPalette.Ink500,
    surfaceContainerLowest = OxPalette.Paper,
    surfaceContainerLow = OxPalette.Ember50,
    surfaceContainer = OxPalette.PaperDim,
    surfaceContainerHigh = OxPalette.Ink100,
    surfaceContainerHighest = OxPalette.Ink100,
    outline = OxPalette.Ink300,
    outlineVariant = OxPalette.Ink100,
    error = OxPalette.Red,
)

private val OxShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * OrangeXP's theme. Dark is the signature look; light follows the system setting.
 * Dynamic color is intentionally not used: orange is the identity.
 */
@Composable
fun OrangeXpTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalOxColors provides if (darkTheme) DarkExtendedColors else LightExtendedColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = OxTypography,
            shapes = OxShapes,
            content = content,
        )
    }
}

object OxTheme {
    val colors: OxExtendedColors
        @Composable @ReadOnlyComposable get() = LocalOxColors.current
}
