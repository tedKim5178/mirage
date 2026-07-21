package com.hyundai.airlab.mirage

import com.google.protobuf.DescriptorProtos.FieldDescriptorProto
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.InputStream

class MirageInterceptorTest {

    private val marshaller = object : MethodDescriptor.PrototypeMarshaller<FieldDescriptorProto> {
        override fun getMessagePrototype(): FieldDescriptorProto = FieldDescriptorProto.getDefaultInstance()
        override fun getMessageClass(): Class<FieldDescriptorProto> = FieldDescriptorProto::class.java
        override fun stream(value: FieldDescriptorProto): InputStream = value.toByteArray().inputStream()
        override fun parse(stream: InputStream): FieldDescriptorProto = FieldDescriptorProto.parseFrom(stream)
    }

    private val method: MethodDescriptor<FieldDescriptorProto, FieldDescriptorProto> =
        MethodDescriptor.newBuilder<FieldDescriptorProto, FieldDescriptorProto>()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("mirage.test/Echo")
            .setRequestMarshaller(marshaller)
            .setResponseMarshaller(marshaller)
            .build()

    private val streamMethod: MethodDescriptor<FieldDescriptorProto, FieldDescriptorProto> =
        MethodDescriptor.newBuilder<FieldDescriptorProto, FieldDescriptorProto>()
            .setType(MethodDescriptor.MethodType.SERVER_STREAMING)
            .setFullMethodName("mirage.test/Stream")
            .setRequestMarshaller(marshaller)
            .setResponseMarshaller(marshaller)
            .build()

    @Test
    fun `serves mock response without calling the real channel`() {
        val store = MirageMockStore()
        store.setMock(method.fullMethodName, """{"name":"phone","number":7}""")
        val interceptor = MirageInterceptor(store)
        val realChannel = object : Channel() {
            override fun <Req, Resp> newCall(m: MethodDescriptor<Req, Resp>, o: CallOptions): ClientCall<Req, Resp> =
                throw AssertionError("real channel must not be called for a mocked method")

            override fun authority(): String = "test"
        }

        val call = interceptor.interceptCall(method, CallOptions.DEFAULT, realChannel)

        var received: FieldDescriptorProto? = null
        var status: Status? = null
        call.start(object : ClientCall.Listener<FieldDescriptorProto>() {
            override fun onMessage(message: FieldDescriptorProto) {
                received = message
            }

            override fun onClose(s: Status, trailers: Metadata) {
                status = s
            }
        }, Metadata())
        call.request(1)

        assertEquals("phone", received?.name)
        assertEquals(7, received?.number)
        assertEquals(Status.Code.OK, status?.code)
    }

    @Test
    fun `does not mock a server-streaming method even if a mock is registered`() {
        val store = MirageMockStore()
        store.setMock(streamMethod.fullMethodName, """{"name":"x"}""") // present, but must be ignored
        val interceptor = MirageInterceptor(store)
        var newCallInvoked = false
        val realChannel = object : Channel() {
            override fun <Req, Resp> newCall(m: MethodDescriptor<Req, Resp>, o: CallOptions): ClientCall<Req, Resp> {
                newCallInvoked = true
                return object : ClientCall<Req, Resp>() {
                    override fun start(l: Listener<Resp>, h: Metadata) {}
                    override fun request(n: Int) {}
                    override fun cancel(m: String?, c: Throwable?) {}
                    override fun halfClose() {}
                    override fun sendMessage(m: Req) {}
                }
            }

            override fun authority(): String = "test"
        }

        interceptor.interceptCall(streamMethod, CallOptions.DEFAULT, realChannel)

        assertTrue(newCallInvoked) // streaming → pass through, not mocked
    }

    @Test
    fun `passes through to the real channel when no mock is registered`() {
        val store = MirageMockStore()
        val interceptor = MirageInterceptor(store)
        var newCallInvoked = false
        val realChannel = object : Channel() {
            override fun <Req, Resp> newCall(m: MethodDescriptor<Req, Resp>, o: CallOptions): ClientCall<Req, Resp> {
                newCallInvoked = true
                return object : ClientCall<Req, Resp>() {
                    override fun start(l: Listener<Resp>, h: Metadata) {}
                    override fun request(n: Int) {}
                    override fun cancel(m: String?, c: Throwable?) {}
                    override fun halfClose() {}
                    override fun sendMessage(m: Req) {}
                }
            }

            override fun authority(): String = "test"
        }

        interceptor.interceptCall(method, CallOptions.DEFAULT, realChannel)

        assertTrue(newCallInvoked)
    }

