package com.nropvox

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import com.nropvox.OnlyKDramaParser.Host
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * The onlykdrama.shop requests behind its Cloudflare challenge and the file hosts its posts link to. Shared by the
 * OnlyKDrama and OmniStream plugins.
 */
object OnlyKDramaSource {
    const val SITE = "https://onlykdrama.shop"
    private const val XCLOUD_BUDGET_MS = 25_000L

    // Serialize browser solves across both hosts without clearing other providers' WebView cookies.
    private val browserLock = Mutex()
    private val site = OnlyKDramaChallenged(browserLock)
    // XCloud's server list (xcloud1.lol) has its own challenge; its landing page and the R2 files have none.
    private val xcloud = OnlyKDramaChallenged(browserLock)

    suspend fun get(url: String): NiceResponse = site.get(url)

    /**
     * A post's page. Cloudflare sometimes refuses the saved clearance on the post pages while it still lets the search
     * and REST requests through, so the page falls back to the same post's body from the REST API; that body has the
     * download links but not the TMDB id.
     */
    suspend fun post(url: String): Document =
        try {
            site.get(url).document
        } catch (e: ErrorLoadingException) {
            val type = if (OnlyKDramaParser.isMovie(url)) "movies" else "tvshows"
            val slug = URLEncoder.encode(URI(url).path.trimEnd('/').substringAfterLast('/'), "UTF-8")
            val rest = site.get("$SITE/index.php?rest_route=/wp/v2/$type&slug=$slug&_fields=title,content")
            val post = rest.parsedSafe<Array<OkRestPost>>()?.firstOrNull() ?: throw e
            OnlyKDramaParser.restDocument(post, url) ?: throw e
        }

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
                // The other hosts answer in seconds. An unsolved XCloud challenge would hold loadLinks open until the
                // app's timeout, so XCloud gets a budget; Cloudflare clearance it earns meanwhile still serves the next play.
                Host.XCLOUD -> safely(name, "XCloud") { withTimeoutOrNull(XCLOUD_BUDGET_MS) { xcloud(file, name, sink) } }
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
                // Read only the redirect: following it would start downloading the video.
                val headers = xcloud.headers(redirect)
                val target = noFollow(redirect, headers, referer = servers)
                    .headers["location"]?.takeIf { it.startsWith("http") } ?: return@safely
                sink.add(file, "XCloud $server", target, streamable = true)
            }
        }
    }

    /** Download-only links (Google `video-downloads`) cannot seek; they list under a separate "Download" source so they can be ranked apart from streams. */
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
                quality = height
            })
        }
    }
}

/** A session per host. A rejected clearance is renewed once; queued requests share the result. */
private class OnlyKDramaChallenged(private val browserLock: Mutex) {
    private val lock = Mutex()
    private var coldUntil = 0L
    private val solver = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingSolve: Deferred<Unit>? = null
    private val acceptedHeaders = ConcurrentHashMap<String, Map<String, String>>()

    // Keep the last working session while a stricter path tries another check. A failed browser solve
    // must not discard clearance that still works on the archive, search or REST endpoints.
    fun headers(url: String): Map<String, String> = acceptedHeaders[URI(url).host] ?: browserHeaders(url)

    private fun browserHeaders(url: String): Map<String, String> = buildMap {
        WebViewResolver.webViewUserAgent?.let { put("User-Agent", it) }
        browserCookies(url).takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
    }

    suspend fun get(url: String, referer: String? = null): NiceResponse = lock.withLock {
        // Always try the requested path, even after a failed solve: REST/search may still be accessible.
        val response = request(url, referer)
        if (!challenged(response)) {
            remember(url, response)
            return@withLock response
        }
        if (System.currentTimeMillis() < coldUntil) throw ErrorLoadingException(FAILED)

        // XCloud's playback budget can expire before the browser is done. Keep one bounded solve alive
        // so the next play can use its clearance instead of restarting the challenge on every attempt.
        val solve = pendingSolve?.takeIf { it.isActive } ?: solver.async<Unit> {
            browserLock.withLock {
                // CloudflareKiller reuses a stale cf_clearance indefinitely. Expire only this host's clearance,
                // then let the browser actually visit the challenged URL with its default user agent.
                val rejected = OnlyKDramaParser.cookies(browserCookies(url))["cf_clearance"]
                expireClearance(url)
                WebViewResolver(
                    interceptUrl = Regex(".^"),
                    additionalUrls = listOf(Regex(".")),
                    userAgent = null,
                    useOkhttp = false,
                    timeout = 45_000L
                ).resolveUsingWebView(url, referer = referer) {
                    OnlyKDramaParser.freshClearance(browserCookies(url), rejected)
                }
            }
        }.also { pendingSolve = it }
        solve.await()
        // A cookie appearing is not proof of success: validate it using the same browser user agent.
        val retried = request(url, referer, fresh = true)
        if (challenged(retried)) {
            coldUntil = System.currentTimeMillis() + 30_000L
            throw ErrorLoadingException(FAILED)
        }
        coldUntil = 0L
        remember(url, retried)
        retried
    }

    private suspend fun request(url: String, referer: String?, fresh: Boolean = false) =
        app.get(url, headers = if (fresh) browserHeaders(url) else headers(url), referer = referer, timeout = 30L)

    private fun remember(url: String, response: NiceResponse) {
        val request = response.okhttpResponse.request
        if (OnlyKDramaParser.cookies(request.header("Cookie").orEmpty())["cf_clearance"] == null) return
        acceptedHeaders[URI(url).host] = buildMap {
            request.header("Cookie")?.let { put("Cookie", it) }
            request.header("User-Agent")?.let { put("User-Agent", it) }
        }
    }

    private fun challenged(response: NiceResponse) = OnlyKDramaParser.challenged(
        response.code, response.headers["server"], response.headers["cf-mitigated"], response.text
    )

    // Reflection keeps the shared OmniStream source usable in its desktop jar as well as on Android.
    private fun cookieManager(): Any? = try {
        Class.forName("android.webkit.CookieManager").getMethod("getInstance").invoke(null)
    } catch (_: Exception) {
        null
    }

    private fun browserCookies(url: String): String = try {
        cookieManager()?.let { it.javaClass.getMethod("getCookie", String::class.java).invoke(it, url) as? String }.orEmpty()
    } catch (_: Exception) {
        ""
    }

    private fun expireClearance(url: String) {
        val origin = URI(url).let { "${it.scheme}://${it.host}/" }
        val host = URI(url).host
        val manager = cookieManager() ?: return
        val setter = manager.javaClass.getMethod("setCookie", String::class.java, String::class.java)
        // Cover host-only and domain cookies without removing login/session cookies or other sites' clearances.
        val expired = "cf_clearance=deleted; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0; Path=/; Secure; SameSite=None"
        // Cloudflare now also uses CHIPS. Partitioned cookies need their own deletion in this top-level site.
        for (domain in listOf("", "; Domain=.$host")) {
            setter.invoke(manager, origin, expired + domain)
            setter.invoke(manager, origin, expired + domain + "; Partitioned")
        }
        manager.javaClass.getMethod("flush").invoke(manager)
    }

    private companion object {
        const val FAILED = "Cloudflare verification failed. Retry in a moment; the site may require an interactive browser check."
    }
}
