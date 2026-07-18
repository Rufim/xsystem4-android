package io.github.rufim.alice.tts

import io.github.rufim.alice.R
import io.github.rufim.alice.NativeBridge
import io.github.rufim.alice.tts.*
import io.github.rufim.alice.cheats.*
import io.github.rufim.alice.launcher.*
import io.github.rufim.alice.engine.*
import io.github.rufim.alice.ui.*

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * On-device перевод японских имён переменных JA→RU через Google ML Kit.
 * Модель качается при первом включении; результаты кэшируются в памяти.
 * Если модель недоступна (нет сети/сервисов) — деградирует мягко (imя как есть).
 */
class NameTranslator {
    private val cache = HashMap<String, String>()
    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.JAPANESE)
            .setTargetLanguage(TranslateLanguage.RUSSIAN)
            .build())

    /** Скачать модель, если нужно. onReady(true) — готово, (false) — недоступно. */
    fun ensureModel(onReady: (Boolean) -> Unit) {
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { onReady(true) }
            .addOnFailureListener { onReady(false) }
    }

    /** Перевести имя (только если содержит кану/кандзи). Иначе — как есть. */
    fun translate(name: String, cb: (String) -> Unit) {
        cache[name]?.let { cb(it); return }
        if (name.none { it.code in 0x3040..0x9FFF }) { cb(name); return }
        translator.translate(name)
            .addOnSuccessListener { cache[name] = it; cb(it) }
            .addOnFailureListener { cb(name) }
    }

    fun close() = translator.close()
}
