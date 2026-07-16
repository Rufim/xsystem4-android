package io.github.kichikuou.xsystem4

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/** Совпадает ли локаль голоса с русским (движки кодируют язык как ru/rus). */
private fun voiceMatchesRu(locale: Locale): Boolean {
    val lang = locale.language.lowercase(Locale.ROOT)
    return lang == "ru" || lang == "rus"
}

/** Разбивка строки на языковые сегменты и разбор имени говорящего. */
object TtsSegmenter {
    private fun langOf(c: Char): String? = when (c.code) {
        in 0x0400..0x04FF -> "ru"                        // кириллица
        in 0x3040..0x30FF, in 0x4E00..0x9FFF,
        in 0x31F0..0x31FF, in 0xFF66..0xFF9F -> "ja"     // кана/кандзи
        in 0x0041..0x005A, in 0x0061..0x007A,
        in 0xFF21..0xFF3A, in 0xFF41..0xFF5A -> "en"     // латиница (+полноширинная)
        else -> null                                     // цифры/знаки липнут к соседу
    }

    fun segments(text: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, StringBuilder>>()
        val pending = StringBuilder()   // знаки до первого языкового символа
        var cur: String? = null
        for (c in text) {
            val l = langOf(c)
            if (l != null && l != cur) {
                cur = l
                out.add(l to StringBuilder())
                if (pending.isNotEmpty()) {
                    out.last().second.append(pending)
                    pending.clear()
                }
            }
            if (out.isEmpty()) pending.append(c) else out.last().second.append(c)
        }
        return out.map { it.first to it.second.toString().trim() }
            .filter { it.second.isNotEmpty() }
    }

    /** Имя говорящего, если строка — маркер вида 【Имя】 (формат ADV-лога Daiteikoku). */
    fun speakerOf(line: String): String? {
        val t = line.trim()
        if (t.length >= 3 && t.first() == '【' && t.last() == '】')
            return t.substring(1, t.length - 1).trim().ifEmpty { null }
        return null
    }
}

/**
 * Очередь TTS: автоязык по сегментам, имя говорящего только при смене,
 * приглушение музыки на время речи (через NativeBridge.nativeDuckMusic).
 */
