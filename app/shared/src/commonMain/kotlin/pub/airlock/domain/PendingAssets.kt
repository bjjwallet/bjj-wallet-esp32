package pub.airlock.domain

/** Catalog entries awaiting verified deployment metadata or a compatible account integration. */
object PendingAssets {
    val networks = setOf("monero")
    val tokens = emptyMap<String, List<String>>()
    val tokenIcons = emptyMap<String, String>()
}
