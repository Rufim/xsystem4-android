package io.github.rufim.alice

import android.app.AlertDialog
import android.media.MediaPlayer
import android.text.InputType
import android.util.Log
import android.widget.EditText
import android.widget.NumberPicker
import java.io.IOException

// Игровая активити движка xsystem35 (System 3.x). Обвязка TTS/читов — в
// EngineActivity; здесь — нативная библиотека, аргументы запуска и
// движко-специфичные JNI-колбэки (MIDI-плеер, диалоги ввода строки/числа).
// Нативка находит эти методы через GetObjectClass(activity), поэтому имена
// midiStart/midiStop/midiCurrentPosition/inputString/inputNumber обязательны.
class XSystem35Activity : EngineActivity() {
    private val midi = MidiPlayer()

    override fun onStop() {
        super.onStop()
        midi.onActivityStop()
    }

    override fun onResume() {
        super.onResume()
        midi.onActivityResume()
    }

    override fun getLibraries(): Array<String> {
        return arrayOf("SDL2", "xsystem35")
    }

    // Подавление озвучки по номерам страниц (movie/меню/статус) — есть в мосте
    // только у xsystem35. По умолчанию для Daiakuji — страница 11 (титул/хаб «35Y…»).
    override fun applySuppressPages(pages: String) {
        NativeBridge.setSuppressPages(pages)
    }

    override fun defaultSuppressPages(): String = "11"

    // System 3.x: встроенное меню движка (громкость BGM/SE, пропуск сообщений,
    // рестарт, выход) в оригинале открывается «тремя пальцами» — добавляем кнопку.
    override fun onPanelSetup(panel: EdgePanel) {
        panel.addButton("Меню движка (звук, пропуск)") { NativeBridge.openEngineMenu() }
    }

    override fun getArguments(): Array<String> {
        return arrayOf(
            "-gamedir", intent.getStringExtra(EXTRA_GAME_ROOT)!!,
            "-savedir", intent.getStringExtra(EXTRA_SAVE_DIR)!!)
    }

    private fun textInputDialog(msg: String, oldVal: String, maxLen: Int, result: Array<String?>) {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.setText(oldVal)
        AlertDialog.Builder(this)
                .setMessage(msg)
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val s = input.text.toString()
                    result[0] = if (s.length <= maxLen) s else s.substring(0, maxLen)
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> }
                .setOnDismissListener {
                    synchronized(result) {
                        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN") (result as Object).notify()
                    }
                }
                .show()
    }

    private fun numberInputDialog(msg: String, min: Int, max: Int, initial: Int, result: IntArray) {
        val input = NumberPicker(this)
        input.minValue = min
        input.maxValue = max
        input.value = initial
        AlertDialog.Builder(this)
                .setMessage(msg)
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    input.clearFocus()
                    result[0] = input.value
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> }
                .setOnDismissListener {
                    synchronized(result) {
                        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN") (result as Object).notify()
                    }
                }
                .show()
    }

    // Ниже — методы, вызываемые из потока SDL по JNI.
    @Suppress("unused") fun midiStart(path: String, loop: Boolean) = midi.start(path, loop)
    @Suppress("unused") fun midiStop() = midi.stop()
    @Suppress("unused") fun midiCurrentPosition() = midi.currentPosition()

    @Suppress("unused") fun inputString(msg: String, oldVal: String, maxLen: Int): String? {
        val result = arrayOfNulls<String?>(1)
        runOnUiThread { textInputDialog(msg, oldVal, maxLen, result) }
        synchronized(result) {
            try {
                @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN") (result as Object).wait()
            } catch (ex: InterruptedException) {
                ex.printStackTrace()
            }
        }
        return result[0]
    }

    @Suppress("unused") fun inputNumber(msg: String, min: Int, max: Int, initial: Int): Int {
        val result = intArrayOf(-1)
        runOnUiThread { numberInputDialog(msg, min, max, initial, result) }
        synchronized(result) {
            try {
                @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN") (result as Object).wait()
            } catch (ex: InterruptedException) {
                ex.printStackTrace()
            }
        }
        return result[0]
    }
}

private class MidiPlayer {
    private val player = MediaPlayer()
    private var playing = false
    private var playerPaused = false

    fun start(path: String, loop: Boolean) {
        try {
            player.apply {
                reset()
                setDataSource(path)
                isLooping = loop
                prepare()
                start()
            }
            playing = true
        } catch (e: IOException) {
            Log.e("midiStart", "Cannot play midi", e)
            player.reset()
        }
    }

    fun stop() {
        if (playing && player.isPlaying) {
            player.stop()
            playing = false
        }
    }

    fun currentPosition(): Int {
        return if (playing) player.currentPosition else 0
    }

    fun onActivityStop() {
        if (playing && player.isPlaying) {
            player.pause()
            playerPaused = true
        }
    }

    fun onActivityResume() {
        if (playerPaused) {
            player.start()
            playerPaused = false
        }
    }
}
