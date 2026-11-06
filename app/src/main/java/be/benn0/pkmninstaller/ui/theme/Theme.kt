package be.benn0.pkmninstaller.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * Pokémon-themed Material 3 colour scheme (light).
 *
 * Uses the classic Pokéball red as the primary colour, Pikachu yellow as the
 * secondary, and the Pokémon-logo blue as the tertiary accent, on a warm cream
 * background.
 */
private val PokeLightColorScheme = lightColorScheme(
    primary = PokeRed,
    onPrimary = PokeSurface,
    primaryContainer = PokeRedDark,
    onPrimaryContainer = PokeSurface,
    secondary = PokeYellow,
    onSecondary = PokeInk,
    secondaryContainer = PokeYellowDark,
    onSecondaryContainer = PokeInk,
    tertiary = PokeBlue,
    onTertiary = PokeSurface,
    tertiaryContainer = PokeBlueDark,
    onTertiaryContainer = PokeSurface,
    background = PokeCream,
    onBackground = PokeInk,
    surface = PokeSurface,
    onSurface = PokeInk,
    surfaceVariant = PokeSurfaceVariant,
    onSurfaceVariant = PokeInk,
    outline = PokeOutline,
    error = PokeRedDark,
    onError = PokeSurface,
)

/**
 * Pokémon-themed Material 3 colour scheme (dark).
 *
 * Keeps the same red/yellow/blue identity but on warm, low-luminance neutrals
 * and with lighter accents so text stays readable.
 */
private val PokeDarkColorScheme = darkColorScheme(
    primary = PokeRedLight,
    onPrimary = PokeNight,
    primaryContainer = PokeRedDark,
    onPrimaryContainer = PokeInkLight,
    secondary = PokeYellowLight,
    onSecondary = PokeNight,
    secondaryContainer = PokeYellowDark,
    onSecondaryContainer = PokeNight,
    tertiary = PokeBlueLight,
    onTertiary = PokeNight,
    tertiaryContainer = PokeBlueDark,
    onTertiaryContainer = PokeInkLight,
    background = PokeNight,
    onBackground = PokeInkLight,
    surface = PokeSurfaceNight,
    onSurface = PokeInkLight,
    surfaceVariant = PokeSurfaceVariantNight,
    onSurfaceVariant = PokeInkLight,
    outline = PokeOutlineNight,
    error = PokeRedLight,
    onError = PokeNight,
)

/**
 * Wraps [content] in the Pokémon-themed Material 3 theme.
 *
 * The colour scheme follows the system light/dark setting.
 */
@Composable
fun PkmnInstallerTheme(content: @Composable () -> Unit) {
    val colorScheme = if (isSystemInDarkTheme()) PokeDarkColorScheme else PokeLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
