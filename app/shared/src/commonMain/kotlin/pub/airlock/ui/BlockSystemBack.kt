package pub.airlock.ui

import androidx.compose.runtime.Composable

@Composable internal expect fun BlockSystemBack(blocked: Boolean, onBack: () -> Unit = {})
