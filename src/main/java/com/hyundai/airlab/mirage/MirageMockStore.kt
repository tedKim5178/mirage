package com.hyundai.airlab.mirage

import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves the active mock for a gRPC fully-qualified method name (e.g.
 * "ridergwv1.RiderGw/ListCards"). Mocks are held **in memory only** and injected at runtime via the
 * debug HTTP control server ([setMock]/[clear]). Absent → falls through to the real server.
 *
 * Accessed from gRPC call threads, so backed by a concurrent map.
 */
class MirageMockStore {

    private val mocks = ConcurrentHashMap<String, String>()

    fun setMock(fullMethodName: String, json: String) {
        mocks[fullMethodName] = json
    }

    fun mockFor(fullMethodName: String): String? = mocks[fullMethodName]

    fun clear(fullMethodName: String) {
        mocks.remove(fullMethodName)
    }
}
