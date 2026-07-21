package com.hyundai.airlab.mirage

import com.google.protobuf.Struct
import com.google.protobuf.TypeRegistry
import com.google.protobuf.Value
import com.google.protobuf.util.JsonFormat
import io.grpc.Metadata
import io.grpc.Status
import java.util.Base64

/**
 * Turns a mock file whose JSON is an *error envelope* into a failed gRPC status + trailers, so a
 * mock can exercise the app's error handling instead of a success state.
 *
 * The envelope is detected by the reserved top-level key `$mirageError` (protobuf field names cannot
 * contain `$`, so it can never collide with a real response field):
 *
 * ```json
 * {
 *   "$mirageError": {
 *     "code": "INVALID_ARGUMENT",       // gRPC status code name (any case) or number — required
 *     "message": "text",                 // optional → status description
 *     "details": [                       // optional → rich error model (google.rpc.Status.details)
 *       { "@type": "type.googleapis.com/commonv1.ErrorInfo", "type": "PG_CARDNUM", ... }
 *     ],
 *     "detailsBin": "<base64>",          // optional alternative: raw serialized google.rpc.Status
 *     "trailers": { "k": "v", "x-bin": "<base64>" }  // optional extra trailers (non-standard apps)
 *   }
 * }
 * ```
 *
 * `details` are delivered on the standard `grpc-status-details-bin` trailer — byte-identical to how
 * a real server sends them — so the app's `StatusProto.fromThrowable(...)`-based conversion to typed
 * exceptions fires exactly as in production. Parsing each detail's `@type` needs the detail proto's
 * descriptor: standard `google.rpc.*` types are pre-registered when available, and app-specific ones
 * are added via [Mirage.registerErrorDetailTypes]. `detailsBin` needs no registration (replay).
 */
object MirageErrorFactory {

    /** Reserved top-level key that marks a mock as an error mock. */
    const val ENVELOPE_KEY = "\$mirageError"

    /** Standard rich-error-model trailer; `io.grpc.protobuf.StatusProto` reads this on the app side. */
    internal val STATUS_DETAILS_KEY: Metadata.Key<ByteArray> =
        Metadata.Key.of("grpc-status-details-bin", Metadata.BINARY_BYTE_MARSHALLER)

    class MockError(val status: Status, val trailers: Metadata)

    /**
     * Returns the error to serve for [json], or null when [json] is a normal success mock (no
     * top-level [ENVELOPE_KEY]). A malformed envelope throws — the interceptor surfaces that as a
     * loud INTERNAL failure instead of silently mocking success.
     */
    fun errorFor(json: String, registry: TypeRegistry): MockError? {
        if (!json.contains(ENVELOPE_KEY)) return null // cheap pre-filter; the real check is below
        val root = Struct.newBuilder().also { JsonFormat.parser().ignoringUnknownFields().merge(json, it) }.build()
        val env = root.fieldsMap[ENVELOPE_KEY]
            ?.takeIf { it.kindCase == Value.KindCase.STRUCT_VALUE }?.structValue
            ?: return null // the marker only appeared inside a value → success mock

        val status = statusOf(env)
        val trailers = Metadata()
        detailsBytes(env, status, registry)?.let { trailers.put(STATUS_DETAILS_KEY, it) }
        putCustomTrailers(env, trailers)
        return MockError(status, trailers)
    }

    /**
     * Renders a captured real failure as envelope JSON, so a corpus error sample is directly
     * reusable as a mock file. Prefers the typed `details` form (needs every `@type` in [registry]);
     * falls back to `detailsBin` (replayable with no registration).
     */
    fun captureEnvelope(status: Status, detailsBin: ByteArray?, registry: TypeRegistry): String {
        if (detailsBin != null) {
            try {
                val rpcStatus = com.google.rpc.Status.parseFrom(detailsBin)
                val body = JsonFormat.printer().usingTypeRegistry(registry)
                    .omittingInsignificantWhitespace().print(rpcStatus)
                return """{"$ENVELOPE_KEY":$body}"""
            } catch (_: Exception) {
                // @type not in the registry (or bytes aren't a google.rpc.Status) → base64 replay form
            }
        }
        val env = Struct.newBuilder()
            .putFields("code", stringValue(status.code.name))
        status.description?.let { env.putFields("message", stringValue(it)) }
        detailsBin?.let { env.putFields("detailsBin", stringValue(Base64.getEncoder().encodeToString(it))) }
        val root = Struct.newBuilder()
            .putFields(ENVELOPE_KEY, Value.newBuilder().setStructValue(env).build()).build()
        return JsonFormat.printer().omittingInsignificantWhitespace().print(root)
    }

    private fun statusOf(env: Struct): Status {
        val codeValue = requireNotNull(env.fieldsMap["code"]) {
            "$ENVELOPE_KEY.code is required (a gRPC status code name or number)"
        }
        val code = when (codeValue.kindCase) {
            Value.KindCase.STRING_VALUE -> Status.Code.valueOf(codeValue.stringValue.trim().uppercase())
            Value.KindCase.NUMBER_VALUE -> Status.fromCodeValue(codeValue.numberValue.toInt()).code
            else -> error("$ENVELOPE_KEY.code must be a gRPC status code name or number")
        }
        require(code != Status.Code.OK) { "$ENVELOPE_KEY.code must not be OK — use a plain response mock for success" }
        val message = env.fieldsMap["message"]?.stringValue
        return if (message.isNullOrEmpty()) code.toStatus() else code.toStatus().withDescription(message)
    }

    /** Builds the `grpc-status-details-bin` payload from `details` (typed) or `detailsBin` (replay). */
    private fun detailsBytes(env: Struct, status: Status, registry: TypeRegistry): ByteArray? {
        val details = env.fieldsMap["details"]
        val detailsBin = env.fieldsMap["detailsBin"]
        require(details == null || detailsBin == null) { "$ENVELOPE_KEY: use either details or detailsBin, not both" }

        if (detailsBin != null) return Base64.getDecoder().decode(detailsBin.stringValue)
        if (details == null) return null
        require(details.kindCase == Value.KindCase.LIST_VALUE) { "$ENVELOPE_KEY.details must be an array" }

        // Reassemble a google.rpc.Status JSON and parse it with the registry, so each detail's
        // @type resolves to a real proto. An unknown @type throws (loud, not a silent success).
        val statusStruct = Struct.newBuilder()
            .putFields("code", Value.newBuilder().setNumberValue(status.code.value().toDouble()).build())
            .putFields("message", stringValue(status.description ?: ""))
            .putFields("details", details)
            .build()
        val rpcStatus = com.google.rpc.Status.newBuilder()
        JsonFormat.parser().usingTypeRegistry(registry).merge(JsonFormat.printer().print(statusStruct), rpcStatus)
        return rpcStatus.build().toByteArray()
    }

    /** Extra trailers for apps with non-standard error transport; `-bin` keys take base64 values. */
    private fun putCustomTrailers(env: Struct, trailers: Metadata) {
        val custom = env.fieldsMap["trailers"]
            ?.takeIf { it.kindCase == Value.KindCase.STRUCT_VALUE }?.structValue ?: return
        for ((name, value) in custom.fieldsMap) {
            if (name.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
                trailers.put(
                    Metadata.Key.of(name, Metadata.BINARY_BYTE_MARSHALLER),
                    Base64.getDecoder().decode(value.stringValue),
                )
            } else {
                trailers.put(Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER), value.stringValue)
            }
        }
    }

    private fun stringValue(s: String): Value = Value.newBuilder().setStringValue(s).build()
}
