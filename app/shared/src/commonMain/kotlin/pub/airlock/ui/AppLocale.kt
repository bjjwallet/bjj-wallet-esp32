@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
@file:OptIn(org.jetbrains.compose.resources.InternalResourceApi::class)

package pub.airlock.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import org.jetbrains.compose.resources.ComposeEnvironment
import org.jetbrains.compose.resources.DensityQualifier
import org.jetbrains.compose.resources.LanguageQualifier
import org.jetbrains.compose.resources.LocalComposeEnvironment
import org.jetbrains.compose.resources.RegionQualifier
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.ThemeQualifier
import pub.airlock.domain.AppLanguage

internal val LocalAppLanguage = staticCompositionLocalOf { AppLanguage.ENGLISH }

@Composable internal expect fun deviceLanguageTags(): List<String>

@Composable
internal fun ProvideAppLanguage(selection: String?, content: @Composable () -> Unit) {
    val language = AppLanguage.resolve(selection, deviceLanguageTags())
    // Compose Resources 1.10 has no public locale override. Scope its resource environment
    // to the app so a language change does not depend on Foundation's cached preferences.
    val environment =
        remember(language) {
            object : ComposeEnvironment {
                @Composable
                override fun rememberEnvironment(): ResourceEnvironment {
                    val dark = isSystemInDarkTheme()
                    val density = LocalDensity.current.density
                    return remember(language, dark, density) {
                        ResourceEnvironment(
                            LanguageQualifier(language.code),
                            RegionQualifier(""),
                            ThemeQualifier.selectByValue(dark),
                            DensityQualifier.selectByDensity(density),
                        )
                    }
                }
            }
        }
    CompositionLocalProvider(
        LocalAppLanguage provides language,
        LocalComposeEnvironment provides environment,
        content = content,
    )
}
