package pub.airlock.ui

import androidx.compose.runtime.Composable
import platform.Foundation.NSLocale
import platform.Foundation.preferredLanguages

@Composable
internal actual fun deviceLanguageTags(): List<String> =
    NSLocale.preferredLanguages.filterIsInstance<String>()
