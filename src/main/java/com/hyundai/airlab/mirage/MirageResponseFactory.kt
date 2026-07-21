package com.hyundai.airlab.mirage

import com.google.protobuf.Message
import com.google.protobuf.TypeRegistry
import com.google.protobuf.util.JsonFormat
import io.grpc.MethodDescriptor

/**
 * Builds a gRPC response message of any RPC purely from its [MethodDescriptor] and a JSON string,
 * so a single code path can mock every RiderGw endpoint without per-method code. [registry] resolves
 * `Any` fields (`@type`) in the JSON, e.g. an in-band `google.rpc.Status` inside a response body.
 */
object MirageResponseFactory {

    fun <RespT> build(
        method: MethodDescriptor<*, RespT>,
        json: String,
        registry: TypeRegistry = TypeRegistry.getEmptyTypeRegistry(),
    ): RespT {
        val marshaller = method.responseMarshaller
        require(marshaller is MethodDescriptor.PrototypeMarshaller<*>) {
            "Response marshaller is not a PrototypeMarshaller: ${marshaller.javaClass.name}"
        }
        val prototype = marshaller.messagePrototype as Message
        val builder = prototype.newBuilderForType()
        JsonFormat.parser().usingTypeRegistry(registry).ignoringUnknownFields().merge(json, builder)
        @Suppress("UNCHECKED_CAST")
        return builder.build() as RespT
    }
}
