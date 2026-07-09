package com.hyundai.airlab.mirage

import fi.iki.elonen.NanoHTTPD

/**
 * Debug-only HTTP control endpoint for Mirage — the single channel for injecting mocks and reading
 * captured samples. Ports to platforms without adb (iOS parity, via `iproxy`).
 *
 * Binds to **loopback (127.0.0.1) only**, so it is reachable exclusively through a USB port-forward
 * (`adb forward tcp:8080 tcp:8080` on Android, `iproxy 8080 8080` on iOS) and is invisible on Wi-Fi.
 * That keeps it USB-only by construction — no LAN exposure, and no iOS Local Network permission.
 *
 * This class ships in the `mirage-debug` artifact (added via `debugImplementation`), so it is
 * compiled into the app's **debug build only** and is entirely absent from release.
 *
 * Routes (URL segment uses the file-style key, `service__Method`):
 * - `GET    /mirage/corpus`         → captured endpoint keys, one per line
 * - `GET    /mirage/corpus/<key>`   → real captured samples for that endpoint (JSONL)
 * - `PUT    /mirage/mock/<key>`     → store the request body as the mock for that endpoint
 * - `DELETE /mirage/mock/<key>`     → turn the mock off
 *
 * Example (after `adb forward tcp:8080 tcp:8080`):
 * `curl -X PUT http://localhost:8080/mirage/mock/ridergwv1.RiderGw__ListCards -d @mock.json`
 */
class MirageControlServer(port: Int = DEFAULT_PORT) : NanoHTTPD("127.0.0.1", port) {

    override fun serve(session: IHTTPSession): Response = try {
        val path = session.uri
        when {
            session.method == Method.GET && path == "/mirage/corpus" ->
                json((Mirage.corpus?.keys() ?: emptyList()).joinToString("\n"))

            session.method == Method.GET && path.startsWith("/mirage/corpus/") ->
                json((Mirage.corpus?.samplesFor(keyOf(path, "/mirage/corpus/")) ?: emptyList()).joinToString("\n"))

            session.method == Method.PUT && path.startsWith("/mirage/mock/") -> {
                Mirage.store.setMock(keyOf(path, "/mirage/mock/"), body(session))
                json("""{"stored":"${keyOf(path, "/mirage/mock/")}"}""")
            }

            session.method == Method.DELETE && path.startsWith("/mirage/mock/") -> {
                Mirage.store.clear(keyOf(path, "/mirage/mock/"))
                json("""{"cleared":"${keyOf(path, "/mirage/mock/")}"}""")
            }

            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "no route: ${session.method} $path")
        }
    } catch (e: Exception) {
        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "error: ${e.message}")
    }

    /** URL carries the file-style key (`svc__Method`); the stores use the gRPC name (`svc/Method`). */
    private fun keyOf(path: String, prefix: String): String =
        path.removePrefix(prefix).trimEnd('/').replace("__", "/")

    private fun body(session: IHTTPSession): String {
        val len = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (len <= 0) return ""
        val buf = ByteArray(len)
        var read = 0
        while (read < len) {
            val r = session.inputStream.read(buf, read, len - read)
            if (r < 0) break
            read += r
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }

    private fun json(payload: String): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json", payload)

    companion object {
        const val DEFAULT_PORT = 8080
    }
}
