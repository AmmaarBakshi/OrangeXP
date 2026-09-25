package com.orangexp.feature.holstrom.brain

/**
 * Cuts streamed text into whole sentences so speech can start before the
 * answer is finished. Feed deltas with [add]; call [rest] at the end.
 */
class SentenceChunker {
    private val buffer = StringBuilder()

    /** Complete sentences available after adding [delta]. */
    fun add(delta: String): List<String> {
        buffer.append(delta)
        val out = mutableListOf<String>()
        while (true) {
            val end = boundary() ?: break
            val sentence = buffer.substring(0, end).trim()
            buffer.delete(0, end)
            if (sentence.isNotEmpty()) out += sentence
        }
        return out
    }

    fun rest(): String = buffer.toString().trim().also { buffer.clear() }

    /** Index just after a sentence end followed by whitespace; decimals like "4.7" are not ends. */
    private fun boundary(): Int? {
        for (i in 0 until buffer.length - 1) {
            val c = buffer[i]
            val next = buffer[i + 1]
            if (c == '\n') return i + 1
            if ((c == '.' || c == '!' || c == '?') && next.isWhitespace() && i >= MIN_SENTENCE) return i + 1
        }
        return null
    }

    private companion object {
        /** Avoid speaking fragments such as "Dr." on their own. */
        const val MIN_SENTENCE = 3
    }
}
