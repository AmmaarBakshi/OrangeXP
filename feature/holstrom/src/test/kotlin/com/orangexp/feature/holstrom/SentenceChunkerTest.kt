package com.orangexp.feature.holstrom

import com.orangexp.feature.holstrom.brain.SentenceChunker
import kotlin.test.Test
import kotlin.test.assertEquals

class SentenceChunkerTest {

    @Test
    fun `whole sentences come out as they complete`() {
        val chunker = SentenceChunker()
        assertEquals(emptyList(), chunker.add("You walked 4"))
        assertEquals(emptyList(), chunker.add(".7 km today"))
        assertEquals(listOf("You walked 4.7 km today."), chunker.add(". Nice"))
        assertEquals(listOf("Nice work!"), chunker.add(" work! "))
        assertEquals("Keep going", chunker.add("Keep going").let { chunker.rest() })
    }
}
