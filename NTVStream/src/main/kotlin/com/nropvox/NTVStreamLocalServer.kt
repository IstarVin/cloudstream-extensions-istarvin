package com.nropvox

import com.lagradost.api.Log
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import kotlin.concurrent.thread

/**
 * Serves wideiptv.top (Falcon channels) as a media playlist over loopback. Its URLs carry a token that dies after
 * 5 minutes, so a plain link stops playing mid-stream and a cached link is dead on the next open. Every request here
 * mints (or reuses) a fresh token and returns the playlist with absolute, freshly tokened segment URLs.
 */
object NTVStreamLocalServer {
    private const val TAG = "NTVStream"
    private const val BASE = "https://cdn.wideiptv.top"
    private const val TOKEN_API = "https://wideiptv.top/api/refresh_token.php"
    private const val TOKEN_TTL_MS = 200_000L
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36"

    private var server: ServerSocket? = null
    private val tokens = HashMap<String, Pair<String, Long>>()
    private val variants = HashMap<String, String>()

    /** Loopback playlist URL for a wideiptv channel (`beINSPORTSUS`). */
    @Synchronized
    fun playlistUrl(channel: String): String {
        val port = (server ?: start()).localPort
        return "http://127.0.0.1:$port/wideiptv/$channel.m3u8"
    }

    /**
     * Whether the channel is up. A channel nobody is watching 404s on its first request while the origin starts it,
     * so the first tries are retried.
     */
    fun isLive(channel: String): Boolean = runCatching { media(channel, tries = 4) }.getOrNull() != null

    private fun start(): ServerSocket {
        val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        server = socket
        thread(isDaemon = true, name = "ntv-local") {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { handle(client) } }
            }
        }
        return socket
    }

    private fun handle(client: Socket) = client.use {
        it.soTimeout = 15_000
        val input = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val request = input.readLine() ?: return
        while (true) if (input.readLine().isNullOrEmpty()) break
        val parts = request.split(' ')
        val method = parts.getOrNull(0)
        val target = parts.getOrNull(1).orEmpty()
        val body = if (method == "GET" || method == "HEAD") runCatching { respond(target) }.getOrNull() else null
        val out = it.getOutputStream()
        if (body == null) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        } else {
            out.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\n" +
                    "Content-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray()
            )
            if (method == "GET") out.write(body)
        }
        out.flush()
    }

    private fun respond(target: String): ByteArray? {
        val channel = Regex("""^/wideiptv/([A-Za-z0-9_-]+)\.m3u8""").find(target)?.groupValues?.get(1) ?: return null
        return media(channel, tries = 2)?.toByteArray().also { if (it == null) Log.w(TAG, "$channel: no playlist") }
    }

    /** The channel's best-variant media playlist with absolute segment URLs, or null when it is not up. */
    private fun media(channel: String, tries: Int): String? {
        repeat(tries) { attempt ->
            val token = token(channel) ?: return@repeat
            val variant = variant(channel, token)
            if (variant != null) {
                val url = "$BASE/$channel/" + NTVStreamParser.withToken(variant, token)
                val text = get(url)
                if (text != null) return NTVStreamParser.absolutize(text, url.substringBefore('?'))
            }
            if (attempt < tries - 1) Thread.sleep(1_500)
        }
        return null
    }

    /** Variant path of the channel (`tracks-v1a1/mono.m3u8`), read once from the master playlist. */
    private fun variant(channel: String, token: String): String? {
        synchronized(this) { variants[channel] }?.let { return it }
        val master = get("$BASE/$channel/index.m3u8?token=$token") ?: return null
        val best = NTVStreamParser.bestVariant(master) ?: return null
        return best.substringBefore('?').also { synchronized(this) { variants[channel] = it } }
    }

    private fun token(channel: String): String? {
        synchronized(this) { tokens[channel] }?.takeIf { System.currentTimeMillis() - it.second < TOKEN_TTL_MS }?.let { return it.first }
        val json = get(TOKEN_API, """{"channel":"$channel"}""") ?: return null
        return NTVStreamParser.wideiptvToken(json)?.also { synchronized(this) { tokens[channel] = it to System.currentTimeMillis() } }
    }

    private fun get(url: String, postBody: String? = null): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.connectTimeout = 8_000
        connection.readTimeout = 10_000
        if (postBody != null) {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(postBody.toByteArray()) }
        }
        return try {
            if (connection.responseCode == 200) connection.inputStream.use { it.readBytes().decodeToString() }
            else null.also { Log.w(TAG, "${connection.responseCode} ${url.substringBefore('?')}") }
        } catch (e: Exception) {
            Log.w(TAG, "${e.javaClass.simpleName} ${url.substringBefore('?')}")
            null
        } finally {
            connection.disconnect()
        }
    }
}