class TtsSpeaker(private val appContext: Context) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var ready = false
    @Volatile private var enabled = false
    @Volatile var duckMusic = false
    @Volatile var duckPercent = 15
    /** Авто-листание: когда всё прочитано, послать игре «дальше». */
    @Volatile var autoAdvance = false
    /** Как именно «листать» (задаёт активити: синтетический тап в SDL-поверхность). */
    var advance: () -> Unit = {}
    private var advanceGeneration = 0
    private var emitSeq = 0
    private companion object { const val MAX_ADVANCE_TAPS = 6 }
    @Volatile var paused = false
        private set
    private var utterance = 0
    private var lastSpeaker: String? = null
    private var pendingSpeaker: String? = null
    private var lastLineText: String? = null   // для возобновления после паузы
    private var activeUtterances = 0
    private var flushOnNextLine = false

    private var tts: TextToSpeech? = null
    private var currentEngine: String? = null
    private var ruVoiceName: String? = null
    @Volatile var speechRate = 1.0f
    @Volatile var speechPitch = 1.0f
    /** Колбэк готовности движка (для обновления списков в UI). */
    var onReady: (() -> Unit)? = null

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            main.post {
                advanceGeneration++   // отменить отложенную «прокачку» листания
                if (activeUtterances++ == 0 && duckMusic)
                    NativeBridge.duck(true, duckPercent)
            }
        }
        override fun onDone(utteranceId: String?) = finished(utteranceId)
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = finished(utteranceId)
        private fun finished(id: String?) {
            main.post {
                if (--activeUtterances <= 0) {
                    activeUtterances = 0
                    NativeBridge.duck(false, duckPercent)
                    scheduleAdvance()
                }
            }
        }
    }

    init { createEngine(null) }

    private fun createEngine(enginePkg: String?) {
        tts?.shutdown()
        ready = false
        currentEngine = enginePkg
        val cb = TextToSpeech.OnInitListener { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.setOnUtteranceProgressListener(progress)
                applyRuVoice()
                applyRatePitch()
                main.post { onReady?.invoke() }
            }
        }
        tts = if (enginePkg.isNullOrBlank()) TextToSpeech(appContext, cb)
              else TextToSpeech(appContext, cb, enginePkg)
    }

    // --- Настройки движка/голоса (для диалога TTS) ---

    fun engines(): List<TextToSpeech.EngineInfo> = tts?.engines ?: emptyList()

    fun currentEnginePackage(): String? = currentEngine

    fun setEngine(enginePkg: String?) {
        if (enginePkg == currentEngine && tts != null) return
        createEngine(enginePkg)
    }

    /** Русские голоса текущего движка (по локали). */
    fun ruVoices(): List<Voice> = runCatching {
        tts?.voices?.filter { voiceMatchesRu(it.locale) }?.sortedBy { it.name } ?: emptyList()
    }.getOrDefault(emptyList())

    fun currentRuVoiceName(): String? = ruVoiceName

    fun setRuVoice(name: String?) {
        ruVoiceName = name
        applyRuVoice()
    }

    private fun applyRuVoice() {
        val t = tts ?: return
        val name = ruVoiceName ?: return
        runCatching { t.voices?.firstOrNull { it.name == name } }.getOrNull()
            ?.let { t.voice = it }
    }

    /** Темп речи (0.5–2.0) и тон (0.5–2.0). Применяется сразу и при пересоздании движка. */
    fun setRate(rate: Float) { speechRate = rate; applyRatePitch() }
    fun setPitch(pitch: Float) { speechPitch = pitch; applyRatePitch() }

    private fun applyRatePitch() {
        tts?.setSpeechRate(speechRate)
        tts?.setPitch(speechPitch)
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        paused = false
        if (!on) {
            tts?.stop()
            main.post {
                activeUtterances = 0
                NativeBridge.duck(false, duckPercent)
            }
        }
    }

    /** Пауза: остановить речь, замереть (не читать/не листать). */
    fun pause() {
        paused = true
        advanceGeneration++
        tts?.stop()
        main.post {
            activeUtterances = 0
            NativeBridge.duck(false, duckPercent)
        }
    }

    /** Возобновить: перечитать текущий бокс и продолжить. */
    fun resume() {
        paused = false
        val t = lastLineText ?: return
        main.post {
            flushOnNextLine = true
            if (enqueue(t) == 0 && activeUtterances == 0) scheduleAdvance()
        }
    }

    /** Вызывается из потока VM (через NativeBridge). */
    fun speak(text: String) {
        if (!enabled || !ready || paused) return
        TtsSegmenter.speakerOf(text)?.let { pendingSpeaker = it; return }
        lastLineText = text
        main.post {
            emitSeq++   // пришёл новый текст от движка (для «прокачки» листания)
            pendingSpeaker?.let { name ->
                if (name != lastSpeaker) enqueue(name)
                lastSpeaker = name
                pendingSpeaker = null
            }
            // строку нечем озвучить (нет голоса/одни символы) — не стопорить авто-листание
            if (enqueue(text) == 0 && activeUtterances == 0)
                scheduleAdvance()
        }
    }

    /** Новая страница диалога: не дочитывать прошлое, начать с чистой очереди. */
    fun pageBreak() {
        if (!enabled || !ready) return
        main.post { flushOnNextLine = true }
    }

    /**
     * Авто-листание. Проблема: длинная реплика приходит движку одной строкой
     * (одна фраза TTS), но на экране может не влезть в бокс и показываться в
     * нескольких боксах — каждый требует своего тапа. TTS отговаривает один раз,
     * поэтому после чтения «прокачиваем» тапы: шлём тап, и если новый текст не
     * появился (перелистнулся внутренний бокс той же реплики) — шлём ещё, пока
     * не придёт новая реплика (её озвучка сама продолжит цепочку) или не исчерпан
     * лимит. advanceGeneration отменяет прокачку, когда началась новая речь.
     */
    private fun scheduleAdvance() {
        if (!autoAdvance || !enabled) return
        val gen = ++advanceGeneration
        main.postDelayed({ pumpAdvance(gen, 0) }, 500)
    }

    private fun pumpAdvance(gen: Int, taps: Int) {
        if (gen != advanceGeneration || !enabled || !autoAdvance) return
        if (activeUtterances > 0) return          // пошла новая речь — она продолжит
        if (taps >= MAX_ADVANCE_TAPS) return
        val before = emitSeq
        advance()                                  // один тап
        main.postDelayed({
            if (gen != advanceGeneration || !enabled || !autoAdvance) return@postDelayed
            if (emitSeq == before && activeUtterances == 0) {
                // новый текст не появился → это был внутренний бокс той же реплики
                pumpAdvance(gen, taps + 1)
            }
            // иначе появилась новая реплика — её onStart уже сбросил поколение
        }, 700)
    }

    /** @return сколько фраз реально встало в очередь TTS. */
    private fun enqueue(text: String): Int {
        val t = tts ?: return 0
        var queued = 0
        var mode = if (flushOnNextLine) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        flushOnNextLine = false
        for ((lang, chunk) in TtsSegmenter.segments(text)) {
            val ruVoice = if (lang == "ru" && ruVoiceName != null)
                runCatching { t.voices?.firstOrNull { it.name == ruVoiceName } }.getOrNull() else null
            if (ruVoice != null) {
                t.voice = ruVoice
            } else {
                t.language = when (lang) {
                    "ru" -> Locale("ru"); "ja" -> Locale.JAPANESE; else -> Locale.ENGLISH
                }
            }
            if (t.speak(chunk, mode, null, "adv${utterance++}") == TextToSpeech.SUCCESS)
                queued++
            mode = TextToSpeech.QUEUE_ADD
        }
        return queued
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
