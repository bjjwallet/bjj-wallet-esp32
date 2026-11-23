package pub.airlock.domain

object HardwarePinBackoff {
    /** Third rejection: 10s, fourth: 20s. Saturate only to avoid integer overflow. */
    fun seconds(failures: Int): Long {
        if (failures < 3) return 0
        var result = 10L
        repeat((failures - 3).coerceAtMost(60)) {
            result = if (result > Long.MAX_VALUE / 2000) Long.MAX_VALUE / 2000 else result * 2
        }
        return result
    }
}
