package io.github.rufim.alice

/** JNI-мост к android_bridge.c внутри libxsystem4.so. */
object NativeBridge {
    private var listener: ((String, Boolean) -> Unit)? = null
    private var pageListener: (() -> Unit)? = null
    private var skipListener: ((Int) -> Unit)? = null

    /** Вызвать после загрузки нативных библиотек (из активити). */
    fun init(l: (String, Boolean) -> Unit, onPage: () -> Unit, onSkip: (Int) -> Unit) {
        listener = l
        pageListener = onPage
        skipListener = onSkip
        nativeInit()
    }

    fun setTts(on: Boolean) = nativeSetTts(on)
    fun duck(on: Boolean, percent: Int) = nativeDuckMusic(on, percent)
    fun advance() = nativeAdvance()
    /** Счётчик посимвольной отрисовки текста — растёт, пока на экране модалка. */
    fun uiDrawCount(): Int = nativeUiDrawCount()

    /** Зовётся из потока VM (android_bridge.c). */
    @JvmStatic
    fun onAdvText(text: String, hasVoice: Boolean) {
        listener?.invoke(text, hasVoice)
    }

    /** Новая страница диалога (поток VM). */
    @JvmStatic
    fun onAdvPage() {
        pageListener?.invoke()
    }

    /** Игра включила/выключила «ПРОПУСК» (поток VM). */
    @JvmStatic
    fun onSkip(state: Int) {
        skipListener?.invoke(state)
    }

    // --- Читы ---
    /** Строки "pageSlot\tvarno\tname\tvalue". */
    fun cheatList(filter: String): Array<String> = nativeCheatList(filter)
    /** [0]="TOTAL:<n>", далее строки переменных. */
    fun cheatScan(value: Int, narrow: Boolean): Array<String> = nativeCheatScan(value, narrow)
    fun cheatWrite(pageSlot: Int, varno: Int, value: Int): Boolean =
        nativeCheatWrite(pageSlot, varno, value)

    private external fun nativeInit()
    private external fun nativeSetTts(on: Boolean)
    private external fun nativeDuckMusic(on: Boolean, percent: Int)
    private external fun nativeAdvance()
    private external fun nativeUiDrawCount(): Int
    private external fun nativeCheatList(filter: String): Array<String>
    private external fun nativeCheatScan(value: Int, narrow: Boolean): Array<String>
    private external fun nativeCheatWrite(pageSlot: Int, varno: Int, value: Int): Boolean
}
