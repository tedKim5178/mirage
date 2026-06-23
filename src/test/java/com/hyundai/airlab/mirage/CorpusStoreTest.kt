package com.hyundai.airlab.mirage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CorpusStoreTest {

    private val key = "ridergwv1.RiderGw/ListCards"

    @Test
    fun `records and returns a sample`(@TempDir dir: File) {
        val store = CorpusStore(dir)

        store.record(key, """{"cards":[]}""")

        assertEquals(listOf("""{"cards":[]}"""), store.samplesFor(key))
    }

    @Test
    fun `keeps only the most recent N distinct samples`(@TempDir dir: File) {
        val store = CorpusStore(dir, maxPerEndpoint = 3)

        listOf("""{"a":1}""", """{"a":2}""", """{"a":3}""", """{"a":4}""").forEach { store.record(key, it) }

        val samples = store.samplesFor(key, limit = 10)
        assertEquals(3, samples.size)
        assertTrue("""{"a":1}""" !in samples) // oldest dropped
    }

    @Test
    fun `deduplicates byte-identical samples`(@TempDir dir: File) {
        val store = CorpusStore(dir)

        store.record(key, """{"a":1}""")
        store.record(key, """{"a":1}""")

        assertEquals(1, store.samplesFor(key, limit = 10).size)
    }

    @Test
    fun `skips samples larger than the size cap`(@TempDir dir: File) {
        val store = CorpusStore(dir, maxSampleBytes = 10)

        store.record(key, "x".repeat(100))

        assertTrue(store.samplesFor(key, limit = 10).isEmpty())
    }

    @Test
    fun `samplesFor returns at most the requested limit, newest first`(@TempDir dir: File) {
        val store = CorpusStore(dir)

        store.record(key, """{"a":1}""")
        store.record(key, """{"a":2}""")
        store.record(key, """{"a":3}""")

        assertEquals(listOf("""{"a":3}""", """{"a":2}"""), store.samplesFor(key, limit = 2))
    }
}
