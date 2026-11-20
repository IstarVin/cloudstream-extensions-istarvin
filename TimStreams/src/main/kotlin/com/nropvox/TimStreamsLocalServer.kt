package com.nropvox

import com.lagradost.api.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Plays tim-streams.com streams for the player over loopback. The origin's playlist needs an `Origin` header the
 * player can't send and lists PNG images instead of MPEG-TS segments (see [TimStreamsSegment]), so:
 *  - `/m.m3u8?u=<variant url>` fetches the live media playlist on every request and points its segments at us,
 *  - `/s.ts?u=<segment url>` downloads a segment image and answers with the MPEG-TS inside it.
 * Nothing is kept per stream except a small cache of segments being fetched ahead, so a link keeps working for as
 * long as the origin's signed playlist URL does (about 2.5 hours). Every thread started here is wrapped, because an
 * uncaught exception in a detached thread kills the app (Safe mode).
 */
object TimStreamsLocalServer {
    private const val TAG = "TimStreams"
    private const val MAX_CACHED_SEGMENTS = 8
    private const val PREFETCH = 4
    private const val SEGMENT_WAIT_SECONDS = 25L

    private var server: ServerSocket? = null
    private val workers: ExecutorService = Executors.newFixedThreadPool(4) { task ->
        Thread(task, "timstreams-fetch").apply { isDaemon = true }
    }
    private val segments = object : LinkedHashMap<String, Future<ByteArray?>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Future<ByteArray?>>?): Boolean {
            val drop = size > MAX_CACHED_SEGMENTS
            if (drop) eldest?.value?.cancel(true)
            return drop
        }
    }

    /** Loopback URL of the live media playlist at [variantUrl]. */
    @Synchronized
    fun playlistUrl(variantUrl: String): String {
        val port = (server ?: start()).localPort
        return "http://127.0.0.1:$port/m.m3u8?u=" + URLEncoder.encode(variantUrl, "UTF-8")
    }

    /** Text of a playlist from the stream origin, or null (404 means the signed URL expired or the headers are wrong). */
    fun fetchText(url: String): String? = fetch(url, playlist = true)?.decodeToString()

    @Synchronized
    private fun start(): ServerSocket {
        val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        server = socket
        thread(isDaemon = true, name = "timstreams-local") {
            try {
                while (!socket.isClosed) {
                    val client = try {
                        socket.accept()
                    } catch (e: IOException) {
                        break
                    }
                    thread(isDaemon = true, name = "timstreams-conn") {
                        try {
                            handle(client)
                        } catch (e: Throwable) {
                            Log.w(TAG, "connection: ${e.javaClass.simpleName} ${e.message}")
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "accept loop: ${e.javaClass.simpleName} ${e.message}")
            }
        }
        return socket
    }

    private fun handle(client: Socket) {
        client.use {
            it.soTimeout = 40_000
            val input = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val request = input.readLine() ?: return
            while (true) if (input.readLine().isNullOrEmpty()) break
            val parts = request.split(' ')
            val method = parts.getOrNull(0)
            val target = parts.getOrNull(1).orEmpty()
            val out = it.getOutputStream()
            val response = if (method == "GET" || method == "HEAD") {
                try {
                    respond(target)
                } catch (e: Exception) {
                    Log.w(TAG, "$target: ${e.javaClass.simpleName} ${e.message}")
                    null
                }
            } else null
            if (response == null) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            } else {
                out.write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: ${response.first}\r\nContent-Length: ${response.second.size}\r\n" +
                        "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray()
                )
                if (method == "GET") out.write(response.second)
            }
            out.flush()
        }
    }

    private fun respond(target: String): Pair<String, ByteArray>? {
        val remote = TimStreamsParser.queryU(target) ?: return null
        return when {
            target.startsWith("/m.m3u8") -> playlist(remote)?.let { "application/vnd.apple.mpegurl" to it.toByteArray() }
            target.startsWith("/s.ts") -> segment(remote)?.let { "video/mp2t" to it }
            else -> null
        }
    }

    private fun playlist(variantUrl: String): String? {
        val text = fetchText(variantUrl) ?: return null
        val port = server?.localPort ?: return null
        val rewritten = TimStreamsParser.rewriteMedia(text, variantUrl, "http://127.0.0.1:$port/s.ts")
        // Start unwrapping what the player will ask for next while it is still reading the playlist.
        TimStreamsParser.segmentUrls(text, variantUrl).takeLast(PREFETCH).forEach(::prefetch)
        return rewritten
    }

    private fun prefetch(url: String): Future<ByteArray?> = synchronized(segments) {
        segments[url]?.takeUnless { it.isCancelled } ?: workers.submit<ByteArray?> { download(url) }.also { segments[url] = it }
    }

    private fun segment(url: String): ByteArray? {
        val future = prefetch(url)
        val ts = try {
            future.get(SEGMENT_WAIT_SECONDS, TimeUnit.SECONDS)
        } catch (e: Exception) {
            null
        }
        // A failed fetch must not be served again from the cache.
        if (ts == null) synchronized(segments) { if (segments[url] === future) segments.remove(url) }
        return ts
    }

    private fun download(url: String): ByteArray? {
        val png = fetch(url, playlist = false) ?: return null
        val ts = TimStreamsSegment.unwrap(png)
        if (ts == null) Log.w(TAG, "segment is not a KWP1 image (${png.size} bytes): ${url.substringBefore('?')}")
        return ts
    }

    private fun fetch(url: String, playlist: Boolean): ByteArray? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", TimStreamsParser.BROWSER_USER_AGENT)
        // The origin 404s a playlist without the embed's Origin, and answers with the right one only for browsers.
        if (playlist) connection.setRequestProperty("Origin", TimStreamsParser.EMBED_ORIGIN)
        connection.connectTimeout = 8_000
        connection.readTimeout = 15_000
        return try {
            if (connection.responseCode == 200) connection.inputStream.use { it.readBytes() }
            else null.also { Log.w(TAG, "${connection.responseCode} ${url.substringBefore('?')}") }
        } catch (e: Exception) {
            Log.w(TAG, "${e.javaClass.simpleName} ${url.substringBefore('?')}")
            null
        } finally {
            connection.disconnect()
        }
    }
}
