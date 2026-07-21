package com.hyundai.airlab.mirage

import com.google.protobuf.TypeRegistry
import com.google.rpc.ErrorInfo
import io.grpc.Status
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64

class MirageErrorFactoryTest {

    private val empty: TypeRegistry = TypeRegistry.getEmptyTypeRegistry()
    private val withErrorInfo: TypeRegistry =
        TypeRegistry.newBuilder().add(ErrorInfo.getDescriptor()).build()

    // --- envelope detection ---

    @Test
    fun `returns null for a plain success mock`() {
        assertNull(MirageErrorFactory.errorFor("""{"cards":[]}""", empty))
    }

    @Test
    fun `returns null when the marker only appears inside a value`() {
        assertNull(MirageErrorFactory.errorFor("""{"note":"${'$'}mirageError"}""", empty))
    }

    // --- status ---

    @Test
    fun `builds a failed status from a code name in any case`() {
        val error = MirageErrorFactory.errorFor("""{"${'$'}mirageError":{"code":"unavailable"}}""", empty)!!
        assertEquals(Status.Code.UNAVAILABLE, error.status.code)
    }

    @Test
    fun `builds a failed status from a numeric code`() {
        val error = MirageErrorFactory.errorFor("""{"${'$'}mirageError":{"code":14}}""", empty)!!
        assertEquals(Status.Code.UNAVAILABLE, error.status.code)
    }

    @Test
    fun `message becomes the status description`() {
        val error = MirageErrorFactory.errorFor(
            """{"${'$'}mirageError":{"code":"NOT_FOUND","message":"no such card"}}""", empty,
        )!!
        assertEquals("no such card", error.status.description)
    }

    @Test
    fun `missing code is rejected`() {
        assertThrows<IllegalArgumentException> {
            MirageErrorFactory.errorFor("""{"${'$'}mirageError":{"message":"x"}}""", empty)
        }
    }

    @Test
    fun `OK code is rejected`() {
        assertThrows<IllegalArgumentException> {
            MirageErrorFactory.errorFor("""{"${'$'}mirageError":{"code":"OK"}}""", empty)
        }
    }

    @Test
    fun `unknown code name is rejected loudly`() {
        assertThrows<IllegalArgumentException> {
            MirageErrorFactory.errorFor("""{"${'$'}mirageError":{"code":"PG_CARDNUM"}}""", empty)
        }
    }

    // --- rich details (typed) ---

    @Test
    fun `typed details land on the grpc-status-details-bin trailer as a google rpc Status`() {
        val json = """
            {"${'$'}mirageError":{
              "code":"INVALID_ARGUMENT","message":"bad card",
              "details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"PG_CARDNUM","domain":"pg"}]
            }}
        """.trimIndent()

        val error = MirageErrorFactory.errorFor(json, withErrorInfo)!!

        val bytes = error.trailers.get(MirageErrorFactory.STATUS_DETAILS_KEY)
        assertNotNull(bytes)
        val rpcStatus = com.google.rpc.Status.parseFrom(bytes)
        assertEquals(Status.Code.INVALID_ARGUMENT.value(), rpcStatus.code)
        assertEquals("bad card", rpcStatus.message)
        val detail = rpcStatus.detailsList.single().unpack(ErrorInfo::class.java)
        assertEquals("PG_CARDNUM", detail.reason)
        assertEquals("pg", detail.domain)
    }

    @Test
    fun `an unregistered detail type is rejected loudly`() {
        val json = """
            {"${'$'}mirageError":{"code":"INVALID_ARGUMENT",
              "details":[{"@type":"type.googleapis.com/commonv1.ErrorInfo","type":"PG_CARDNUM"}]}}
        """.trimIndent()

        assertThrows<Exception> { MirageErrorFactory.errorFor(json, empty) }
    }

    // --- detailsBin (replay) ---

