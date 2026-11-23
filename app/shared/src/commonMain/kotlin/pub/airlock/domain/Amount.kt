package pub.airlock.domain

/** Base-10 string arithmetic: chain amounts never pass through Float or Double. */
object Amount {
    fun atomic(value: String, decimals: Int): String {
        require(decimals in 0..30) { "Invalid decimals" }
        requireInput(
            value.length <= 128 && Regex("[0-9]+(\\.[0-9]+)?").matches(value),
            InputError.INVALID_AMOUNT,
        )
        val parts = value.split('.')
        val fraction = parts.getOrElse(1) { "" }
        requireInput(fraction.length <= decimals, InputError.DECIMAL_PLACES)
        return normalize(parts[0] + fraction.padEnd(decimals, '0'))
    }

    fun display(atomic: String, decimals: Int): String {
        require(decimals in 0..30 && atomic.isNotEmpty() && atomic.all { it in '0'..'9' })
        if (decimals == 0) return normalize(atomic)
        val padded = normalize(atomic).padStart(decimals + 1, '0')
        val fraction = padded.takeLast(decimals).trimEnd('0')
        return padded.dropLast(decimals) + if (fraction.isEmpty()) "" else ".$fraction"
    }

    fun compare(a: String, b: String): Int {
        val x = normalize(a)
        val y = normalize(b)
        return if (x.length != y.length) x.length.compareTo(y.length) else x.compareTo(y)
    }

    fun add(a: String, b: String): String {
        val width = maxOf(a.length, b.length)
        val x = normalize(a).padStart(width, '0')
        val y = normalize(b).padStart(width, '0')
        var carry = 0
        val result = CharArray(width + 1)
        for (i in width - 1 downTo 0) {
            val sum = x[i].digitToInt() + y[i].digitToInt() + carry
            result[i + 1] = '0' + sum % 10
            carry = sum / 10
        }
        result[0] = '0' + carry
        return normalize(result.concatToString())
    }

    fun multiply(a: String, b: String): String {
        val x = normalize(a)
        val y = normalize(b)
        var total = "0"
        for ((index, digit) in y.reversed().withIndex()) {
            var row = "0"
            repeat(digit.digitToInt()) { row = add(row, x) }
            total = add(total, row + "0".repeat(index))
        }
        return total
    }

    /** USD valuation only: rounded to cents, never used to construct a chain amount. */
    fun usdCents(balance: String, price: String): String {
        val scale = balance.substringAfter('.', "").length + price.substringAfter('.', "").length
        val product = multiply(balance.replace(".", ""), price.replace(".", ""))
        if (scale <= 2) return product + "0".repeat(2 - scale)
        val padded = product.padStart(scale + 1, '0')
        val cents = padded.dropLast(scale - 2)
        return if (padded[padded.length - scale + 2] >= '5') add(cents, "1") else normalize(cents)
    }

    fun usd(cents: String): String {
        val value = normalize(cents).padStart(3, '0')
        return value.dropLast(2).reversed().chunked(3).joinToString(",").reversed() +
            "." +
            value.takeLast(2)
    }

    fun subtract(a: String, b: String): String {
        requireInput(compare(a, b) >= 0, InputError.INSUFFICIENT_BALANCE)
        val x = normalize(a)
        val y = normalize(b).padStart(x.length, '0')
        var borrow = 0
        val result = CharArray(x.length)
        for (i in x.indices.reversed()) {
            var digit = (x[i] - '0') - (y[i] - '0') - borrow
            borrow = if (digit < 0) 1 else 0
            if (digit < 0) digit += 10
            result[i] = '0' + digit
        }
        return normalize(result.concatToString())
    }

    private fun normalize(value: String): String {
        require(value.isNotEmpty() && value.all { it in '0'..'9' }) { "Invalid atomic amount" }
        return value.trimStart('0').ifEmpty { "0" }
    }
}
