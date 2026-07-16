package io.github.kichikuou.xsystem4

import android.os.Bundle
import android.system.Os
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import org.libsdl.app.SDLActivity

// Intent for this activity must have the following extras:
// - EXTRA_GAME_ROOT (string): A path to the game installation.
// - EXTRA_SAVE_DIR (string): A path to a directory where save files are stored.
class XSystem4Activity : SDLActivity() {
    companion object {
        const val EXTRA_GAME_ROOT = "GAME_ROOT"
        const val EXTRA_SAVE_DIR = "SAVE_DIR"
        const val COMMAND_OPEN_PLAYING_MANUAL = 0x8000  // xsystem4/src/hll/SystemService.c
    }

    private lateinit var tts: TtsSpeaker

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
        NativeBridge.init({ text, _ -> tts.speak(text) }, { tts.pageBreak() })

        val ttsOn = panel.prefs.getBoolean("tts", false)
        val duckOn = panel.prefs.getBoolean("duck", true)
        val duckPct = panel.prefs.getInt("duck_pct", 15)
        tts.duckMusic = duckOn
        tts.duckPercent = duckPct

        // применить сохранённые движок, голос, темп и тон
        panel.prefs.getString("tts_engine", null)?.let { tts.setEngine(it) }
        panel.prefs.getString("tts_ru_voice", null)?.let { tts.setRuVoice(it) }
        tts.setRate(panel.prefs.getInt("tts_rate", 100) / 100f)
        tts.setPitch(panel.prefs.getInt("tts_pitch", 100) / 100f)
        tts.autoAdvance = panel.prefs.getBoolean("auto_advance", false)
        tts.advance = { synthesizeTap() }

        this.panel = panel
        panel.addButton("Озвучка (TTS)") { showTtsDialog() }
        panel.addButton("Читы") { showCheatsDialog() }

        NativeBridge.setTts(ttsOn)
        tts.setEnabled(ttsOn)
    }

    private lateinit var panel: EdgePanel

    private fun showTtsDialog() {
        val prefs = panel.prefs
        val root = android.widget.ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(20), dpToPx(20), dpToPx(20), dpToPx(20))
            setBackgroundColor(android.graphics.Color.rgb(24, 27, 38))
        }
        root.addView(col)

        col.addView(dialogTitle("Озвучка (TTS)"))
        col.addView(makeSwitch("Читать текст", prefs.getBoolean("tts", false)) { on ->
            prefs.edit().putBoolean("tts", on).apply()
            NativeBridge.setTts(on); tts.setEnabled(on)
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
        val voiceSpinner = android.widget.Spinner(this)
        fun fillVoices() {
            val voices = tts.ruVoices()
            val names = listOf("(по умолчанию)") + voices.map { it.name }
            voiceSpinner.adapter = android.widget.ArrayAdapter(
                this, android.R.layout.simple_spinner_item, names).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            val cur = tts.currentRuVoiceName()
            val idx = voices.indexOfFirst { it.name == cur }
            voiceSpinner.setSelection(if (idx >= 0) idx + 1 else 0)
            voiceSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val name = if (pos == 0) null else voices[pos - 1].name
                    tts.setRuVoice(name)
                    prefs.edit().putString("tts_ru_voice", name).apply()
                }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
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
        col.addView(engineSpinner)
        col.addView(dialogLabel("Голос (русский)"))
        col.addView(voiceSpinner)

        // список голосов обновляется по готовности движка (в т.ч. после смены)
        tts.onReady = { fillVoices() }
        fillVoices()

        showFullscreenDialog(root)
    }

    private fun showCheatsDialog() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(20), dpToPx(12), dpToPx(20), dpToPx(12))
            setBackgroundColor(android.graphics.Color.rgb(24, 27, 38))
        }
        col.addView(dialogTitle("Читы"))
        val cheats = CheatPanel(this, panel.prefs)
        col.addView(cheats.view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        cheats.reload()
        showFullscreenDialog(col)
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

    private fun dialogTitle(t: String) = TextView(this).apply {
        text = t; textSize = 22f
        setTextColor(android.graphics.Color.WHITE)
        setPadding(0, 0, 0, dpToPx(16))
    }

    private fun dialogLabel(t: String) = TextView(this).apply {
        text = t; textSize = 15f
        setTextColor(android.graphics.Color.rgb(180, 190, 210))
        setPadding(0, dpToPx(16), 0, dpToPx(4))
    }

    private fun showFullscreenDialog(content: View) {
        val dlg = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.rgb(24, 27, 38))
        }
        wrap.addView(Button(this).apply {
            text = "← Закрыть"
            setOnClickListener { dlg.dismiss() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
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

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    /** Синтетический тап в центр игровой поверхности — «дальше» в диалоге
     *  (тот же путь, что палец; SDL-клавиши игру на Android не листают). */
    private fun synthesizeTap() {
        val surface = mSurface ?: return
        val x = surface.width * 0.5f
        val y = surface.height * 0.45f
        val now = android.os.SystemClock.uptimeMillis()
        val down = android.view.MotionEvent.obtain(
            now, now, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
        val up = android.view.MotionEvent.obtain(
            now, now + 30, android.view.MotionEvent.ACTION_UP, x, y, 0)
        surface.dispatchTouchEvent(down)
        surface.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    override fun onDestroy() {
        if (::tts.isInitialized) tts.shutdown()
        super.onDestroy()
    }

    override fun getLibraries(): Array<String> {
        return arrayOf("SDL2", "xsystem4")
    }

    override fun getArguments(): Array<String> {
        val saveFolder = intent.getStringExtra(EXTRA_SAVE_DIR)!!
        val gameRoot = intent.getStringExtra(EXTRA_GAME_ROOT)!!
        return arrayOf("--save-folder", saveFolder, "--save-format=rsm", gameRoot)
    }

    override fun onUnhandledMessage(command: Int, param: Any): Boolean {
        when (command) {
            COMMAND_OPEN_PLAYING_MANUAL -> {
                val gameRoot = intent.getStringExtra(EXTRA_GAME_ROOT)!!
                val manualDir = java.io.File(gameRoot, "Manual")
                if (manualDir.isDirectory) {
                    val intent = android.content.Intent(this, ManualActivity::class.java).apply {
                        val url = "file://${manualDir.absolutePath}/index.html"
                        putExtra(ManualActivity.EXTRA_URL, url)
                    }
                    startActivity(intent)
                }
                return true
            }
        }
        return super.onUnhandledMessage(command, param)
    }
}