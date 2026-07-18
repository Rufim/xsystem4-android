package io.github.rufim.alice.engine

import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import io.github.rufim.alice.NativeBridge
import io.github.rufim.alice.bridge.AdvRouter
import io.github.rufim.alice.cheats.CheatSession
import io.github.rufim.alice.cheats.CheatsScreen
import io.github.rufim.alice.history.HistoryScreen
import io.github.rufim.alice.history.MessageHistory
import io.github.rufim.alice.tts.NameTranslator
import io.github.rufim.alice.tts.TTS_LANGS
import io.github.rufim.alice.tts.TtsSpeaker
import io.github.rufim.alice.ui.ComposeOverlay
import org.libsdl.app.SDLActivity

/**
 * Общий базовый класс игровых активити обоих движков: SDL-жизненный цикл,
 * связка NativeBridge → AdvRouter → озвучка/история и Compose-оверлей
 * (панель, экраны настроек/истории/читов). Подклассы задают нативную
 * библиотеку, аргументы запуска и движко-специфичные кнопки/фильтры.
 *
 * Intent обязан содержать extras:
 * - EXTRA_GAME_ROOT (string): путь к каталогу установленной игры.
 * - EXTRA_SAVE_DIR  (string): путь к каталогу сейвов.
 */
abstract class EngineActivity : SDLActivity() {
    companion object {
        const val EXTRA_GAME_ROOT = "GAME_ROOT"
        const val EXTRA_SAVE_DIR = "SAVE_DIR"
    }

