package pub.airlock.data.monero

private val portableStorageHeader = byteArrayOf(1, 17, 1, 1, 1, 1, 2, 1, 1)
private const val fieldName = "max_block_count"
private const val unsignedLongType = 5
private const val stringType = 10

/** wallet2 sends a flat portable-storage object for getblocks.bin. */
internal fun capMoneroBlockBatch(request: ByteArray, limit: Int): ByteArray? {
    require(limit > 0)
    val countAt = portableStorageHeader.size
    if (
        request.size <= countAt ||
            !portableStorageHeader.indices.all { request[it] == portableStorageHeader[it] }
    )
        return null
    val countByte = request[countAt].toInt() and 0xff
    if (countByte and 3 != 0) return null
    val fields = countByte ushr 2
    var offset = countAt + 1
    var existing = -1
    repeat(fields) {
        if (offset >= request.size) return null
        val nameSize = request[offset++].toInt() and 0xff
        if (nameSize >= request.size - offset) return null
        val name = request.decodeToString(offset, offset + nameSize)
        offset += nameSize
        val type = request[offset++].toInt() and 0xff
        if (name == fieldName) {
            if (existing >= 0 || type != unsignedLongType) return null
            existing = offset
        }
        val size =
            when (type) {
                1,
                5,
                9 -> 8
                2,
                6 -> 4
                3,
                7 -> 2
                4,
                8,
                11 -> 1
                stringType -> {
                    if (offset >= request.size) return null
                    val width = 1 shl (request[offset].toInt() and 3)
                    if (width > 4 || width > request.size - offset) return null
                    var encodedLength = 0L
                    repeat(width) { index ->
                        encodedLength =
                            encodedLength or
                                ((request[offset + index].toLong() and 0xff) shl (index * 8))
                    }
                    offset += width
                    (encodedLength ushr 2).toInt()
                }
                else -> return null
            }
        if (size > request.size - offset) return null
        offset += size
    }
    if (offset != request.size) return null
    val limitBytes = ByteArray(8) { index -> (limit.toLong() ushr (index * 8)).toByte() }
    if (existing >= 0) {
        var value = 0uL
        repeat(8) { index ->
            value = value or (request[existing + index].toUByte().toULong() shl (index * 8))
        }
        return if (value in 1uL..limit.toULong()) request
        else request.copyOf().also { limitBytes.copyInto(it, existing) }
    }
    if (fields == 63) return null
    return (request +
            byteArrayOf(fieldName.length.toByte()) +
            fieldName.encodeToByteArray() +
            byteArrayOf(unsignedLongType.toByte()) +
            limitBytes)
        .also { it[countAt] = ((fields + 1) shl 2).toByte() }
}
