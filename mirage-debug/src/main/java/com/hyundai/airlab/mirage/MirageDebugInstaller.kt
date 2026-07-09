package com.hyundai.airlab.mirage

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.net.Uri
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File

/**
 * Zero-touch debug installer. Ships in the `mirage-debug` artifact, which consumers add with
 * `debugImplementation`, so it — and everything it starts — is compiled into the app's **debug build
 * only** and is absent from release.
 *
 * Registered via this module's manifest, so a consuming app needs no startup code. Android calls
 * [onCreate] before `Application.onCreate`. On a debuggable host it:
 * 1. enables corpus capture under the on-device dir (real responses are recorded for grounding), and
 * 2. starts the [MirageControlServer] so mocks can be injected over HTTP (`curl`) — the same
 *    mechanism ports to iOS.
 */
class MirageDebugInstaller : ContentProvider() {

    private var server: MirageControlServer? = null

    override fun onCreate(): Boolean {
        val ctx = context ?: return false

        val debuggable = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return true

        val dir = (ctx.getExternalFilesDir("mirage") ?: File(ctx.filesDir, "mirage")).apply { mkdirs() }
        Mirage.init(corpusDir = File(dir, "corpus"))

        server = runCatching {
            MirageControlServer().also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
        }.onFailure { Log.w("Mirage", "control server failed to start: ${it.message}") }.getOrNull()

        Log.d(
            "Mirage",
            "installed; corpus dir=${File(dir, "corpus").absolutePath}; " +
                "control server=${if (server != null) "on :${MirageControlServer.DEFAULT_PORT}" else "off"}",
        )
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
