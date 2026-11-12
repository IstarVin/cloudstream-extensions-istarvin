package com.nropvox

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.URI
import okio.ByteString.Companion.decodeBase64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The current Filemoon player uses an inexpensive proof of work and AES-GCM playback JSON. */
internal object Arc018Filemoon {
    private val mapper = jacksonObjectMapper()

    suspend fun resolve(embedUrl: String, referer: String): List<String> {
        val uri = URI(embedUrl)
        val code = uri.path.split('/').filter { it.isNotEmpty() }.getOrNull(1)
            ?.takeIf { it.matches(Regex("[a-zA-Z0-9]+")) } ?: return emptyList()
        val origin = "${uri.scheme}://${uri.authority}"
        val endpoint = "$origin/api/videos/$code/embed"
        val headers = mapOf(
            "Referer" to embedUrl,
            "Origin" to origin,
            "X-Embed-Origin" to (URI(referer).host ?: ""),
            "X-Embed-Referer" to referer,
        )
        val body = mapOf("fingerprint" to emptyMap<String, String>())
        var response = app.post("$endpoint/playback", json = body, headers = headers, timeout = 20L)
        if (response.code == 428) {
            val challenge = mapper.readTree(app.post("$endpoint/captcha", json = body, headers = headers, timeout = 20L).text)
            val nonce = challenge.path("pow_nonce").asText()
            val difficulty = challenge.path("pow_difficulty").asInt(-1)
            val solution = solve(nonce, difficulty) ?: return emptyList()
            val verified = mapper.readTree(app.post(
                "$endpoint/captcha/verify",
                json = mapOf("pow_token" to challenge.path("pow_token").asText(), "solution" to solution, "fingerprint" to emptyMap<String, String>()),
                headers = headers, timeout = 20L,
            ).text)
            val token = verified.path("token").asText().takeIf { verified.path("status").asText() == "ok" && it.isNotBlank() }
                ?: return emptyList()
            response = app.post("$endpoint/playback", json = body, headers = headers + ("X-Captcha-Token" to token), timeout = 20L)
        }
        if (response.code != 200) return emptyList()
        val playback = mapper.readTree(decrypt(response.text))
        return playback.path("sources").mapNotNull { source ->
            source.path("url").asText().takeIf { it.startsWith("https://") }
        }.distinct()
    }

    /** A version selects two one-based key parts; unrecognised versions use all parts. */
    fun decrypt(json: String): String {
        val envelope = mapper.readTree(json).path("playback")
        val parts = envelope.path("key_parts").map { it.asText() }
        val version = envelope.path("version").asText().toIntOrNull()
        val selected = if (version != null && version in 1..20 && version <= parts.size && 31 - version <= parts.size) {
            listOf(parts[version - 1], parts[30 - version])
        } else parts
        val key = selected.filter { it.isNotBlank() }.flatMap { decodeBase64Url(it).toList() }.toByteArray()
        require(key.size in listOf(16, 24, 32)) { "Unsupported Filemoon key" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, decodeBase64Url(envelope.path("iv").asText())))
        return cipher.doFinal(decodeBase64Url(envelope.path("payload").asText())).toString(Charsets.UTF_8)
    }

    private fun decodeBase64Url(value: String): ByteArray {
        return requireNotNull(value.decodeBase64()) { "Invalid Filemoon Base64 payload" }.toByteArray()
    }

    /** Its advertised SHA256 name is stale: the shipped player uses this memory-mixing hash. */
    fun hash(input: String): IntArray {
        val state = intArrayOf(1779033703, -1150833019, 1013904242, -1521486534)
        fun round() {
            state[0] += state[1]
            state[3] = Integer.rotateLeft(state[3] xor state[0], 16)
            state[2] += state[3]
            state[1] = Integer.rotateLeft(state[1] xor state[2], 12)
            state[0] += state[1]
            state[3] = Integer.rotateLeft(state[3] xor state[0], 8)
            state[2] += state[3]
            state[1] = Integer.rotateLeft(state[1] xor state[2], 7)
        }
        for (char in input) {
            state[0] += char.code and 255
            state[0] = Integer.rotateLeft(state[0], 7)
            round()
        }
        repeat(8) { round() }
        val memory = IntArray(512) {
            round()
            state[0] xor state[2]
        }
        repeat(2) {
            for (i in memory.indices) {
                val selected = memory[i] and 511
                var value = memory[i] + memory[selected]
                value = Integer.rotateLeft(value, 13)
                value = value xor (memory[(i + 1) and 511] * -1640531535)
                memory[i] = value
                state[0] = state[0] xor value
                round()
            }
        }
        return IntArray(8) { block ->
            round()
            var value = state[0]
            repeat(64) { offset ->
                val word = memory[block * 64 + offset]
                value += word
                value = Integer.rotateLeft(value, 5)
                value = value xor (word * -2048144777)
            }
            value xor state[2]
        }
    }

    fun leadingZeroBits(words: IntArray): Int {
        var count = 0
        for (word in words) {
            count += Integer.numberOfLeadingZeros(word)
            if (word != 0) break
        }
        return count
    }

    suspend fun solve(nonce: String, difficulty: Int, timeoutMs: Long = 20_000): String? = withContext(Dispatchers.Default) {
        // Reject unreasonable challenges; cancellation is checked between small batches.
        if (nonce.isBlank() || nonce.length > 256 || difficulty !in 0..20 || timeoutMs <= 0) return@withContext null
        if (difficulty == 0) return@withContext "0"
        val started = System.nanoTime()
        for (candidate in 0 until 1_000_000) {
            if (candidate % 128 == 0) {
                currentCoroutineContext().ensureActive()
                if ((System.nanoTime() - started) / 1_000_000 >= timeoutMs) return@withContext null
            }
            if (leadingZeroBits(hash("$nonce:$candidate")) >= difficulty) return@withContext candidate.toString()
        }
        null
    }
}
