package io.github.kichikuou.xsystem4

/** JNI-мост к android_bridge.c внутри libxsystem4.so. */
object NativeBridge {
    private var listener: ((String, Boolean) -> Unit)? = null
    private var pageListener: (() -> Unit)? = null

    /** Вызвать после загрузки нативных библиотек (из активити). */
    fun init(l: (String, Boolean) -> Unit, onPage: () -> Unit) {
        listener = l
        pageListener = onPage
        nativeInit()
    }

    fun setTts(on: Boolean) = nativeSetTts(on)
    fun duck(on: Boolean, percent: Int) = nativeDuckMusic(on, percent)
    fun advance() = nativeAdvance()

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

    private external fun nativeInit()
    private external fun nativeSetTts(on: Boolean)
    private external fun nativeDuckMusic(on: Boolean, percent: Int)
    private external fun nativeAdvance()
}
