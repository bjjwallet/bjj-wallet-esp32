package pub.airlock.ui

import androidx.compose.runtime.Composable

@Composable
internal actual fun BlockSystemBack(blocked: Boolean, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(enabled = blocked, onBack = onBack)
}
