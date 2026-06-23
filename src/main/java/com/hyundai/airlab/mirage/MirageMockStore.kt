package com.hyundai.airlab.mirage

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves the active mock for a gRPC fully-qualified method name (e.g.
 * "ridergwv1.RiderGw/ListCards"). In-memory mocks (set via [setMock]) take precedence; otherwise,
 * if [fileDir] is set, the mock is read **fresh from a file on each call** — so editing the file
 * applies from the next RPC with no app restart. Absent → falls through to the real server.
 *
 * Accessed from gRPC call threads, so backed by a concurrent map.
 */
class MirageMockStore {

    private val mocks = ConcurrentHashMap<String, String>()

    /** When set, methods not in memory are read from `<fileDir>/<key with / -> __>.json` per call. */
    @Volatile
    var fileDir: File? = null

    fun setMock(fullMethodName: String, json: String) {
        mocks[fullMethodName] = json
    }

    fun mockFor(fullMethodName: String): String? =
        mocks[fullMethodName] ?: fileMockFor(fullMethodName)

    /** Read the mock fresh from disk each call, so file edits apply from the next RPC. */
    private fun fileMockFor(fullMethodName: String): String? {
        val dir = fileDir ?: return null
        val file = File(dir, fullMethodName.replace("/", "__") + ".json")
        return runCatching { if (file.exists()) file.readText() else null }.getOrNull()
    }

    fun clear(fullMethodName: String) {
        mocks.remove(fullMethodName)
    }
}
