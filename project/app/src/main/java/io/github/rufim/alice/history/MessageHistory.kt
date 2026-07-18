package io.github.rufim.alice.history

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** История последних сообщений диалога (на процесс движка). */
object MessageHistory {
    const val MAX_ENTRIES = 50

    data class Entry(val speaker: String?, val text: String, val timeMs: Long)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    /** Зовётся с потока VM (AdvRouter). */
    fun add(speaker: String?, text: String) {
        if (text.isBlank()) return
        _entries.update { (it + Entry(speaker, text, System.currentTimeMillis())).takeLast(MAX_ENTRIES) }
    }
}
