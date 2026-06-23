package com.hyundai.airlab.mirage

import com.google.protobuf.Message
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
 * Short-circuits a **unary** RPC that has an active mock in [store]: returns the mock response
 * without touching the real server. Server-streaming RPCs are not mocked (mock unsupported) and
 * always pass through. RPCs that pass through have their real response captured into the corpus
 * (when a [CorpusStore] is available) for later grounding.
 */
class MirageInterceptor(
    private val store: MirageMockStore,
    private val corpusProvider: () -> CorpusStore? = { null },
    private val captureExecutor: Executor = Executor { it.run() },
) : ClientInterceptor {

    override fun <ReqT, RespT> interceptCall(
        method: MethodDescriptor<ReqT, RespT>,
        callOptions: CallOptions,
        next: Channel,
    ): ClientCall<ReqT, RespT> {
        // Streaming is not mockable — only unary (server sends a single message) is.
        val json = if (method.type.serverSendsOneMessage()) store.mockFor(method.fullMethodName) else null
        if (json != null) return MockClientCall(method, json)

        val realCall = next.newCall(method, callOptions)
        val corpus = corpusProvider() ?: return realCall
        return CapturingClientCall(method, realCall, corpus, captureExecutor)
    }

    /** A unary [ClientCall] that delivers a single mock response built from [json], then closes OK. */
    private class MockClientCall<ReqT, RespT>(
        private val method: MethodDescriptor<ReqT, RespT>,
        private val json: String,
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
            Timber.tag("Mirage").i("serving mock: %s", method.fullMethodName)
            try {
                val response = MirageResponseFactory.build(method, json)
                l.onHeaders(Metadata())
                l.onMessage(response)
                l.onClose(Status.OK, Metadata())
            } catch (e: Exception) {
                l.onClose(
                    Status.INTERNAL
                        .withDescription("Mirage failed to build mock for ${method.fullMethodName}: ${e.message}")
                        .withCause(e),
                    Metadata(),
                )
            }
        }
    }

    /** Forwards to the real call, capturing each proto response message into the corpus. */
    private class CapturingClientCall<ReqT, RespT>(
        private val method: MethodDescriptor<ReqT, RespT>,
        delegate: ClientCall<ReqT, RespT>,
        private val corpus: CorpusStore,
        private val captureExecutor: Executor,
    ) : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(delegate) {

        override fun start(responseListener: Listener<RespT>, headers: Metadata) {
            super.start(
                object : ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(responseListener) {
                    override fun onMessage(message: RespT) {
                        capture(message)
                        super.onMessage(message)
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
    }
}
