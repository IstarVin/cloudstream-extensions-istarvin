package com.istarvin

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

object SubtitleCat {
    const val ORIGIN = "https://subtitlecat.com"

    suspend fun fetchSubtitles(
        query: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val queryUrl = "${ORIGIN}/index.php?search=$query"
        val doc = app.get(queryUrl).document
        val subs = doc.select(".sub-table a")
            .map { ORIGIN + '/' + it.attr("href") }
            .take(3)

        coroutineScope {
            subs.map { url ->
                async {
                    val subPageDoc = app.get(url).document
                    val href =
                        subPageDoc.getElementById("download_en")?.attr("href") ?: return@async null

                    subtitleCallback(newSubtitleFile("English", ORIGIN + href))
                }
            }
        }
    }
}