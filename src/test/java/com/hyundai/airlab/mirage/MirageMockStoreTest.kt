package com.hyundai.airlab.mirage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MirageMockStoreTest {

    private val listCards = "ridergwv1.RiderGw/ListCards"

    @Test
    fun `returns the registered mock json for a method`() {
        val store = MirageMockStore()

        store.setMock(listCards, """{"cards":[{}]}""")

        assertEquals("""{"cards":[{}]}""", store.mockFor(listCards))
    }

    @Test
    fun `returns null when no mock is registered`() {
        val store = MirageMockStore()

        assertNull(store.mockFor(listCards))
    }

    @Test
    fun `clear removes a previously registered mock`() {
        val store = MirageMockStore()
        store.setMock(listCards, """{"cards":[{}]}""")

        store.clear(listCards)

        assertNull(store.mockFor(listCards))
    }

    @Test
    fun `latest setMock wins for a method`() {
        val store = MirageMockStore()

        store.setMock(listCards, """{"v":1}""")
        store.setMock(listCards, """{"v":2}""")

        assertEquals("""{"v":2}""", store.mockFor(listCards))
    }
}
