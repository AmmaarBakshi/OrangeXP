package com.orangexp.core.data.tracking

/**
 * The hardware counter reports cumulative steps since boot. We sample it and
 * attribute each positive difference to the day of the later sample.
 */
internal object StepAccumulator {

    data class Baseline(val counter: Long, val timestampMs: Long) {
        fun encode(): String = "$counter:$timestampMs"

        companion object {
            fun decode(value: String?): Baseline? {
                val parts = value?.split(':') ?: return null
                if (parts.size != 2) return null
                val counter = parts[0].toLongOrNull() ?: return null
                val timestamp = parts[1].toLongOrNull() ?: return null
                return Baseline(counter, timestamp)
            }
        }
    }

    /**
     * Steps taken since [previous]. A counter lower than the baseline means the
     * device rebooted and the counter restarted from zero.
     */
    fun delta(previous: Baseline?, counter: Long): Long = when {
        previous == null -> 0
        counter >= previous.counter -> counter - previous.counter
        else -> counter
    }
}
