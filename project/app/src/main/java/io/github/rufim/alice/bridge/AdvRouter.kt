package io.github.rufim.alice.bridge

import android.os.Handler
import android.os.Looper
import io.github.rufim.alice.tts.TtsSegmenter

/**
 * Единая точка разбора потока ADV-текста от движков (через NativeBridge).
 * Маркер говорившего `【Имя】` приходит отдельной строкой перед репликой
 * (System 4); здесь он склеивается с ней, и подписчики (озвучка, история)
 * получают готовую пару (speaker, text).
 *
 * System 4 отдаёт текст бокса ПОСТРОЧНО (каждая видимая строка — отдельный
 * add_text): при включённом [coalesceLines] строки копятся и отдаются одной
 * репликой, когда бокс дорисован (пауза текста) или пришла новая страница —
 * иначе TTS читает с паузами после каждой строки. System 3.x склеивает бокс
 * уже в нативном мосте — там режим выключен.
 */
object AdvRouter {
    private const val FLUSH_MS = 300L

    /** Подписчик реплик; null speaker — имя неизвестно (System 3.x, рассказчик). */
    var onLine: ((speaker: String?, text: String) -> Unit)? = null
    /** Новая страница диалога. */
    var onPage: (() -> Unit)? = null
    /** Склеивать построчный текст бокса в одну реплику (System 4). */
    var coalesceLines = false

    private val main = Handler(Looper.getMainLooper())
    private var pendingSpeaker: String? = null
    private val buf = StringBuilder()
    private val flushRunnable = Runnable { flush() }

    fun text(raw: String) {
        TtsSegmenter.speakerOf(raw)?.let {
            pendingSpeaker = it
            return
        }
        if (!coalesceLines) {
            deliver(raw)
            return
        }
        synchronized(buf) {
            if (buf.isNotEmpty()) buf.append(' ')
            buf.append(raw)
        }
        main.removeCallbacks(flushRunnable)
        main.postDelayed(flushRunnable, FLUSH_MS)
    }

    fun page() {
        // дочитать недофлашенный бокс до сигнала новой страницы
        main.removeCallbacks(flushRunnable)
        flush()
        onPage?.invoke()
    }

    private fun flush() {
        val text = synchronized(buf) {
            val t = buf.toString()
            buf.setLength(0)
            t
        }
        if (text.isNotEmpty()) deliver(text)
    }

    private fun deliver(text: String) {
        val speaker = pendingSpeaker
        pendingSpeaker = null
        onLine?.invoke(speaker, text)
    }
}
