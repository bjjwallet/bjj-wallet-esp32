package pub.airlock.domain

import io.ktor.http.decodeURLQueryComponent

/**
 * Extract only the recipient. Amounts and other optional payment metadata never change the draft.
 */
object RecipientQr {
    fun address(payload: String, coin: String): String? {
        val value = payload.trim()
        if (value.length !in 14..4096 || value.any { it.isWhitespace() || it.code < 32 })
            return null
        val separator = value.indexOf(':')
        if (separator < 0) return value.takeIf(::plainAddress)
        val scheme = value.substring(0, separator).lowercase()
        val expected =
            when (coin) {
                "ecash" -> "ecash"
                else -> coin
            }
        // Complex payment requests (contract calls, URLs, lightning invoices) are not addresses.
        if (scheme != expected) return null
        val body = value.substring(separator + 1)
        if (body.startsWith("//") || '#' in body) return null
        val query = body.substringAfter('?', "")
        if (
            query.split('&').any { field ->
                runCatching {
                        field
                            .substringBefore('=')
                            .decodeURLQueryComponent()
                            .startsWith("req-", ignoreCase = true)
                    }
                    .getOrDefault(true)
            }
        )
            return null
        val address = body.substringBefore('?')
        if (!plainAddress(address)) return null
        return if (scheme in setOf("bitcoincash", "ecash")) "$scheme:$address" else address
    }

    private fun plainAddress(value: String) =
        value.length in 14..256 &&
            value.all {
                it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-'
            }
}
