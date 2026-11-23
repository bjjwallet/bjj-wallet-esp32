package pub.airlock.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import pub.airlock.domain.AppTheme
import pub.airlock.resources.*
import pub.airlock.resources.Res
import pub.airlock.resources.roboto_400
import pub.airlock.resources.roboto_500
import pub.airlock.resources.roboto_700

// Blue headers retain white foregrounds in both themes.
internal val AirLockHeaderBlue = Color(0xFF1976D2)
val AirLockBlue: Color
    @Composable get() = MaterialTheme.colorScheme.primary
val AirLockGreen: Color
    @Composable get() = MaterialTheme.colorScheme.secondary
val AirLockGrey: Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

private val LightColors =
    lightColorScheme(
        primary = AirLockHeaderBlue,
        onPrimary = Color.White,
        secondary = Color(0xFF00796B),
        onSecondary = Color.White,
        tertiary = Color(0xFF9C27B0),
        background = Color(0xFFF5F5F5),
        onBackground = Color(0xFF1A1C1E),
        surface = Color.White,
        onSurface = Color(0xFF1A1C1E),
        surfaceVariant = Color(0xFFF1F3F5),
        onSurfaceVariant = Color(0xFF616161),
        outline = Color(0xFF757575),
        outlineVariant = Color(0xFFE0E0E0),
        error = Color(0xFFC10015),
    )
private val DarkColors =
    darkColorScheme(
        primary = Color(0xFF90CAF9),
        onPrimary = Color(0xFF003258),
        primaryContainer = Color(0xFF00497D),
        onPrimaryContainer = Color(0xFFD1E4FF),
        secondary = Color(0xFF80CBC4),
        onSecondary = Color(0xFF003731),
        secondaryContainer = Color(0xFF005047),
        onSecondaryContainer = Color(0xFFB2DFDB),
        tertiary = Color(0xFFE1BEE7),
        background = Color(0xFF101418),
        onBackground = Color(0xFFE2E2E6),
        surface = Color(0xFF181C20),
        onSurface = Color(0xFFE2E2E6),
        surfaceVariant = Color(0xFF252B32),
        onSurfaceVariant = Color(0xFFC2C7CF),
        outline = Color(0xFF8C9199),
        outlineVariant = Color(0xFF42474E),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
    )
private val LocalDarkTheme = staticCompositionLocalOf { false }

internal val AppTheme.label: StringResource
    get() =
        when (this) {
            AppTheme.SYSTEM -> Res.string.theme_system
            AppTheme.LIGHT -> Res.string.theme_light
            AppTheme.DARK -> Res.string.theme_dark
        }

@Composable
internal fun AppLogo(modifier: Modifier = Modifier.size(96.dp)) {
    Image(
        painterResource(
            if (LocalDarkTheme.current) Res.drawable.airlock_logo_dark
            else Res.drawable.airlock_logo_light
        ),
        stringResource(Res.string.app_name),
        modifier,
    )
}

@Composable
fun AirLockTheme(theme: AppTheme = AppTheme.SYSTEM, content: @Composable () -> Unit) {
    val dark =
        when (theme) {
            AppTheme.SYSTEM -> isSystemInDarkTheme()
            AppTheme.LIGHT -> false
            AppTheme.DARK -> true
        }
    val roboto =
        FontFamily(
            Font(Res.font.roboto_400),
            Font(Res.font.roboto_500, FontWeight.Medium),
            Font(Res.font.roboto_700, FontWeight.Bold),
        )
    val typography = Typography()
    CompositionLocalProvider(LocalDarkTheme provides dark) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography =
                Typography(
                    displayLarge = typography.displayLarge.copy(fontFamily = roboto),
                    displayMedium = typography.displayMedium.copy(fontFamily = roboto),
                    displaySmall = typography.displaySmall.copy(fontFamily = roboto),
                    headlineLarge = typography.headlineLarge.copy(fontFamily = roboto),
                    headlineMedium = typography.headlineMedium.copy(fontFamily = roboto),
                    headlineSmall = typography.headlineSmall.copy(fontFamily = roboto),
                    titleLarge = typography.titleLarge.copy(fontFamily = roboto),
                    titleMedium = typography.titleMedium.copy(fontFamily = roboto),
                    titleSmall = typography.titleSmall.copy(fontFamily = roboto),
                    bodyLarge = typography.bodyLarge.copy(fontFamily = roboto),
                    bodyMedium = typography.bodyMedium.copy(fontFamily = roboto),
                    bodySmall = typography.bodySmall.copy(fontFamily = roboto),
                    labelLarge = typography.labelLarge.copy(fontFamily = roboto),
                    labelMedium = typography.labelMedium.copy(fontFamily = roboto),
                    labelSmall = typography.labelSmall.copy(fontFamily = roboto),
                ),
            content = content,
        )
    }
}
