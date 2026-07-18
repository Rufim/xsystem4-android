package io.github.rufim.alice

/** JNI-мост к android_bridge.c внутри libxsystem4.so. */
object NativeBridge {
    private var listener: ((String, Boolean) -> Unit)? = null
    private var pageListener: (() -> Unit)? = null
    private var skipListener: ((Int) -> Unit)? = null
    private var windowListener: ((Int, Int) -> Unit)? = null

    /** Колбэк (окно, страница) отображаемого текста (отладочный оверлей). */
    fun setWindowListener(l: (Int, Int) -> Unit) { windowListener = l }

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
    /** Номера сценарных страниц (через запятую), текст которых не озвучивать
     *  (только xsystem35 — метод есть лишь в его .so). */
    fun setSuppressPages(pages: String) = nativeSetSuppressPages(pages)
    /** Открыть встроенное меню движка (громкость/пропуск/…); только xsystem35. */
    fun openEngineMenu() = nativeOpenEngineMenu()
    /** Читать только эти окна сообщений (номера через запятую, пусто = все); xsystem35. */
    fun setReadWindows(csv: String) = nativeSetReadWindows(csv)

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

    /** Окно и страница отображаемого текста (поток VM). */
    @JvmStatic
    fun onWindow(winno: Int, page: Int) {
        windowListener?.invoke(winno, page)
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
    private external fun nativeSetSuppressPages(pages: String)
    private external fun nativeOpenEngineMenu()
    private external fun nativeSetReadWindows(csv: String)
    private external fun nativeCheatList(filter: String): Array<String>
    private external fun nativeCheatScan(value: Int, narrow: Boolean): Array<String>
    private external fun nativeCheatWrite(pageSlot: Int, varno: Int, value: Int): Boolean
}
