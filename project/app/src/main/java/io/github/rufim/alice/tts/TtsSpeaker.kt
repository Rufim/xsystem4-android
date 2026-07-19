package io.github.rufim.alice.tts

import io.github.rufim.alice.R
import io.github.rufim.alice.NativeBridge
import io.github.rufim.alice.tts.*
import io.github.rufim.alice.cheats.*
import io.github.rufim.alice.launcher.*
import io.github.rufim.alice.engine.*
import io.github.rufim.alice.ui.*

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/** Языки контента (коды сегментатора) — для них голос реально применяется. */
val TTS_LANGS = listOf("ru", "ja", "en")

/** Частые ISO-639-2 (3-буквенные) коды, которые Android отдаёт для голосов,
 *  → ISO-639-1 (2-буквенные), чтобы совпадать с кодами сегментатора. */
private val ISO3TO1 = mapOf(
    "rus" to "ru", "jpn" to "ja", "eng" to "en", "kor" to "ko", "zho" to "zh",
    "chi" to "zh", "fra" to "fr", "fre" to "fr", "deu" to "de", "ger" to "de",
    "spa" to "es", "ita" to "it", "por" to "pt", "ukr" to "uk", "pol" to "pl",
    "nld" to "nl", "dut" to "nl", "tur" to "tr", "ara" to "ar", "hin" to "hi",
    "vie" to "vi", "tha" to "th", "ind" to "id", "ces" to "cs", "ell" to "el",
    "swe" to "sv", "dan" to "da", "fin" to "fi", "nor" to "nb", "ron" to "ro",
    "hun" to "hu", "heb" to "he"
)

