package com.nropvox

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * tim-streams.com hides its HLS segments on TikTok's image CDN: each "segment" is a PNG whose RGB pixel bytes (row
 * major) hold `KWP1`, a big-endian u32 length and then that many bytes of MPEG-TS. This reads such a PNG by hand
 * (zlib inflate and PNG unfiltering) instead of through `BitmapFactory`, which would cost a 2 MB bitmap per segment.
 * Reference: `png_segment_to_ts.py` in the recon folder.
 */
object TimStreamsSegment {
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val MAGIC = byteArrayOf('K'.code.toByte(), 'W'.code.toByte(), 'P'.code.toByte(), '1'.code.toByte())

    /** The MPEG-TS inside a wrapped segment, or null when [png] isn't one (error page, other format, truncated). */
    fun unwrap(png: ByteArray): ByteArray? {
        val pixels = pixels(png) ?: return null
        if (pixels.size < 8 || !pixels.copyOfRange(0, 4).contentEquals(MAGIC)) return null
        val length = readU32(pixels, 4)
        if (length <= 0 || length > pixels.size - 8L) return null
        val ts = pixels.copyOfRange(8, 8 + length.toInt())
        return if (ts[0] == 0x47.toByte()) ts else null
    }

    /**
     * The decoded RGB bytes of an 8-bit, non-interlaced truecolor PNG (colour type 2, or 6 with the alpha dropped),
     * row after row without filter bytes. Null for any other PNG.
     */
    fun pixels(png: ByteArray): ByteArray? {
        if (png.size < 33 || !png.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) return null
        var width = 0
        var height = 0
        var channels = 0
        val idat = ByteArrayOutputStream(png.size)
        var pos = 8
        while (pos + 12 <= png.size) {
            val length = readU32(png, pos)
            if (length < 0 || pos + 12 + length > png.size) break
            val type = String(png, pos + 4, 4, Charsets.ISO_8859_1)
            val body = pos + 8
            when (type) {
                "IHDR" -> {
                    width = readU32(png, body).toInt()
                    height = readU32(png, body + 4).toInt()
                    val depth = png[body + 8].toInt()
                    val colour = png[body + 9].toInt()
                    val interlace = png[body + 12].toInt()
                    if (depth != 8 || interlace != 0) return null
                    channels = when (colour) {
                        2 -> 3
                        6 -> 4
                        else -> return null
                    }
                }
                "IDAT" -> idat.write(png, body, length.toInt())
                "IEND" -> break
            }
            pos += 12 + length.toInt()
        }
        if (width <= 0 || height <= 0 || channels == 0 || idat.size() == 0) return null

        val stride = width * channels
        val raw = ByteArray((stride + 1) * height)
        val inflater = Inflater()
        try {
            inflater.setInput(idat.toByteArray())
            var filled = 0
            while (filled < raw.size) {
                val n = inflater.inflate(raw, filled, raw.size - filled)
                if (n == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break
                filled += n
            }
            if (filled < raw.size) return null
        } catch (e: java.util.zip.DataFormatException) {
            return null
        } finally {
            inflater.end()
        }

        val out = ByteArray(stride * height)
        unfilter(raw, out, stride, channels, height) ?: return null
        if (channels == 3) return out
        val rgb = ByteArray(width * height * 3)
        var j = 0
        for (i in out.indices step 4) {
            rgb[j++] = out[i]; rgb[j++] = out[i + 1]; rgb[j++] = out[i + 2]
        }
        return rgb
    }

    /** Reverses the five PNG row filters. [raw] has a filter-type byte in front of every row. */
    private fun unfilter(raw: ByteArray, out: ByteArray, stride: Int, bpp: Int, height: Int): Unit? {
        for (y in 0 until height) {
            val filter = raw[y * (stride + 1)].toInt()
            val src = y * (stride + 1) + 1
            val dst = y * stride
            val up = dst - stride
            for (x in 0 until stride) {
                val value = raw[src + x].toInt() and 0xFF
                val a = if (x >= bpp) out[dst + x - bpp].toInt() and 0xFF else 0
                val b = if (y > 0) out[up + x].toInt() and 0xFF else 0
                val c = if (y > 0 && x >= bpp) out[up + x - bpp].toInt() and 0xFF else 0
                val predicted = when (filter) {
                    0 -> 0
                    1 -> a
                    2 -> b
                    3 -> (a + b) ushr 1
                    4 -> paeth(a, b, c)
                    else -> return null
                }
                out[dst + x] = (value + predicted).toByte()
            }
        }
        return Unit
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    private fun readU32(bytes: ByteArray, at: Int): Long =
        ((bytes[at].toLong() and 0xFF) shl 24) or ((bytes[at + 1].toLong() and 0xFF) shl 16) or
            ((bytes[at + 2].toLong() and 0xFF) shl 8) or (bytes[at + 3].toLong() and 0xFF)
}
