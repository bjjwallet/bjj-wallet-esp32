package pub.airlock.ui

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

@Composable
internal actual fun deviceLanguageTags(): List<String> {
    // Observe configuration changes and read the system's language order.
    LocalConfiguration.current
    val locales = Resources.getSystem().configuration.locales
    return List(locales.size()) { locales[it].toLanguageTag() }
}
