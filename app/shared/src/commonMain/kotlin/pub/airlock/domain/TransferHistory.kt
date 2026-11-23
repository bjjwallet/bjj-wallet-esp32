package pub.airlock.domain

object TransferHistory {
    private fun key(tx: Transfer): Pair<String, String> =
        tx.assetId to
            (Regex("(?:0x)?[a-fA-F0-9]{64}").find(tx.id)?.value?.removePrefix("0x")?.lowercase()
                ?: tx.id)

    /** Chain status wins, while the reviewed outgoing amount and destination stay stable. */
    fun merge(local: List<Transfer>, remote: List<Transfer>): List<Transfer> {
        val stored = local.associateBy(::key)
        val indexed = remote.distinctBy(::key)
        val keys = indexed.map(::key).toSet()
        return local.filterNot { key(it) in keys }.distinctBy(::key) +
            indexed.map { tx ->
                stored[key(tx)]?.takeIf { !it.incoming }?.copy(status = tx.status) ?: tx
            }
    }
}
