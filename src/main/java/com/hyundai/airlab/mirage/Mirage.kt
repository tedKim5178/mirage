package com.hyundai.airlab.mirage

import io.grpc.ClientInterceptor
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Entry point for the Mirage mock engine.
 *
 * Integration is a single line at channel-build time, guarded to debug builds:
 *
 * ```
 * channelBuilder.apply { if (BuildConfig.DEBUG) intercept(Mirage.interceptor) }
 * ```
 *
 * Call [init] once at app startup (debug only): mocks are then read fresh from [mockDir] on each
 * call (edit a file → applies from the next RPC, no restart), and real responses are captured into
 * [corpusDir] for grounding. A mock present short-circuits the real server; everything else passes
 * through and is captured.
 */
object Mirage {

    val store: MirageMockStore = MirageMockStore()

    @Volatile
    var corpus: CorpusStore? = null
        private set

    private val captureExecutor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mirage-capture").apply { isDaemon = true }
    }

    val interceptor: ClientInterceptor = MirageInterceptor(
        store = store,
        corpusProvider = { corpus },
        captureExecutor = captureExecutor,
    )

    /** Point the store at [mockDir] (read per call) and enable corpus capture under [corpusDir]. */
    fun init(mockDir: File, corpusDir: File) {
        store.fileDir = mockDir
        corpus = CorpusStore(corpusDir)
    }
}
