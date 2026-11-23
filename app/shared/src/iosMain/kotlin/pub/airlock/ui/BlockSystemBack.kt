package pub.airlock.ui

import androidx.compose.runtime.Composable

@Composable
internal actual fun BlockSystemBack(blocked: Boolean, onBack: () -> Unit) {
    // Root Compose controller has no native back navigation.
}
