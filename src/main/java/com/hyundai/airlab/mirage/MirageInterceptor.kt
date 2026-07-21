package com.hyundai.airlab.mirage

import com.google.protobuf.Message
import com.google.protobuf.TypeRegistry
import com.google.protobuf.util.JsonFormat
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall
import io.grpc.ForwardingClientCallListener
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import timber.log.Timber
import java.util.concurrent.Executor

/**
 * Short-circuits a **unary** RPC that has an active mock in [store]: a normal mock JSON is returned
 * as the response without touching the real server, while a `$mirageError` envelope (see
 * [MirageErrorFactory]) fails the call with the given status + rich error trailers — so error UI
 * can be exercised too. Server-streaming RPCs are not mocked (mock unsupported) and always pass
 * through. RPCs that pass through have their real response captured into the corpus (when a
 * [CorpusStore] is available) for later grounding; real *failures* are captured as ready-to-reuse
 * error envelopes under the `<method>.errors` corpus key.
 */
class MirageInterceptor(
    private val store: MirageMockStore,
    private val corpusProvider: () -> CorpusStore? = { null },
    private val captureExecutor: Executor = Executor { it.run() },
    private val registryProvider: () -> TypeRegistry = { TypeRegistry.getEmptyTypeRegistry() },
) : ClientInterceptor {

    override fun <ReqT, RespT> interceptCall(
        method: MethodDescriptor<ReqT, RespT>,
        callOptions: CallOptions,
        next: Channel,
    ): ClientCall<ReqT, RespT> {
        // Streaming is not mockable — only unary (server sends a single message) is.
        val json = if (method.type.serverSendsOneMessage()) store.mockFor(method.fullMethodName) else null
        if (json != null) return MockClientCall(method, json, registryProvider())

        val realCall = next.newCall(method, callOptions)
        val corpus = corpusProvider() ?: return realCall
        return CapturingClientCall(method, realCall, corpus, captureExecutor, registryProvider)
    }

    /**
     * A unary [ClientCall] that serves [json] without a network round-trip: an error envelope closes
     * the call with its status + trailers (no message), any other JSON delivers a single mock
     * response and closes OK.
     */
    private class MockClientCall<ReqT, RespT>(
        private val method: MethodDescriptor<ReqT, RespT>,
        private val json: String,
        private val registry: TypeRegistry,
    ) : ClientCall<ReqT, RespT>() {

        private var listener: Listener<RespT>? = null
        private var delivered = false

        override fun start(responseListener: Listener<RespT>, headers: Metadata) {
            listener = responseListener
        }

        override fun request(numMessages: Int) {
            deliverOnce()
        }

        override fun sendMessage(message: ReqT) = Unit
        override fun halfClose() = Unit
        override fun cancel(message: String?, cause: Throwable?) = Unit

        private fun deliverOnce() {
            val l = listener ?: return
            if (delivered) return
            delivered = true
            try {
                val error = MirageErrorFactory.errorFor(json, registry)
                if (error != null) {
                    // Trailers-only failure, exactly like a real failed RPC.
                    Timber.tag("Mirage").i(
                        "serving mock error: %s -> %s", method.fullMethodName, error.status.code,
                    )
                    l.onClose(error.status, error.trailers)
                    return
                }
                Timber.tag("Mirage").i("serving mock: %s", method.fullMethodName)
                val response = MirageResponseFactory.build(method, json, registry)
                l.onHeaders(Metadata())
                l.onMessage(response)
                l.onClose(Status.OK, Metadata())
            } catch (e: Throwable) {
                // Malformed mock (bad JSON, unknown @type, bad code…) → loud INTERNAL, never a
                // silent fake success and never a fall-through to the real server.
                l.onClose(
                    Status.INTERNAL
                        .withDescription("Mirage failed to build mock for ${method.fullMethodName}: ${e.message}")
                        .withCause(e),
                    Metadata(),
                )
            }
        }
    }

    /**
     * Forwards to the real call, capturing each proto response message into the corpus — and, when
     * the call fails, capturing the failure as a `$mirageError` envelope under `<method>.errors`.
     */
    private class CapturingClientCall<ReqT, RespT>(
        private val method: MethodDescriptor<ReqT, RespT>,
        delegate: ClientCall<ReqT, RespT>,
        private val corpus: CorpusStore,
        private val captureExecutor: Executor,
        private val registryProvider: () -> TypeRegistry,
    ) : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(delegate) {

        override fun start(responseListener: Listener<RespT>, headers: Metadata) {
            super.start(
                object : ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(responseListener) {
                    override fun onMessage(message: RespT) {
                        capture(message)
                        super.onMessage(message)
                    }

                    override fun onClose(status: Status, trailers: Metadata) {
                        if (!status.isOk) captureError(status, trailers)
                        super.onClose(status, trailers)
                    }
                },
                headers,
            )
        }

        private fun capture(message: RespT) {
            val proto = message as? Message ?: return
            val json = try {
                JsonFormat.printer().omittingInsignificantWhitespace().print(proto)
            } catch (e: Exception) {
                return
            }
            captureExecutor.execute {
                try {
                    corpus.record(method.fullMethodName, json)
                    Timber.tag("Mirage").i("captured: %s", method.fullMethodName)
                } catch (e: Exception) {
                    // capture is best-effort; never affect the real call
                }
            }
        }

        private fun captureError(status: Status, trailers: Metadata) {
            // Read the trailer on the call thread; render + write on the capture thread.
            val detailsBin = runCatching { trailers.get(MirageErrorFactory.STATUS_DETAILS_KEY) }.getOrNull()
            captureExecutor.execute {
                runCatching {
                    val envelope = MirageErrorFactory.captureEnvelope(status, detailsBin, registryProvider())
                    corpus.record(method.fullMethodName + ERRORS_KEY_SUFFIX, envelope)
                    Timber.tag("Mirage").i("captured error: %s (%s)", method.fullMethodName, status.code)
                } // best-effort; never affect the real call
            }
        }
    }

    companion object {
        /** Failed calls are recorded under `<fullMethodName>.errors` → `<key>.errors.jsonl` on disk. */
        const val ERRORS_KEY_SUFFIX = ".errors"
    }
}