    protected lateinit var tts: TtsSpeaker
    protected lateinit var prefs: SharedPreferences
    private val panelState = EnginePanelState()
    private var nameTranslator: NameTranslator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Workaround for https://github.com/libsdl-org/SDL/issues/8995
        SDLActivity.setWindowStyle(true)
        prefs = getSharedPreferences("bridge_prefs", 0)
        setupBridge()
        setupOverlay()
    }

    /** Поток текста и восстановление сохранённых настроек озвучки. */
    private fun setupBridge() {
        tts = TtsSpeaker(this)
        AdvRouter.onLine = { speaker, text ->
            MessageHistory.add(speaker, text)
            tts.speak(speaker, text)
        }
        AdvRouter.onPage = { tts.pageBreak() }
        NativeBridge.init(
            { text, _ -> AdvRouter.text(text) },
            { AdvRouter.page() },
            { state -> if (state != 0) tts.stop() })   // игровой «ПРОПУСК» → стоп чтения

        tts.duckMusic = prefs.getBoolean("duck", true)
        tts.duckPercent = prefs.getInt("duck_pct", 15)
        prefs.getString("tts_engine", null)?.let { tts.setEngine(it) }
        for (lang in TTS_LANGS)
            prefs.getString("tts_voice_$lang", null)?.let { tts.setVoiceForLang(lang, it) }
        tts.setRate(prefs.getInt("tts_rate", 100) / 100f)
        tts.setPitch(prefs.getInt("tts_pitch", 100) / 100f)
        tts.autoAdvance = prefs.getBoolean("auto_advance", false)
        tts.advance = { advanceGame() }
        if (supportsTextFilters) {
            applySuppressPages(prefs.getString("tts_suppress_pages", defaultSuppressPages())!!)
            applyReadWindows(prefs.getString("tts_read_windows", defaultReadWindows())!!)
            NativeBridge.setWindowListener { w, p ->
                runOnUiThread { panelState.indicator = "стр $p · окно $w" }
            }
        }

        val ttsOn = prefs.getBoolean("tts", false)
        panelState.ttsOn = ttsOn
        tts.onStoppedChanged = { runOnUiThread { panelState.ttsStopped = tts.stopped } }
        NativeBridge.setTts(ttsOn)
        tts.setEnabled(ttsOn)

        // сохранённый режим перевода имён в читах
        CheatSession.translateNames = prefs.getBoolean("translate_names", false)
        if (CheatSession.translateNames) enableNameTranslation(true)
    }

    /** Панель и плавающие элементы поверх игры. */
    private fun setupOverlay() {
        val actions = buildList {
            add(PanelAction("Озвучка (TTS)") { showTtsSettings() })
            add(PanelAction("История") { showHistory() })
            add(PanelAction("Читы") { showCheats() })
            addAll(extraPanelActions())
        }
        ComposeOverlay.attach(mLayout) {
            EnginePanelOverlay(
                state = panelState,
                actions = actions,
                onPlayPause = { if (tts.stopped) tts.play() else tts.stop() },
            )
        }
    }

    private fun showTtsSettings() {
        ComposeOverlay.showFullscreen(this) { dismiss ->
            TtsSettingsScreen(
                tts = tts,
                prefs = prefs,
                supportsTextFilters = supportsTextFilters,
                onTtsToggled = { on -> panelState.ttsOn = on },
                applySuppressPages = { applySuppressPages(it) },
                applyReadWindows = { applyReadWindows(it) },
                onBack = dismiss,
            )
        }
    }

    private fun showHistory() {
        ComposeOverlay.showFullscreen(this) { dismiss -> HistoryScreen(onBack = dismiss) }
    }

    private fun showCheats() {
        if (CheatSession.rows.isEmpty()) CheatSession.reload()
        ComposeOverlay.showFullscreen(this) { dismiss ->
            CheatsScreen(
                onTranslateToggle = { on ->
                    prefs.edit().putBoolean("translate_names", on).apply()
                    enableNameTranslation(on)
                },
                onBack = dismiss,
            )
        }
    }

    /** Включить/выключить ML Kit-перевод имён переменных в читах. */
    private fun enableNameTranslation(on: Boolean) {
        if (!on) {
            CheatSession.translator = null
            CheatSession.reload()
            return
        }
        val translator = nameTranslator ?: NameTranslator().also { nameTranslator = it }
        translator.ensureModel { ready ->
            if (ready) {
                CheatSession.translator = { name, cb -> translator.translate(name, cb) }
                CheatSession.reload()
            } else {
                runOnUiThread {
                    CheatSession.translateNames = false
                    Toast.makeText(this, "Модель перевода недоступна (нет сети/сервисов Google)",
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // --- Точки расширения для движков ---

    /** Дополнительные кнопки боковой панели (напр. «Меню движка» у System 3.x). */
    protected open fun extraPanelActions(): List<PanelAction> = emptyList()

    /** Как «листать» диалог при авто-режиме. По умолчанию — синтетический тап
     *  (для xsystem4; SDL-клавиши там не листают). Подклассы могут переопределить. */
    protected open fun advanceGame() = synthesizeTap()

    /** Не озвучивать текст на этих сценарных страницах (меню/бой). */
    protected open fun applySuppressPages(pages: String) {}

    /** Значение «не читать страницы» по умолчанию (для конкретного движка/игры). */
    protected open fun defaultSuppressPages(): String = ""

    /** Читать только эти окна сообщений (пусто = все). */
    protected open fun applyReadWindows(csv: String) {}

    /** Окна для озвучки по умолчанию. */
    protected open fun defaultReadWindows(): String = ""

    /** Есть ли у движка фильтры текста по страницам/окнам (поля в настройках,
     *  индикатор «стр·окно»). Только System 3.x. */
    protected open val supportsTextFilters: Boolean get() = false

    /** Синтетический тап в центр игровой поверхности — «дальше» в диалоге
     *  (тот же путь, что палец; SDL-клавиши/SDL_PushEvent игру на Android не листают).
     *  UP шлём с реальной паузой: движок откладывает применение состояния касания
     *  (TOUCH_EVENT_DELAY), поэтому мгновенный down+up теряется на опросе keywait. */
    private fun synthesizeTap() {
        val surface = mSurface ?: return
        val x = surface.width * 0.5f
        val y = surface.height * 0.5f
        val t0 = android.os.SystemClock.uptimeMillis()
        val down = android.view.MotionEvent.obtain(
            t0, t0, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
        surface.dispatchTouchEvent(down)
        down.recycle()
        surface.postDelayed({
            val t1 = android.os.SystemClock.uptimeMillis()
            val up = android.view.MotionEvent.obtain(
                t0, t1, android.view.MotionEvent.ACTION_UP, x, y, 0)
            surface.dispatchTouchEvent(up)
            up.recycle()
        }, 120)
    }

    override fun onDestroy() {
        if (::tts.isInitialized) tts.shutdown()
        nameTranslator?.close()
        super.onDestroy()
    }
}
