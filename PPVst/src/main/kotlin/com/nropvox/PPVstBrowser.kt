package com.nropvox

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.api.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * A hidden WebView that plays an embed page just long enough to see which HLS playlist its player asks for. Written
 * instead of using WebViewResolver because that one lets the WebView fetch the playlist (the token in the URL is bound
 * to the first client that fetches it, so the app's player would then be rejected), cannot lift the autoplay block and
 * cannot stop ad popups. Here the playlist request is answered with an empty 404 and its URL is handed back.
 */
object PPVstBrowser {
    private const val TAG = "PPVst"
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun empty() = WebResourceResponse("text/plain", "utf-8", 404, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private val skipExtensions = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg", ".css", ".woff", ".woff2", ".ttf", ".ico", ".mp4", ".webm")

    // Starts the player the way a tap would, without toggling it off when it is already running.
    private const val NUDGE = "try{var p=jwplayer();if(p.getState()!=='playing'&&p.getState()!=='buffering'){p.setMute(true);p.play();}}catch(e){}"

    /** The playlist URL the embed [url]'s player requests, or null when none shows up within [timeoutMs]. */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun sniffPlaylist(url: String, timeoutMs: Long): String? {
        val context = appContext ?: run {
            Log.w(TAG, "no context for the WebView")
            return null
        }
        val embedHost = PPVstParser.hostOf(url)
        return suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            var view: WebView? = null

            fun finish(result: String?) {
                if (!done.compareAndSet(false, true)) return
                main.post {
                    runCatching {
                        view?.stopLoading()
                        view?.destroy()
                    }
                    view = null
                }
                if (cont.isActive) cont.resume(result)
            }

            main.post {
                try {
                    val web = WebView(context)
                    view = web
                    web.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                    }
                    web.webChromeClient = WebChromeClient()
                    web.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val target = request.url.toString()
                            if (PPVstParser.isPlaylistUrl(target)) {
                                finish(target)
                                return empty()
                            }
                            val path = request.url.path.orEmpty().lowercase()
                            if (PPVstParser.isAdUrl(target) || skipExtensions.any { path.endsWith(it) }) return empty()
                            return null
                        }

                        // Popunders navigate the top frame away; stay on the embed host.
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                            request.url.host != embedHost

                        override fun onPageFinished(view: WebView, pageUrl: String?) {
                            if (done.get()) return
                            for (delay in longArrayOf(500, 2_000, 4_000, 7_000)) {
                                main.postDelayed({ if (!done.get()) runCatching { view.evaluateJavascript(NUDGE, null) } }, delay)
                            }
                        }
                    }
                    web.loadUrl(url)
                } catch (e: Exception) {
                    Log.w(TAG, "WebView failed: ${e.javaClass.simpleName} ${e.message}")
                    finish(null)
                }
            }
            main.postDelayed({ finish(null) }, timeoutMs)
            cont.invokeOnCancellation { finish(null) }
        }
    }
}
