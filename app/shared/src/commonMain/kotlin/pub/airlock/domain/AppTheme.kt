package pub.airlock.domain

import kotlinx.serialization.Serializable

@Serializable
enum class AppTheme {
    SYSTEM,
    LIGHT,
    DARK,
}