    @Test
    fun `detailsBin is decoded onto the trailer verbatim`() {
        val raw = com.google.rpc.Status.newBuilder().setCode(3).setMessage("replayed").build().toByteArray()
        val b64 = Base64.getEncoder().encodeToString(raw)

        val error = MirageErrorFactory.errorFor(
            """{"${'$'}mirageError":{"code":"INVALID_ARGUMENT","detailsBin":"$b64"}}""", empty,
        )!!

        assertArrayEquals(raw, error.trailers.get(MirageErrorFactory.STATUS_DETAILS_KEY))
    }

    @Test
    fun `details and detailsBin together are rejected`() {
        assertThrows<IllegalArgumentException> {
            MirageErrorFactory.errorFor(
                """{"${'$'}mirageError":{"code":"INTERNAL","details":[],"detailsBin":"AA=="}}""", empty,
            )
        }
    }

    // --- custom trailers ---

    @Test
    fun `custom ascii and binary trailers are attached`() {
        val error = MirageErrorFactory.errorFor(
            """{"${'$'}mirageError":{"code":"UNAVAILABLE",
                "trailers":{"retry-after":"30","x-blob-bin":"${Base64.getEncoder().encodeToString(byteArrayOf(1, 2))}"}}}""",
            empty,
        )!!

        assertEquals("30", error.trailers.get(io.grpc.Metadata.Key.of("retry-after", io.grpc.Metadata.ASCII_STRING_MARSHALLER)))
        assertArrayEquals(
            byteArrayOf(1, 2),
            error.trailers.get(io.grpc.Metadata.Key.of("x-blob-bin", io.grpc.Metadata.BINARY_BYTE_MARSHALLER)),
        )
    }

    // --- capture round-trip ---

    @Test
    fun `captured typed failure renders as a reusable envelope`() {
        val detailsBin = com.google.rpc.Status.newBuilder()
            .setCode(3).setMessage("bad card")
            .addDetails(com.google.protobuf.Any.pack(ErrorInfo.newBuilder().setReason("PG_CARDNUM").build()))
            .build().toByteArray()

        val envelope = MirageErrorFactory.captureEnvelope(
            Status.INVALID_ARGUMENT.withDescription("bad card"), detailsBin, withErrorInfo,
        )

        assertTrue(envelope.contains("google.rpc.ErrorInfo"))
        // The captured line must itself be a servable error mock.
        val replayed = MirageErrorFactory.errorFor(envelope, withErrorInfo)!!
        assertEquals(Status.Code.INVALID_ARGUMENT, replayed.status.code)
        val detail = com.google.rpc.Status.parseFrom(replayed.trailers.get(MirageErrorFactory.STATUS_DETAILS_KEY))
            .detailsList.single().unpack(ErrorInfo::class.java)
        assertEquals("PG_CARDNUM", detail.reason)
    }

    @Test
    fun `captured failure with an unknown detail type falls back to detailsBin replay`() {
        val detailsBin = com.google.rpc.Status.newBuilder()
            .setCode(3)
            .addDetails(com.google.protobuf.Any.pack(ErrorInfo.newBuilder().setReason("PG_CARDNUM").build()))
            .build().toByteArray()

        // Empty registry cannot render ErrorInfo as JSON → base64 replay form.
        val envelope = MirageErrorFactory.captureEnvelope(Status.INVALID_ARGUMENT, detailsBin, empty)

        assertTrue(envelope.contains("detailsBin"))
        val replayed = MirageErrorFactory.errorFor(envelope, empty)!!
        assertArrayEquals(detailsBin, replayed.trailers.get(MirageErrorFactory.STATUS_DETAILS_KEY))
    }

    @Test
    fun `captured plain failure renders code and message only`() {
        val envelope = MirageErrorFactory.captureEnvelope(Status.UNAVAILABLE.withDescription("down"), null, empty)

        val replayed = MirageErrorFactory.errorFor(envelope, empty)!!
        assertEquals(Status.Code.UNAVAILABLE, replayed.status.code)
        assertEquals("down", replayed.status.description)
        assertNull(replayed.trailers.get(MirageErrorFactory.STATUS_DETAILS_KEY))
    }
}
