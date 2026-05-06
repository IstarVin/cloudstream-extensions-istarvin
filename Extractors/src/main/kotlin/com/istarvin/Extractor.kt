package com.istarvin

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink

class LuluVid : ExtractorApi() {
    override val name = "LuluStream"
    override val mainUrl = "https://luluvid.com"
    override val requiresReferer = false

    private val urlRegex = Regex("""sources.*file:"(.*)"""")

    private val headers = mapOf(
        "user-agent" to USER_AGENT,
        "accept-language" to "en-US,en;q=0.8"
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url, headers = headers).text
        val videoUrl = urlRegex.find(res)?.groupValues?.get(1) ?: return

        callback(newExtractorLink(name, name, videoUrl) {
            this.headers = this@LuluVid.headers
        })
    }
}

class Vidara : ExtractorApi() {
    override val name = "Vidara"
    override val mainUrl = "https://vidara.so"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val id = url.substringBefore('?').trimEnd('/').substringAfterLast('/')
        if (id.isBlank()) return null

        val res = app.post("https://vidara.so/api/stream", json = mapOf("filecode" to id))
            .parsedSafe<Result>() ?: return null
        return listOf(
            newExtractorLink(name, name, res.url)
        )
    }

    data class Result(
        @JsonProperty("streaming_url") val url: String
    )
}

class StreamRuby : ExtractorApi() {
    override val name = "StreamRuby"
    override val mainUrl = "https://rubyvidhub.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val id = url.substringBefore('?').trimEnd('/').substringAfterLast('/')
        if (id.isBlank()) return null

        val res = app.post(
            "$mainUrl/dl",
            data = mapOf("file_code" to id, "op" to "embed")
        ).text

        val unpackedBlocks = unpackBlocks(res)
        val allUrls = LinkedHashSet<String>()
        allUrls.addAll(extractCandidateUrls(res))
        unpackedBlocks.forEach { allUrls.addAll(extractCandidateUrls(it)) }

        val videoUrls = allUrls.filter { videoHintRe.containsMatchIn(it) }.distinct()
        val mediaUrls =
            allUrls.filter { mediaHintRe.containsMatchIn(it) && it !in videoUrls }.distinct()
        val directUrl = videoUrls.firstOrNull() ?: mediaUrls.firstOrNull() ?: return null

        return listOf(newExtractorLink(name, name, directUrl))
    }

    private val packedEvalRe = Regex(
        """(?:\}\)\(|\}\()\s*(['"])((?:\\.|[\s\S])*?)\1\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(['"])((?:\\.|[\s\S])*?)\5\.split\(\s*(['"])\|\7\s*,?\s*\)""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )
    private val filePropRe =
        Regex("""\bfile\s*:\s*(['"])(https?://[^"']+)\1""", RegexOption.IGNORE_CASE)
    private val urlRe = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
    private val videoHintRe = Regex(
        """(\.m3u8\b|\.mp4\b|\.mpd\b|/hls\b|/manifest\b|master\.m3u8)""",
        RegexOption.IGNORE_CASE
    )
    private val mediaHintRe = Regex("""\.(m3u8|mp4|mpd|vtt|webvtt)\b""", RegexOption.IGNORE_CASE)

    private fun unpackBlocks(text: String): List<String> {
        return packedEvalRe.findAll(text).mapNotNull { m ->
            val payloadRaw = m.groupValues[2]
            val base = m.groupValues[3].toIntOrNull() ?: return@mapNotNull null
            val count = m.groupValues[4].toIntOrNull() ?: return@mapNotNull null
            val dictRaw = m.groupValues[6]
            unpackPayload(payloadRaw, base, count, dictRaw)
        }.toList()
    }

    private fun unpackPayload(
        payloadRaw: String,
        base: Int,
        count: Int,
        dictionaryRaw: String
    ): String {
        var payload = decodeJsString(payloadRaw)
        val dictionary = decodeJsString(dictionaryRaw).split("|")
        for (index in count - 1 downTo 0) {
            if (index >= dictionary.size) continue
            val replacement = dictionary[index]
            if (replacement.isEmpty()) continue
            val token = toBase(index, base)
            payload = payload.replace(Regex("""\b${Regex.escape(token)}\b"""), replacement)
        }
        return payload
    }

    private fun extractCandidateUrls(text: String): List<String> {
        val out = LinkedHashSet<String>()
        filePropRe.findAll(text).forEach { out.add(it.groupValues[2]) }
        urlRe.findAll(text).forEach { out.add(it.value) }
        return out.toList()
    }

    private fun toBase(value: Int, base: Int): String {
        val digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        require(base in 2..digits.length) { "Unsupported base: $base" }
        if (value == 0) return "0"

        var current = value
        val out = StringBuilder()
        while (current > 0) {
            val remainder = current % base
            out.append(digits[remainder])
            current /= base
        }
        return out.reverse().toString()
    }

    private fun decodeJsString(raw: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '\\' || i + 1 >= raw.length) {
                out.append(c)
                i++
                continue
            }

            when (val n = raw[i + 1]) {
                '\\' -> {
                    out.append('\\'); i += 2
                }

                '\'' -> {
                    out.append('\''); i += 2
                }

                '"' -> {
                    out.append('"'); i += 2
                }

                'n' -> {
                    out.append('\n'); i += 2
                }

                'r' -> {
                    out.append('\r'); i += 2
                }

                't' -> {
                    out.append('\t'); i += 2
                }

                'b' -> {
                    out.append('\b'); i += 2
                }

                'f' -> {
                    out.append('\u000C'); i += 2
                }

                'u' -> {
                    if (i + 5 < raw.length) {
                        val hex = raw.substring(i + 2, i + 6)
                        val v = hex.toIntOrNull(16)
                        if (v != null) {
                            out.append(v.toChar()); i += 6
                        } else {
                            out.append('\\').append('u'); i += 2
                        }
                    } else {
                        out.append('\\').append('u'); i += 2
                    }
                }

                'x' -> {
                    if (i + 3 < raw.length) {
                        val hex = raw.substring(i + 2, i + 4)
                        val v = hex.toIntOrNull(16)
                        if (v != null) {
                            out.append(v.toChar()); i += 4
                        } else {
                            out.append('\\').append('x'); i += 2
                        }
                    } else {
                        out.append('\\').append('x'); i += 2
                    }
                }

                else -> {
                    out.append(n)
                    i += 2
                }
            }
        }
        return out.toString()
    }
}