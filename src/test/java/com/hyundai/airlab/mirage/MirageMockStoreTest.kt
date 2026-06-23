package com.hyundai.airlab.mirage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MirageMockStoreTest {

    private val listCards = "ridergwv1.RiderGw/ListCards"
    private val listCardsFile = "ridergwv1.RiderGw__ListCards.json"

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
    fun `reads the mock from a file when not set in memory`(@TempDir dir: File) {
        File(dir, listCardsFile).writeText("""{"cards":[{}]}""")
        val store = MirageMockStore().apply { fileDir = dir }

        assertEquals("""{"cards":[{}]}""", store.mockFor(listCards))
    }

    @Test
    fun `reflects file changes on each call without restart`(@TempDir dir: File) {
        val file = File(dir, listCardsFile)
        val store = MirageMockStore().apply { fileDir = dir }

        file.writeText("""{"v":1}""")
        assertEquals("""{"v":1}""", store.mockFor(listCards))

        file.writeText("""{"v":2}""") // 자연어로 파일만 바꿈
        assertEquals("""{"v":2}""", store.mockFor(listCards)) // 다음 호출이 새 파일 반영
    }

    @Test
    fun `in-memory mock takes precedence over the file`(@TempDir dir: File) {
        File(dir, listCardsFile).writeText("""{"from":"file"}""")
        val store = MirageMockStore().apply { fileDir = dir }

        store.setMock(listCards, """{"from":"memory"}""")

        assertEquals("""{"from":"memory"}""", store.mockFor(listCards))
    }
}
