package com.volleyballlab.player.pose.review

/** AOSP ColorUtils also encodes defined primaries/matrix pairs beyond SDK enums. */
internal object SdrColorStandard {
    fun bt709(standard: Int): Boolean? = when (standard) {
        1 -> true
        2, 4 -> false
        in 64..112 -> {
            // ColorUtils: 64 + primaries + matrix * (PrimariesBT2020 + 1).
            // Unspecified/709/601 primaries are supported; other gamuts need conversion.
            val pair = standard - 64
            if (pair % 7 !in listOf(0, 1, 3, 4)) null else when (pair / 7) {
                1 -> true
                3 -> false
                else -> null
            }
        }
        else -> null
    }
}
