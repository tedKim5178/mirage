package com.hyundai.airlab.mirage

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * Zero-touch auto-installer for Mirage.
 *
 * This provider is declared in the library's own manifest, so manifest merging registers it in the
 * host app automatically — a consuming app needs no startup code and no manifest entry. Android
 * instantiates every [ContentProvider] and calls [onCreate] **before** `Application.onCreate`, so
 * Mirage is initialized as early as possible.
 *
 * It self-limits to **debuggable** host builds (checked at runtime via [ApplicationInfo.FLAG_DEBUGGABLE]),
 * so even though the provider ships in every variant it does nothing in a release build. The only
 * remaining integration step the app must do itself is attaching the interceptor where it builds its
 * gRPC channel: `if (BuildConfig.DEBUG) intercept(Mirage.interceptor)`.
 */
class MirageAutoInstaller : ContentProvider() {

    override fun onCreate(): Boolean {
        val ctx = context ?: return false

        // Only arm Mirage on a debuggable host build; no-op in release.
        val debuggable = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return true

        // Everything lives under the host app's external files dir so it's easy to adb push / pull:
        //   .../files/mirage/           <- adb-pushed mock files (read fresh per call)
        //   .../files/mirage/corpus/    <- auto-captured real response samples
        val dir = (ctx.getExternalFilesDir("mirage") ?: File(ctx.filesDir, "mirage")).apply { mkdirs() }
        Mirage.init(mockDir = dir, corpusDir = File(dir, "corpus"))
        Log.d("Mirage", "auto-installed; mock dir = ${dir.absolutePath} (read per call)")
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
