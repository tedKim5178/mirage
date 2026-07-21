package com.hyundai.airlab.mirage

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class MirageRegistryTest {

    // google.rpc.Help / RequestInfo are NOT in Mirage's pre-registered defaults, so these prove the
    // overloads themselves work (and that both spellings agents reach for compile).
    @Test
    fun `registers a type from its default instance`() {
        Mirage.registerErrorDetailTypes(com.google.rpc.RequestInfo.getDefaultInstance())

        assertNotNull(Mirage.typeRegistry.find("google.rpc.RequestInfo"))
    }

    @Test
    fun `registers a type from its descriptor`() {
        Mirage.registerErrorDetailTypes(com.google.rpc.Help.getDescriptor())

        assertNotNull(Mirage.typeRegistry.find("google.rpc.Help"))
    }
}
