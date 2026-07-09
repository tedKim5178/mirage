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
