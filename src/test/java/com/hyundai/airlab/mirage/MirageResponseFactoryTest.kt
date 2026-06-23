package com.hyundai.airlab.mirage

import com.google.protobuf.DescriptorProtos.FieldDescriptorProto
import io.grpc.MethodDescriptor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.InputStream

/**
 * Verifies the generic "MethodDescriptor + JSON -> proto response" mechanism.
 *
 * Uses a protobuf-java well-known type ([FieldDescriptorProto]) rather than a generated RiderGw
 * message on purpose: the vendored google/type protos declare an invalid `java.*` java_package
 * that the JVM classloader rejects (works on Android, not on plain JVM). Real RiderGw RPCs are
 * verified end-to-end in the running app. This test pins the factory logic itself.
 */
class MirageResponseFactoryTest {

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

    @Test
    fun `builds proto response from json via method descriptor`() {
        val json = """{"name":"phone","number":7}"""

        val response = MirageResponseFactory.build(method, json)

        assertEquals("phone", response.name)
        assertEquals(7, response.number)
    }
}
