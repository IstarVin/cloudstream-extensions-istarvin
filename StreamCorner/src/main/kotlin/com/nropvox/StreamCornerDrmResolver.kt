package com.nropvox

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.URI

/** Resolves the ClearKey configuration carried by the current Amazon embeds' `p` token. */
internal class StreamCornerDrmResolver(
    private val bridge: StreamCornerBridge,
    private val origin: String,
    private val fetch: (String, Map<String, String>) -> String,
) {
    private val lock = Mutex()
    private val modules = mutableMapOf<String, Pair<StreamCornerDrm.Module, Long>>()

    fun supports(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host in setOf(
            "amazon.com.getsugatensho.sbs", "sportsembed.su.getsugatensho.sbs",
            "amazon.com.pandecocogaming.sbs", "primevideo.com.pandecocogaming.sbs",
        ) && token(uri) != null
    }.getOrDefault(false)

    suspend fun resolve(url: String): SCDrmStream = withContext(Dispatchers.IO) {
        val uri = URI(url)
        val token = token(uri) ?: error("Missing embed token")
        StreamCornerDrm.legacyPayload(token.uppercase())?.let { payload ->
            StreamCornerParser.legacyDrmStream(payload)?.let { return@withContext it }
        }
        val host = "${uri.scheme}://${uri.authority}"
        val headers = mapOf("Referer" to "$origin/")
        val payload = module("$host${uri.path}", url, headers)
        // The asset endpoint selects its player by the referring embed path. A root referer for an NBA embed
        // returns an HTML player page instead of the JavaScript key decoder. Keep those variants apart in cache.
        val player = "$host/assets4/scplayer.js"
        val keys = module("$player#${uri.path}", player, mapOf("Referer" to url))
        try {
            bridge.decode(payload, keys, token)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            lock.withLock { modules.clear() }
            throw e
        }
    }

    private suspend fun module(cacheKey: String, url: String, headers: Map<String, String>): StreamCornerDrm.Module = lock.withLock {
        modules[cacheKey]?.takeIf { System.currentTimeMillis() - it.second < 10 * 60_000 }?.let { return@withLock it.first }
        val source = fetch(url, headers)
        val result = StreamCornerDrm.extract(source).singleOrNull()
            ?: error("Embed decoder changed (${URI(url).path}, ${source.length} characters)")
        modules[cacheKey] = result to System.currentTimeMillis()
        result
    }

    private fun token(uri: URI): String? = uri.rawQuery?.split('&')?.singleOrNull { it.startsWith("p=") }
        ?.substringAfter('=')?.takeIf { it.length in 1..60_000 && it.matches(Regex("[A-Za-z2-7]+")) }
}
