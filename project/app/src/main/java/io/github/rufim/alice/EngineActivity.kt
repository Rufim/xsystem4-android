package io.github.rufim.alice

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.libsdl.app.SDLActivity

/**
 * Общий базовый класс игровых активити обоих движков. Несёт всю движко-
 * независимую обвязку: боковую панель, TTS-озвучку, читы, ML Kit-перевод имён,
 * плавающую кнопку стоп/плей, диалоги. Подклассы задают только нативную
 * библиотеку (`getLibraries`) и аргументы запуска (`getArguments`).
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
    private lateinit var panel: EdgePanel
    private lateinit var playPause: android.widget.ImageButton
    private var nameTranslator: NameTranslator? = null
    private var cheatPanel: CheatPanel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Workaround for https://github.com/libsdl-org/SDL/issues/8995
        SDLActivity.setWindowStyle(true)
        setupBridgeUi()
    }

    private fun setupBridgeUi() {
        val overlay = FrameLayout(this)
        mLayout.addView(overlay, RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT))
        val panel = EdgePanel(this, overlay)

        tts = TtsSpeaker(this)
        NativeBridge.init(
            { text, _ -> tts.speak(text) },
            { tts.pageBreak() },
            { state -> if (state != 0) tts.stop() })   // игровой «ПРОПУСК» → стоп чтения

        val ttsOn = panel.prefs.getBoolean("tts", false)
        val duckOn = panel.prefs.getBoolean("duck", true)
        val duckPct = panel.prefs.getInt("duck_pct", 15)
        tts.duckMusic = duckOn
        tts.duckPercent = duckPct

        // применить сохранённые движок, голоса (на каждый язык), темп и тон
        panel.prefs.getString("tts_engine", null)?.let { tts.setEngine(it) }
        for (lang in TTS_LANGS)
            panel.prefs.getString("tts_voice_$lang", null)?.let { tts.setVoiceForLang(lang, it) }
        tts.setRate(panel.prefs.getInt("tts_rate", 100) / 100f)
        tts.setPitch(panel.prefs.getInt("tts_pitch", 100) / 100f)
        tts.autoAdvance = panel.prefs.getBoolean("auto_advance", false)
        tts.advance = { advanceGame() }
        applySuppressPages(panel.prefs.getString("tts_suppress_pages", defaultSuppressPages())!!)
        applyReadWindows(panel.prefs.getString("tts_read_windows", defaultReadWindows())!!)

        this.panel = panel
        panel.addButton("Озвучка (TTS)") { showTtsDialog() }
        panel.addButton("Читы") { showCheatsDialog() }
        onPanelSetup(panel)   // движко-специфичные кнопки (напр. меню движка у System 3.x)

        // Плавающая кнопка стоп/плей (видна только при включённом TTS)
        playPause = android.widget.ImageButton(this).apply {
            setImageResource(R.drawable.ic_tts_stop)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setBackgroundColor(android.graphics.Color.argb(170, 30, 34, 48))
            setOnClickListener {
                if (tts.stopped) tts.play() else tts.stop()
            }
        }
        overlay.addView(playPause, FrameLayout.LayoutParams(dpToPx(52), dpToPx(52),
            android.view.Gravity.START or android.view.Gravity.BOTTOM).apply {
            leftMargin = dpToPx(12); bottomMargin = dpToPx(12)
        })
        // держать значок в актуальном состоянии (в т.ч. при стопе из игрового «ПРОПУСК»)
        tts.onStoppedChanged = { updatePlayPauseIcon() }

        // Отладочный оверлей: страница сценария и окно отображаемого текста
        // (верхний правый угол). Помогает подобрать «Не читать страницы».
        winIndicator = TextView(this).apply {
            setTextColor(android.graphics.Color.YELLOW)
            setBackgroundColor(android.graphics.Color.argb(160, 0, 0, 0))
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
            textSize = 13f
            text = "стр — · окно —"
        }
        overlay.addView(winIndicator, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.END or android.view.Gravity.TOP).apply {
            topMargin = dpToPx(6); rightMargin = dpToPx(6)
        })
        NativeBridge.setWindowListener { w, p ->
            runOnUiThread { winIndicator.text = "стр $p · окно $w" }
        }

        updatePlayPauseVisibility(ttsOn)

        NativeBridge.setTts(ttsOn)
        tts.setEnabled(ttsOn)
    }

    private lateinit var winIndicator: TextView

    private fun updatePlayPauseVisibility(ttsOn: Boolean) {
        if (::playPause.isInitialized) {
            playPause.visibility = if (ttsOn) android.view.View.VISIBLE else android.view.View.GONE
            if (ttsOn) updatePlayPauseIcon()
        }
        if (::winIndicator.isInitialized)
            winIndicator.visibility = if (ttsOn) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun updatePlayPauseIcon() {
        if (::playPause.isInitialized)
            playPause.setImageResource(if (tts.stopped) R.drawable.ic_tts_play else R.drawable.ic_tts_stop)
    }

    private fun showTtsDialog() {
        val prefs = panel.prefs
        val root = android.widget.ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(8), 0, dpToPx(8), dpToPx(12))
            setBackgroundColor(android.graphics.Color.rgb(24, 27, 38))
        }
        root.addView(col)

        col.addView(makeSwitch("Читать текст", prefs.getBoolean("tts", false)) { on ->
            prefs.edit().putBoolean("tts", on).apply()
            NativeBridge.setTts(on); tts.setEnabled(on)
            updatePlayPauseVisibility(on)
        })
        col.addView(makeSwitch("Приглушать музыку", prefs.getBoolean("duck", true)) { on ->
            prefs.edit().putBoolean("duck", on).apply(); tts.duckMusic = on
        })
        col.addView(makeSwitch("Листать после чтения", prefs.getBoolean("auto_advance", false)) { on ->
            prefs.edit().putBoolean("auto_advance", on).apply(); tts.autoAdvance = on
        })
        col.addView(TextView(this).apply {
            text = "Игровой режим АВТО лучше выключить — иначе он будет листать раньше голоса."
            textSize = 12f
            setTextColor(android.graphics.Color.rgb(150, 160, 180))
        })
        // Читать только эти окна сообщений (окно диалога). Пусто — читать все.
        // Исключает боевой лог/статус/меню, которые идут в другие окна.
        col.addView(dialogLabel("Читать только окна (номера, пусто = все)"))
        col.addView(android.widget.EditText(this).apply {
            setText(prefs.getString("tts_read_windows", defaultReadWindows()))
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            hint = "напр. 5"
            setTextColor(android.graphics.Color.BLACK)   // фон поля светлый
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    val v = s?.toString()?.trim() ?: ""
                    prefs.edit().putString("tts_read_windows", v).apply()
                    applyReadWindows(v)
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })
        // Не читать текст на указанных страницах (меню/статус). Пусто — читать всё.
        col.addView(dialogLabel("Не читать страницы (номера через запятую)"))
        col.addView(android.widget.EditText(this).apply {
            setText(prefs.getString("tts_suppress_pages", defaultSuppressPages()))
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            hint = "напр. 11"
            setTextColor(android.graphics.Color.BLACK)   // фон поля светлый
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    val v = s?.toString()?.trim() ?: ""
                    prefs.edit().putString("tts_suppress_pages", v).apply()
                    applySuppressPages(v)
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })
        // Музыка при чтении: 0..100 %
        addSlider(col, "Музыка при чтении", prefs.getInt("duck_pct", 15), 0..100,
            { "$it%" }) { v ->
            tts.duckPercent = v
            prefs.edit().putInt("duck_pct", v).apply()
        }
        // Скорость речи: 50..200 % → 0.5..2.0
        addSlider(col, "Скорость речи", prefs.getInt("tts_rate", 100), 50..200,
            { "$it%" }) { v ->
            tts.setRate(v / 100f)
            prefs.edit().putInt("tts_rate", v).apply()
        }
        // Тон голоса: 50..200 % → 0.5..2.0
        addSlider(col, "Тон голоса", prefs.getInt("tts_pitch", 100), 50..200,
            { "$it%" }) { v ->
            tts.setPitch(v / 100f)
            prefs.edit().putInt("tts_pitch", v).apply()
        }

        // --- Выбор движка ---
        col.addView(dialogLabel("Движок озвучки"))
        val engineSpinner = android.widget.Spinner(this)
        col.addView(engineSpinner)

        // --- Язык + голос для него ---
        col.addView(dialogLabel("Язык озвучки"))
        val langSpinner = android.widget.Spinner(this)
        col.addView(langSpinner)
        col.addView(dialogLabel("Голос"))
        val voiceSpinner = android.widget.Spinner(this)
        col.addView(voiceSpinner)

        // Человекочитаемое имя языка по коду (на русском), иначе — сам код.
        fun langLabel(code: String): String {
            val disp = java.util.Locale(code).getDisplayLanguage(java.util.Locale("ru"))
            return if (disp.isNotBlank() && !disp.equals(code, ignoreCase = true))
                disp.replaceFirstChar { it.uppercase() } + " ($code)" else code
        }
        // текущий выбранный в диалоге язык (по умолчанию — первый доступный или ru)
        var curLangs: List<String> = TTS_LANGS
        var curLang: String = "ru"

        fun fillVoices() {
            val voices = tts.voicesForLang(curLang)
            val names = listOf("(по умолчанию)") + voices.map { it.name }
            voiceSpinner.adapter = android.widget.ArrayAdapter(
                this, android.R.layout.simple_spinner_item, names).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            // подхватить сохранённый голос для языка, если ещё не загружен в память
            val cur = tts.voiceForLang(curLang)
                ?: prefs.getString("tts_voice_$curLang", null)?.also { tts.setVoiceForLang(curLang, it) }
            val idx = voices.indexOfFirst { it.name == cur }
            voiceSpinner.setSelection(if (idx >= 0) idx + 1 else 0)
            voiceSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val name = if (pos == 0) null else voices[pos - 1].name
                    tts.setVoiceForLang(curLang, name)
                    prefs.edit().putString("tts_voice_$curLang", name).apply()
                }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
        }

        fun fillLangs() {
            curLangs = tts.availableLanguages().ifEmpty { TTS_LANGS }
            if (curLang !in curLangs) curLang = curLangs.first()
            langSpinner.adapter = android.widget.ArrayAdapter(
                this, android.R.layout.simple_spinner_item,
                curLangs.map { langLabel(it) }).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            langSpinner.setSelection(curLangs.indexOf(curLang).coerceAtLeast(0))
            langSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    curLang = curLangs[pos]
                    fillVoices()
                }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
            fillVoices()
        }

        val engines = tts.engines()
        val engineLabels = engines.map { it.label }
        engineSpinner.adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_item, engineLabels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        engines.indexOfFirst { it.name == tts.currentEnginePackage() }
            .takeIf { it >= 0 }?.let { engineSpinner.setSelection(it) }
        engineSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val pkg = engines[pos].name
                if (pkg == tts.currentEnginePackage()) return
                tts.setEngine(pkg)
                prefs.edit().putString("tts_engine", pkg).apply()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        // списки языков/голосов обновляются по готовности движка (в т.ч. после смены)
        tts.onReady = { fillLangs() }
        fillLangs()

        showFullscreenDialog("Озвучка (TTS)", root)
    }

    private fun showCheatsDialog() {
        // Один постоянный экземпляр: сохраняет введённое значение, список и
        // состояние скана между открытиями (нужно для «изменить в игре → Сузить»).
        val cheats = cheatPanel ?: CheatPanel(this, panel.prefs).also { cp ->
            cp.prependControl(makeSwitch("Переводить имена (ML Kit)",
                    panel.prefs.getBoolean("translate_names", false)) { on ->
                if (on) {
                    if (nameTranslator == null) nameTranslator = NameTranslator()
                    nameTranslator!!.ensureModel { ready ->
                        if (ready) {
                            panel.prefs.edit().putBoolean("translate_names", true).apply()
                            cp.translateNames = { rows, done ->
                                rows.forEach { r ->
                                    nameTranslator!!.translate(r.name) { r.display = it; done() }
                                }
                            }
                            cp.reload()
                        } else {
                            Toast.makeText(this, "Модель перевода недоступна (нет сети/сервисов Google)",
                                Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    panel.prefs.edit().putBoolean("translate_names", false).apply()
                    cp.translateNames = null
                    cp.reload()
                }
            })
            cp.reload()
            cheatPanel = cp
        }
        // отвязать view от прошлого (закрытого) диалога перед повторным показом
        (cheats.view.parent as? android.view.ViewGroup)?.removeView(cheats.view)
        showFullscreenDialog("Читы", cheats.view)
    }

    /** Подпись + SeekBar в стиле «Музыка при чтении». value/range в целых (напр. процентах). */
    private fun addSlider(
        parent: LinearLayout, title: String, value: Int, range: IntRange,
        fmt: (Int) -> String, onChange: (Int) -> Unit
    ) {
        val label = TextView(this).apply {
            text = "$title: ${fmt(value)}"
            setTextColor(android.graphics.Color.WHITE)
            setPadding(0, dpToPx(12), 0, 0)
        }
        parent.addView(label)
        parent.addView(SeekBar(this).apply {
            max = range.last - range.first
            progress = value - range.first
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = range.first + p
                    label.text = "$title: ${fmt(v)}"
                    onChange(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })
    }

    private fun dialogLabel(t: String) = TextView(this).apply {
        text = t; textSize = 15f
        setTextColor(android.graphics.Color.rgb(180, 190, 210))
        setPadding(0, dpToPx(16), 0, dpToPx(4))
    }

    private fun showFullscreenDialog(title: String, content: View) {
        val dlg = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.rgb(24, 27, 38))
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
        }
        // шапка: стрелка «назад» + заголовок в одну строку
        wrap.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(android.widget.ImageButton(this@EngineActivity).apply {
                setImageResource(R.drawable.ic_arrow_back)
                background = null
                setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
                contentDescription = "Назад"
                setOnClickListener { dlg.dismiss() }
            }, LinearLayout.LayoutParams(dpToPx(44), dpToPx(44)))
            addView(TextView(this@EngineActivity).apply {
                text = title; textSize = 20f
                setTextColor(android.graphics.Color.WHITE)
                setPadding(dpToPx(12), 0, 0, 0)
            })
        })
        wrap.addView(content, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
        dlg.setContentView(wrap)
        dlg.show()
    }

    /** Хорошо заметный двухпозиционный переключатель с собственными drawable
     *  (системная тема прошивки может не рисовать трек — тинт тогда бесполезен). */
    private fun makeSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit): Switch {
        fun pill(color: Int) = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(15).toFloat()
            setColor(color)
            setSize(dpToPx(56), dpToPx(30))
        }
        val track = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked),
                pill(android.graphics.Color.rgb(76, 200, 120)))
            addState(intArrayOf(), pill(android.graphics.Color.rgb(95, 95, 95)))
        }
        val thumb = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(android.graphics.Color.WHITE)
            setSize(dpToPx(28), dpToPx(28))
            setStroke(dpToPx(1), android.graphics.Color.rgb(60, 60, 60))
        }
        return Switch(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            text = label
            textSize = 16f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(0, dpToPx(12), 0, dpToPx(12))
            switchMinWidth = dpToPx(60)
            showText = false          // убрать «Включено/Выключено» с рычажка
            textOn = ""
            textOff = ""
            trackDrawable = track
            thumbDrawable = thumb
            isChecked = checked
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
    }

    protected fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    /** Как «листать» диалог при авто-режиме. По умолчанию — синтетический тап
     *  (для xsystem4; SDL-клавиши там не листают). Подклассы могут переопределить. */
    protected open fun advanceGame() = synthesizeTap()

    /** Не озвучивать текст на этих сценарных страницах (меню/статус). По умолчанию —
     *  ничего; xsystem35 переопределяет (поддержка через texthook движка). */
    protected open fun applySuppressPages(pages: String) {}

    /** Значение «не читать страницы» по умолчанию (для конкретного движка/игры). */
    protected open fun defaultSuppressPages(): String = ""

    /** Читать только эти окна сообщений (по умолчанию — ничего = все окна). */
    protected open fun applyReadWindows(csv: String) {}

    /** Окна для озвучки по умолчанию (для конкретного движка/игры). */
    protected open fun defaultReadWindows(): String = ""

    /** Добавить движко-специфичные кнопки в боковую панель (по умолчанию — ничего). */
    protected open fun onPanelSetup(panel: EdgePanel) {}

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
