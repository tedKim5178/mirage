package com.hyundai.airlab.mirage

import java.io.File

/**
 * Stores real response samples ("corpus") captured during normal use, keyed by endpoint, so the
 * mock generator can ground its output on real data instead of hallucinating from a schema alone.
 *
 * Backed by one JSONL file per endpoint ([dir]/`<key with / -> __>.jsonl`), one compact JSON
 * sample per line. Keeps the [maxPerEndpoint] most recent distinct samples and skips any single
 * sample larger than [maxSampleBytes].
 */
class CorpusStore(
    private val dir: File,
    private val maxPerEndpoint: Int = 5,
    private val maxSampleBytes: Int = 64 * 1024,
) {

    fun record(endpointKey: String, json: String) {
        if (json.toByteArray(Charsets.UTF_8).size > maxSampleBytes) return

        val file = fileFor(endpointKey)
        val existing = readSamples(file)
        if (json in existing) return // byte-identical dedup

        val updated = (existing + json).takeLast(maxPerEndpoint)
        dir.mkdirs()
        file.writeText(updated.joinToString("\n"))
    }

    fun samplesFor(endpointKey: String, limit: Int = 3): List<String> =
        readSamples(fileFor(endpointKey)).takeLast(limit).reversed() // most recent first

    private fun fileFor(endpointKey: String): File =
        File(dir, endpointKey.replace("/", "__") + ".jsonl")

    private fun readSamples(file: File): List<String> =
        if (file.exists()) file.readLines().filter { it.isNotEmpty() } else emptyList()
}
