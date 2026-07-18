package io.github.rufim.alice.bridge

import io.github.rufim.alice.tts.TtsSegmenter

/**
 * Единая точка разбора потока ADV-текста от движков (через NativeBridge).
 * Маркер говорившего `【Имя】` приходит отдельной строкой перед репликой
 * (System 4); здесь он склеивается с ней, и подписчики (озвучка, история)
 * получают готовую пару (speaker, text). Колбэки зовутся на потоке VM.
 */
object AdvRouter {
    /** Подписчик реплик; null speaker — имя неизвестно (System 3.x, рассказчик). */
    var onLine: ((speaker: String?, text: String) -> Unit)? = null
    /** Новая страница диалога. */
    var onPage: (() -> Unit)? = null

    private var pendingSpeaker: String? = null

    fun text(raw: String) {
        TtsSegmenter.speakerOf(raw)?.let {
            pendingSpeaker = it
            return
        }
        val speaker = pendingSpeaker
        pendingSpeaker = null
        onLine?.invoke(speaker, raw)
    }

    fun page() {
        onPage?.invoke()
    }
}
