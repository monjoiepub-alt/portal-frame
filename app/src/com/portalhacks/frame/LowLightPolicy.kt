package com.portalhacks.frame

/** Ambient light is an explicit opt-in, never an orientation/brightness decision. */
internal class LowLightPolicy {
    var clockOnly = false
        private set
    private var candidateSince: Long? = null

    fun sample(
        enabled: Boolean,
        lux: Float,
        now: Long,
    ): Boolean {
        if (!enabled || !lux.isFinite() || lux < 0) {
            reset()
            return false
        }
        val thresholdMet = if (clockOnly) lux >= BRIGHT_LUX else lux <= DARK_LUX
        if (!thresholdMet) {
            candidateSince = null
            return clockOnly
        }
        val since = candidateSince ?: now.also { candidateSince = it }
        val delay = if (clockOnly) BRIGHT_HOLD_MS else DARK_HOLD_MS
        if (now - since >= delay) {
            clockOnly = !clockOnly
            candidateSince = null
        }
        return clockOnly
    }

    fun reset() {
        clockOnly = false
        candidateSince = null
    }

    companion object {
        private const val DARK_LUX = 8f
        private const val BRIGHT_LUX = 25f
        private const val DARK_HOLD_MS = 15_000L
        private const val BRIGHT_HOLD_MS = 2_000L
    }
}