    @Test
    fun `serves an error mock as a failed call with rich trailers and no message`() {
        val store = MirageMockStore()
        store.setMock(
            method.fullMethodName,
            """{"${'$'}mirageError":{"code":"INVALID_ARGUMENT","message":"bad card",
                 "details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"PG_CARDNUM"}]}}""",
        )
        val registry = com.google.protobuf.TypeRegistry.newBuilder()
            .add(com.google.rpc.ErrorInfo.getDescriptor()).build()
        val interceptor = MirageInterceptor(store, registryProvider = { registry })
        val realChannel = object : Channel() {
            override fun <Req, Resp> newCall(m: MethodDescriptor<Req, Resp>, o: CallOptions): ClientCall<Req, Resp> =
                throw AssertionError("real channel must not be called for a mocked method")

            override fun authority(): String = "test"
        }

        val call = interceptor.interceptCall(method, CallOptions.DEFAULT, realChannel)

        var received: FieldDescriptorProto? = null
        var status: Status? = null
        var trailers: Metadata? = null
        call.start(object : ClientCall.Listener<FieldDescriptorProto>() {
            override fun onMessage(message: FieldDescriptorProto) {
                received = message
            }

            override fun onClose(s: Status, t: Metadata) {
                status = s
                trailers = t
            }
        }, Metadata())
        call.request(1)

        assertEquals(null, received) // an error mock delivers no response message
        assertEquals(Status.Code.INVALID_ARGUMENT, status?.code)
        assertEquals("bad card", status?.description)
        val bytes = trailers?.get(MirageErrorFactory.STATUS_DETAILS_KEY)
        val detail = com.google.rpc.Status.parseFrom(bytes).detailsList.single()
            .unpack(com.google.rpc.ErrorInfo::class.java)
        assertEquals("PG_CARDNUM", detail.reason)
    }

    @Test
    fun `a malformed error envelope fails loudly with INTERNAL`() {
        val store = MirageMockStore()
        store.setMock(method.fullMethodName, """{"${'$'}mirageError":{"code":"NOT_A_CODE"}}""")
        val interceptor = MirageInterceptor(store)
        val realChannel = object : Channel() {
            override fun <Req, Resp> newCall(m: MethodDescriptor<Req, Resp>, o: CallOptions): ClientCall<Req, Resp> =
                throw AssertionError("real channel must not be called for a mocked method")

            override fun authority(): String = "test"
        }

        val call = interceptor.interceptCall(method, CallOptions.DEFAULT, realChannel)
        var status: Status? = null
        call.start(object : ClientCall.Listener<FieldDescriptorProto>() {
            override fun onClose(s: Status, t: Metadata) {
                status = s
            }
        }, Metadata())
        call.request(1)

        assertEquals(Status.Code.INTERNAL, status?.code)
    }

    @Test
    fun `captures a failed pass-through call as a reusable error envelope`(@TempDir dir: File) {
        val store = MirageMockStore() // no mock registered -> pass-through
        val corpus = CorpusStore(dir)
        val interceptor = MirageInterceptor(store, corpusProvider = { corpus })

        var serverListener: ClientCall.Listener<FieldDescriptorProto>? = null
        val realChannel = object : Channel() {
            override fun <Req, Resp> newCall(m: MethodDescriptor<Req, Resp>, o: CallOptions): ClientCall<Req, Resp> {
                @Suppress("UNCHECKED_CAST")
                return object : ClientCall<Req, Resp>() {
                    override fun start(l: Listener<Resp>, h: Metadata) {
                        serverListener = l as ClientCall.Listener<FieldDescriptorProto>
                    }

                    override fun request(n: Int) {}
                    override fun cancel(m: String?, c: Throwable?) {}
                    override fun halfClose() {}
                    override fun sendMessage(m: Req) {}
                } as ClientCall<Req, Resp>
            }

            override fun authority(): String = "test"
        }

        val call = interceptor.interceptCall(method, CallOptions.DEFAULT, realChannel)
        call.start(object : ClientCall.Listener<FieldDescriptorProto>() {}, Metadata())
        // simulate the real server failing the call
        serverListener!!.onClose(Status.UNAVAILABLE.withDescription("down"), Metadata())

        val samples = corpus.samplesFor(method.fullMethodName + MirageInterceptor.ERRORS_KEY_SUFFIX)
        assertEquals(1, samples.size)
        // The captured line is itself a valid error mock.
        val replayed = MirageErrorFactory.errorFor(samples.first(), com.google.protobuf.TypeRegistry.getEmptyTypeRegistry())!!
        assertEquals(Status.Code.UNAVAILABLE, replayed.status.code)
        assertEquals("down", replayed.status.description)
    }

    @Test
    fun `captures the real response into the corpus on a pass-through call`(@TempDir dir: File) {
        val store = MirageMockStore() // no mock registered -> pass-through
        val corpus = CorpusStore(dir)
        val interceptor = MirageInterceptor(store, corpusProvider = { corpus })

        var serverListener: ClientCall.Listener<FieldDescriptorProto>? = null
        val realChannel = object : Channel() {
            override fun <Req, Resp> newCall(m: MethodDescriptor<Req, Resp>, o: CallOptions): ClientCall<Req, Resp> {
                @Suppress("UNCHECKED_CAST")
                return object : ClientCall<Req, Resp>() {
                    override fun start(l: Listener<Resp>, h: Metadata) {
                        serverListener = l as ClientCall.Listener<FieldDescriptorProto>
                    }

                    override fun request(n: Int) {}
                    override fun cancel(m: String?, c: Throwable?) {}
                    override fun halfClose() {}
                    override fun sendMessage(m: Req) {}
                } as ClientCall<Req, Resp>
            }

            override fun authority(): String = "test"
        }

        val call = interceptor.interceptCall(method, CallOptions.DEFAULT, realChannel)
        call.start(object : ClientCall.Listener<FieldDescriptorProto>() {}, Metadata())
        // simulate the real server delivering a response
        serverListener!!.onMessage(FieldDescriptorProto.newBuilder().setName("real").setNumber(9).build())

        val samples = corpus.samplesFor(method.fullMethodName)
        assertEquals(1, samples.size)
        assertTrue(samples.first().contains("real"))
    }
}
