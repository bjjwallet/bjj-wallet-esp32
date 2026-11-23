package pub.airlock.data.network

/** RFC 7693, unkeyed BLAKE2b limited to Cardano's 224- and 256-bit public hashes. */
@OptIn(ExperimentalUnsignedTypes::class)
internal object CardanoBlake2b {
    private val iv =
        ulongArrayOf(
            0x6a09e667f3bcc908u,
            0xbb67ae8584caa73bu,
            0x3c6ef372fe94f82bu,
            0xa54ff53a5f1d36f1u,
            0x510e527fade682d1u,
            0x9b05688c2b3e6c1fu,
            0x1f83d9abfb41bd6bu,
            0x5be0cd19137e2179u,
        )
    private val permutations =
        listOf(
                "0123456789abcdef",
                "ea489fd61c02b753",
                "b8c052fdae367194",
                "7931dcbe265a40f8",
                "905724afe1bc683d",
                "2c6a0b834d75fe19",
                "c51fed4a0763928b",
                "db7ec13950f4862a",
                "6fe9b308c2d714a5",
                "a2847615fb9e3cd0",
            )
            .map { row -> row.map { it.digitToInt(16) } }

    fun hash(data: ByteArray, size: Int): ByteArray {
        require(size == 28 || size == 32)
        val state = iv.copyOf()
        state[0] = state[0] xor (0x01010000uL or size.toULong())
        var offset = 0
        do {
            val length = minOf(data.size - offset, 128)
            val block = ULongArray(16)
            for (i in 0 until length) block[i / 8] =
                block[i / 8] or ((data[offset + i].toInt() and 255).toULong() shl (8 * (i % 8)))
            offset += length
            val work = state + iv
            work[12] = work[12] xor offset.toULong()
            if (offset == data.size) work[14] = work[14].inv()
            fun mix(a: Int, b: Int, c: Int, d: Int, x: ULong, y: ULong) {
                work[a] += work[b] + x
                work[d] = (work[d] xor work[a]).rotateRight(32)
                work[c] += work[d]
                work[b] = (work[b] xor work[c]).rotateRight(24)
                work[a] += work[b] + y
                work[d] = (work[d] xor work[a]).rotateRight(16)
                work[c] += work[d]
                work[b] = (work[b] xor work[c]).rotateRight(63)
            }
            repeat(12) { round ->
                val s = permutations[round % permutations.size]
                mix(0, 4, 8, 12, block[s[0]], block[s[1]])
                mix(1, 5, 9, 13, block[s[2]], block[s[3]])
                mix(2, 6, 10, 14, block[s[4]], block[s[5]])
                mix(3, 7, 11, 15, block[s[6]], block[s[7]])
                mix(0, 5, 10, 15, block[s[8]], block[s[9]])
                mix(1, 6, 11, 12, block[s[10]], block[s[11]])
                mix(2, 7, 8, 13, block[s[12]], block[s[13]])
                mix(3, 4, 9, 14, block[s[14]], block[s[15]])
            }
            for (i in state.indices) state[i] = state[i] xor work[i] xor work[i + 8]
        } while (offset < data.size)
        return ByteArray(size) { i -> (state[i / 8] shr (8 * (i % 8))).toByte() }
    }
}
