package com.hyundai.airlab.mirage

import com.google.protobuf.Descriptors
import com.google.protobuf.Message
import com.google.protobuf.TypeRegistry
import io.grpc.ClientInterceptor
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.CopyOnWriteArraySet

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
 * through and is captured. A mock whose JSON is a `$mirageError` envelope makes the RPC *fail*
 * instead — see [MirageErrorFactory] — and real failures are captured as ready-to-use envelopes
 * under `corpus/<key>.errors.jsonl`.
 */
object Mirage {

    val store: MirageMockStore = MirageMockStore()

    @Volatile
    var corpus: CorpusStore? = null
        private set

    /**
     * Descriptors used to resolve `Any` fields (`@type`) in mock JSON — both `$mirageError.details`
     * and any `Any` fields inside success-mock response bodies. Standard `google.rpc.*` detail
     * types are pre-registered when the host app ships them; app-specific ones are added via
     * [registerErrorDetailTypes].
     */
    @Volatile
    var typeRegistry: TypeRegistry = TypeRegistry.getEmptyTypeRegistry()
        private set

    private val registeredDescriptors = CopyOnWriteArraySet<Descriptors.Descriptor>()

    private val captureExecutor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mirage-capture").apply { isDaemon = true }
    }

    val interceptor: ClientInterceptor = MirageInterceptor(
        store = store,
        corpusProvider = { corpus },
        captureExecutor = captureExecutor,
        registryProvider = { typeRegistry },
    )

    init {
        // Best-effort: standard google.rpc error-detail types. Present whenever the host app uses
        // the rich error model (grpc-protobuf pulls proto-google-common-protos); harmless otherwise.
        runCatching {
            registerErrorDetailTypes(
                com.google.rpc.ErrorInfo.getDefaultInstance(),
                com.google.rpc.BadRequest.getDefaultInstance(),
                com.google.rpc.RetryInfo.getDefaultInstance(),
                com.google.rpc.PreconditionFailure.getDefaultInstance(),
                com.google.rpc.QuotaFailure.getDefaultInstance(),
                com.google.rpc.LocalizedMessage.getDefaultInstance(),
            )
        }
    }

    /** Point the store at [mockDir] (read per call) and enable corpus capture under [corpusDir]. */
    fun init(mockDir: File, corpusDir: File) {
        store.fileDir = mockDir
        corpus = CorpusStore(corpusDir)
    }

    /**
     * Registers the app's error-detail proto(s) so `$mirageError.details` (and `Any` fields in
     * success mocks) can be written as typed JSON with an `@type`. One debug-only line in the app:
     *
     * ```
     * if (BuildConfig.DEBUG) Mirage.registerErrorDetailTypes(ErrorInfo.getDefaultInstance())
     * ```
     *
     * Not needed for code-only error mocks or the `detailsBin` (base64 replay) form.
     */
    fun registerErrorDetailTypes(vararg messages: Message) {
        messages.forEach { registeredDescriptors.add(it.descriptorForType) }
        typeRegistry = TypeRegistry.newBuilder().add(registeredDescriptors).build()
    }
}
