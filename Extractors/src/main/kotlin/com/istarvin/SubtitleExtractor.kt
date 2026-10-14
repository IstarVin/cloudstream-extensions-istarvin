package com.istarvin

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.api.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.net.URI

class SubtitleCat : ExtractorApi() {
    override val name = "SubtitleCat"
    override val mainUrl = "https://subtitlecat.com"
    override val requiresReferer = false

    private fun String.normalize(): String {
        return this.filter { c -> c.isLetterOrDigit() }.lowercase()
    }

    private val codeRegex = Regex("""[a-z]+-\d+""", RegexOption.IGNORE_CASE)

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val query = url.substringAfter("query=").let { codeRegex.find(it)?.value } ?: return
        val queryUrl = "${mainUrl}/index.php?search=$query"
        val doc = app.get(queryUrl).document
        val subs = doc.select(".sub-table a")
            .map { URI("$mainUrl/").resolve(it.attr("href")).toString() }
            .filter {
                it.normalize().contains(query.normalize())
            }
            .distinct()
            .take(3)

        coroutineScope {
            subs.forEach { subUrl ->
                launch(Dispatchers.IO) {
                    try {
                        val subPageDoc = app.get(subUrl).document
                        val href = subPageDoc.getElementById("download_en")?.attr("href")
                            ?.takeIf { it.isNotBlank() } ?: return@launch
                        val subtitleUrl = URI(subUrl).resolve(href).toString()
                        subtitleCallback(newSubtitleFile("English", subtitleUrl))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("SubtitleCat", "Subtitle request failed: ${e.message}")
                    }
                }
            }
        }
    }
}
