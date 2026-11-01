package com.nropvox

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import com.nropvox.OnlyKDramaParser.Host
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.Collections

/**
 * The onlykdrama.shop requests behind its Cloudflare challenge and the file hosts its posts link to. Shared by the
 * OnlyKDrama and OmniStream plugins.
 */
object OnlyKDramaSource {
    const val SITE = "https://onlykdrama.shop"

    // Built on first use: each CloudflareKiller clears every WebView cookie when it is created.
    private val cloudflare by lazy { CloudflareKiller() }
    private val site by lazy { OnlyKDramaChallenged(cloudflare) }
    // XCloud's server list (xcloud1.lol) has its own challenge; its landing page and the R2 files have none.
    private val xcloud by lazy { OnlyKDramaChallenged(cloudflare) }

    suspend fun get(url: String): NiceResponse = site.get(url)

    /** Search result page [page]; past the last page WordPress answers 404, which reads as no cards. */
    suspend fun search(query: String, page: Int = 1): Document? {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val response = site.get(if (page <= 1) "$SITE/?s=$q" else "$SITE/page/$page/?s=$q")
        return response.document.takeIf { response.code != 404 }
    }

    suspend fun loadLinks(links: OkLinks, name: String, callback: (ExtractorLink) -> Unit): Boolean {
        val hosts = NexdriveResolver(name)
        val sink = Sink(name, callback)
        links.files.amap { file ->
            val add = NexdriveSink { host, url, streamable, _ -> sink.add(file, host, url, streamable) }
            when (OnlyKDramaParser.host(file.url)) {
                Host.HUBCLOUD, Host.PIXELDRAIN -> hosts.resolve(file.url, add)
                Host.GDFLIX -> safely(name, "GDFlix") {
                    val pixeldrain = OnlyKDramaParser.gdflixPixeldrain(app.get(file.url, timeout = 30L).document) ?: return@safely
                    hosts.resolve(pixeldrain) { _, url, streamable, _ -> sink.add(file, "GDFlix Pixeldrain", url, streamable) }
                }
                Host.XCLOUD -> safely(name, "XCloud") { xcloud(file, name, sink) }
                Host.DIRECT -> sink.add(file, "Worker", file.url, streamable = true)
                Host.OTHER -> Unit
            }
        }
        return sink.isNotEmpty()
    }

    private suspend fun xcloud(file: OkFile, name: String, sink: Sink) {
        val landing = app.get(file.url, timeout = 30L)
        val servers = OnlyKDramaParser.xcloudServers(landing.document) ?: return
        val list = xcloud.get(servers, referer = landing.url)
        OnlyKDramaParser.xcloudMirrors(list.document, list.url).amap { (server, redirect) ->
            safely(name, "XCloud $server") {
                // With a saved clearance CloudflareKiller replays requests on a client that follows redirects, which
                // would start downloading the file; send its cookies by hand and read the Location instead.
                val headers = cloudflare.getCookieHeaders(redirect).toMap()
                val target = app.get(redirect, headers = headers, referer = servers, allowRedirects = false, timeout = 30L)
                    .headers["location"]?.takeIf { it.startsWith("http") } ?: return@safely
                sink.add(file, "XCloud $server", target, streamable = true)
            }
        }
    }

    /** Download-only links (Google `video-downloads`) cannot seek; they rank as Unknown so streams are picked first. */
    private class Sink(private val name: String, private val callback: (ExtractorLink) -> Unit) {
        private val emitted = Collections.synchronizedSet(HashSet<String>())

        fun isNotEmpty() = emitted.isNotEmpty()

        suspend fun add(file: OkFile, host: String, url: String, streamable: Boolean) {
            if (!emitted.add(url)) return
            val kind = if (streamable) "Stream" else "Download"
            val source = if (streamable) name else "$name Download"
            val label = listOfNotNull(host, OnlyKDramaParser.release(file.name), file.size).joinToString(" · ")
            val height = OnlyKDramaParser.quality(file.name).takeIf { it > 0 } ?: Qualities.Unknown.value
            callback(newExtractorLink(source, "$name [$kind] $label", url, ExtractorLinkType.VIDEO) {
                quality = if (streamable) height else Qualities.Unknown.value
            })
        }
    }
}

/**
 * A host behind a Cloudflare challenge. The first request of a while solves it alone; the rest share the cached cookie
 * two at a time, because CloudflareKiller sends a second request to the same host from inside its interceptor and
 * OkHttp allows only 5 per host: 5 parallel calls deadlock. A failed solve takes the WebView's full minute, so the
 * requests queued behind it fail at once for a while instead of each spending another minute on the same challenge.
 */
private class OnlyKDramaChallenged(private val cloudflare: CloudflareKiller) {
    @Volatile private var warmUntil = 0L
    @Volatile private var coldUntil = 0L
    private val lock = Mutex()
    private val slots = Semaphore(2)

    suspend fun get(url: String, referer: String? = null): NiceResponse {
        if (System.currentTimeMillis() >= warmUntil) lock.withLock {
            if (System.currentTimeMillis() >= warmUntil) {
                if (System.currentTimeMillis() < coldUntil) throw ErrorLoadingException("$FAILED [cold]")
                val start = System.currentTimeMillis()
                val response = app.get(url, referer = referer, interceptor = cloudflare, timeout = 60L)
                com.lagradost.api.Log.i("OnlyKDrama", "TRACE solve $url -> ${response.code} in ${System.currentTimeMillis() - start} ms")
                if (response.code in 200..299) warmUntil = System.currentTimeMillis() + WARM_MS
                else if (challenged(response)) coldUntil = System.currentTimeMillis() + COLD_MS
                return checked(response, "solve ${System.currentTimeMillis() - start}ms")
            }
        }
        return checked(slots.withPermit { app.get(url, referer = referer, interceptor = cloudflare, timeout = 60L) }, "replay")
    }

    private fun challenged(response: NiceResponse) =
        response.code in listOf(403, 503) && response.headers["server"].orEmpty().startsWith("cloudflare")

    private fun checked(response: NiceResponse, how: String): NiceResponse =
        if (challenged(response)) {
            val sent = response.okhttpResponse.request
            throw ErrorLoadingException("$FAILED [$how ${response.code} mit=${response.headers["cf-mitigated"]} ua=${sent.header("user-agent")?.takeLast(60)} " +
                "wvua=${com.lagradost.cloudstream3.network.WebViewResolver.webViewUserAgent?.takeLast(60)} ck=${sent.header("cookie")?.split(";")?.map { it.substringBefore("=").trim() }}]")
        } else response

    private companion object {
        const val WARM_MS = 10 * 60 * 1000L
        const val COLD_MS = 30 * 1000L
        const val FAILED = "Cloudflare check not passed, retry in a moment"
    }
}
