package io.github.rufim.alice.daiteikoku

import java.io.File
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Кодек GD-сейвов System 4 (.asd) — порт asd_lib.py.
 * Формат: "GD\x01\x01" + u32(raw_size) + [зашифрованный] zlib-поток.
 * Шифр: если compressed[0] == 0x1a → mt19937_xorcode(key=0x12320f) (XOR, симметричен).
 * Правка числовых полей — на месте (длина raw не меняется → оффсеты секций целы).
 */
object AsdSave {
    private const val GD_KEY = 0x12320f

    /** MT19937 в варианте AliceSoft: init st[i] = 69069*st[i-1] (не стандартный). */
    private class Mt(seed: Int) {
        private val st = IntArray(624)
        private var idx = 624
        init {
            st[0] = seed
            for (i in 1 until 624) st[i] = (69069L * (st[i - 1].toLong() and 0xffffffffL)).toInt()
        }
        fun next(): Long {
            if (idx >= 624) {
                for (k in 0 until 624) {
                    val y = (st[k].toLong() and 0x80000000L) or (st[(k + 1) % 624].toLong() and 0x7fffffffL)
                    var v = st[(k + 397) % 624].toLong() xor (y ushr 1)
                    if (y and 1L != 0L) v = v xor 0x9908b0dfL
                    st[k] = v.toInt()
                }
                idx = 0
            }
            var y = st[idx++].toLong() and 0xffffffffL
            y = y xor (y ushr 11)
            y = y xor ((y shl 7) and 0x9d2c5680L)
            y = y xor ((y shl 15) and 0xefc60000L)
            y = y xor (y ushr 18)
            return y and 0xffffffffL
        }
    }

    private fun xor(buf: ByteArray): ByteArray {
        val mt = Mt(GD_KEY)
        val out = ByteArray(buf.size)
        for (i in buf.indices) out[i] = (buf[i].toInt() xor (mt.next() and 0xff).toInt()).toByte()
        return out
    }

    private fun u32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8) or
        ((b[off + 2].toInt() and 0xff) shl 16) or ((b[off + 3].toInt() and 0xff) shl 24)

    /** Прочитать сейв → (распакованный дамп кучи, был ли зашифрован). */
    fun read(file: File): Pair<ByteArray, Boolean> {
        val raw = file.readBytes()
        require(raw.size >= 8 && raw[0].toInt() == 'G'.code && raw[1].toInt() == 'D'.code &&
                raw[2].toInt() == 1 && raw[3].toInt() == 1) { "не GD-сейв" }
        val rawSize = u32(raw, 4)
        var comp = raw.copyOfRange(8, raw.size)
        val enc = comp.isNotEmpty() && (comp[0].toInt() and 0xff) == 0x1a
        if (enc) comp = xor(comp)
        val inf = Inflater()
        inf.setInput(comp)
        val out = ByteArray(rawSize)
        var n = 0
        while (!inf.finished() && n < rawSize) n += inf.inflate(out, n, rawSize - n)
        inf.end()
        require(n == rawSize) { "raw_size $rawSize != $n" }
        return out to enc
    }

    /** Записать сейв из дампа кучи (тем же способом шифрования). */
    fun write(file: File, d: ByteArray, enc: Boolean) {
        val def = Deflater(Deflater.BEST_COMPRESSION)
        def.setInput(d); def.finish()
        val buf = ByteArray(d.size + 1024)
        var z = ByteArray(0)
        while (!def.finished()) {
            val n = def.deflate(buf)
            z += buf.copyOfRange(0, n)
        }
        def.end()
        var outData = z
        if (enc) {
            outData = xor(outData)
            require((outData[0].toInt() and 0xff) == 0x1a) {
                "после шифрования первый байт != 0x1a (движок не расшифрует)"
            }
        }
        val hdr = byteArrayOf('G'.code.toByte(), 'D'.code.toByte(), 1, 1,
            (d.size and 0xff).toByte(), ((d.size shr 8) and 0xff).toByte(),
            ((d.size shr 16) and 0xff).toByte(), ((d.size shr 24) and 0xff).toByte())
        file.writeBytes(hdr + outData)
    }
}