/** Нормализованный код языка голоса (ISO-639-1, если известен). */
fun normLang(code: String?): String {
    val c = (code ?: "").lowercase(Locale.ROOT)
    return ISO3TO1[c] ?: c
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
 * Профиль поведения озвучки — у движков разная механика диалогов:
 *
 * System 4: реплика приходит целиком до отображения; длинная реплика листается
 * по внутренним боксам «пустыми» тапами (глифы не перерисовываются), а модальные
 * уведомления рисуют текст посимвольно (NewFont) — прокачку тапов останавливает
 * рост uiDrawCount. Завершение чтения — по счётчику реплик (onStart/onDone
 * приходят парно).
 *
 * System 3.x: бокс приходит построчно и флашится одной репликой; счётчик
 * реплик ненадёжен (движок TTS теряет onDone промежуточной реплики) — конец
 * чтения определяется по isSpeaking с дебаунсом; модалко-защиты нет
 * (uiDrawCount всегда 0).
 */
data class TtsProfile(
    /** Конец чтения по isSpeaking-дебаунсу (S3.x) вместо счётчика реплик (S4). */
    val settleByIsSpeaking: Boolean,
    /** Останавливать прокачку тапов при росте uiDrawCount (модалка; только S4). */
    val modalGuard: Boolean,
    /** Максимум тапов прокачки одной реплики. */
    val maxAdvanceTaps: Int,
) {
    companion object {
        val SYSTEM4 = TtsProfile(settleByIsSpeaking = false, modalGuard = true, maxAdvanceTaps = 6)
        val SYSTEM35 = TtsProfile(settleByIsSpeaking = true, modalGuard = false, maxAdvanceTaps = 3)
    }
}

/**
 * Очередь TTS: автоязык по сегментам, имя говорящего только при смене,
 * приглушение музыки на время речи (через NativeBridge.nativeDuckMusic).
 */
class TtsSpeaker(private val appContext: Context) {
    /** Движко-специфичное поведение (задаёт активити до включения озвучки). */
    var profile: TtsProfile = TtsProfile.SYSTEM4
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
    private companion object {
        // за 700 мс модалка перерисовывает десятки глифов (в тесте ~80); обычный
        // «пустой» тап — 0. Порог 20 отделяет модалку от мелких перерисовок UI.
        const val UI_MODAL_DRAW_THRESHOLD = 20
    }
    @Volatile var stopped = false
        private set
    /** Уведомить UI, что чтение остановлено (напр. игровым «ПРОПУСК») — обновить кнопку. */
    var onStoppedChanged: (() -> Unit)? = null
    private var utterance = 0
    private var lastSpeaker: String? = null
    private var lastLineText: String? = null   // фраза для «плей» после стопа
    private var activeUtterances = 0
    private var flushOnNextLine = false

    private var tts: TextToSpeech? = null
    private var currentEngine: String? = null
    /** Выбранный голос на каждый язык (ru/ja/en) — имя Voice.name. */
    private val langVoice = HashMap<String, String>()
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
                if (activeUtterances > 0) activeUtterances--
                if (profile.settleByIsSpeaking) {
                    // S3.x: движок TTS теряет onDone промежуточной реплики — счётчик
                    // ненадёжен, конец чтения определяем по isSpeaking с дебаунсом.
                    main.postDelayed({
                        if (tts?.isSpeaking == true) return@postDelayed
                        activeUtterances = 0
                        NativeBridge.duck(false, duckPercent)
                        scheduleAdvance()
                    }, 250)
                } else {
                    // S4: onStart/onDone парные — завершение по счётчику (без задержки,
                    // прокачка внутренних боксов длинной реплики стартует сразу).
                    if (activeUtterances <= 0) {
                        activeUtterances = 0
                        NativeBridge.duck(false, duckPercent)
                        scheduleAdvance()
                    }
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

    /** Голоса текущего движка для языка lang (нормализованный код). */
    fun voicesForLang(lang: String): List<Voice> = runCatching {
        tts?.voices?.filter { normLang(it.locale?.language) == lang }?.sortedBy { it.name } ?: emptyList()
    }.getOrDefault(emptyList())

    /** Все языки, для которых у движка есть голоса (нормализованные коды, по алфавиту). */
    fun availableLanguages(): List<String> = runCatching {
        tts?.voices?.mapNotNull { it.locale?.language?.takeIf { l -> l.isNotBlank() } }
            ?.map { normLang(it) }?.distinct()?.sorted() ?: emptyList()
    }.getOrDefault(emptyList())

    fun voiceForLang(lang: String): String? = langVoice[lang]

    fun setVoiceForLang(lang: String, name: String?) {
        if (name.isNullOrBlank()) langVoice.remove(lang) else langVoice[lang] = name
        // Голос применяется по-сегментно в enqueue (у каждой реплики свой язык).
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
        stopped = false
        if (!on) {
            tts?.stop()
            main.post {
                activeUtterances = 0
                NativeBridge.duck(false, duckPercent)
            }
        }
    }

    /** Стоп: полностью очистить очередь, остановить движок, замереть. */
    fun stop() {
        val was = stopped
        stopped = true
        advanceGeneration++
        tts?.stop()
        main.post {
            activeUtterances = 0
            NativeBridge.duck(false, duckPercent)
        }
        if (!was) main.post { onStoppedChanged?.invoke() }
    }

    /** Плей: читать с последней сохранённой фразы. */
    fun play() {
        stopped = false
        main.post { onStoppedChanged?.invoke() }
        val t = lastLineText ?: return
        main.post {
            flushOnNextLine = true
            if (enqueue(t) == 0 && activeUtterances == 0) scheduleAdvance()
        }
    }

    /** Вызывается из потока VM (через AdvRouter): реплика с уже разобранным
     *  говорившим. Имя озвучивается только при смене говорившего. */
    fun speak(speaker: String?, text: String) {
        lastLineText = text   // помним текущий бокс даже в стопе (для «плей»)
        if (!enabled || !ready || stopped) return
        main.post {
            emitSeq++   // пришёл новый текст от движка (для «прокачки» листания)
            if (speaker != null) {
                if (speaker != lastSpeaker) enqueue(speaker)
                lastSpeaker = speaker
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
        if (taps >= profile.maxAdvanceTaps) return
        val before = emitSeq
        val uiBefore = NativeBridge.uiDrawCount()
        advance()                                  // один тап
        main.postDelayed({
            if (gen != advanceGeneration || !enabled || !autoAdvance) return@postDelayed
            if (emitSeq != before || activeUtterances > 0) {
                // появилась новая реплика — её озвучка продолжит цепочку
                return@postDelayed
            }
            // Новой реплики нет. S4: если на экране активно рисуется текст (модалка-
            // уведомление вроде «Была схвачена …») — НЕ листаем её тапом, замираем:
            // пользователь прочтёт и закроет сам, дальше пойдёт обычный диалог.
            if (profile.modalGuard &&
                NativeBridge.uiDrawCount() - uiBefore > UI_MODAL_DRAW_THRESHOLD)
                return@postDelayed
            // иначе это был внутренний бокс той же реплики — листаем дальше
            pumpAdvance(gen, taps + 1)
        }, 700)
    }

    /** @return сколько фраз реально встало в очередь TTS. */
    private fun enqueue(text: String): Int {
        val t = tts ?: return 0
        var queued = 0
        var mode = if (flushOnNextLine) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        flushOnNextLine = false
        for ((lang, chunk) in TtsSegmenter.segments(text)) {
            val voiceName = langVoice[lang]
            val voice = if (voiceName != null)
                runCatching { t.voices?.firstOrNull { it.name == voiceName } }.getOrNull() else null
            if (voice != null) {
                t.voice = voice
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
