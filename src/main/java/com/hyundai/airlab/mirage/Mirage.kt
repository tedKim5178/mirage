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
 * Call [init] once at app startup (debug only) to enable corpus capture under [corpusDir]. Mocks are
 * injected at runtime over the debug HTTP control server (in memory); a mock present short-circuits
 * the real server, while everything else passes through and is captured for grounding.
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

    /** Enable corpus capture under [corpusDir]. */
    fun init(corpusDir: File) {
        corpus = CorpusStore(corpusDir)
    }
}
