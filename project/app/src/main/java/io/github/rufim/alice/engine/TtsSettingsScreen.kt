package io.github.rufim.alice.engine

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rufim.alice.NativeBridge
import io.github.rufim.alice.tts.TtsSpeaker
import io.github.rufim.alice.ui.AliceColors
import io.github.rufim.alice.ui.DropdownField
import io.github.rufim.alice.ui.LabeledSlider
import io.github.rufim.alice.ui.LabeledSwitch
import io.github.rufim.alice.ui.ScreenScaffold
import io.github.rufim.alice.ui.SectionLabel
import java.util.Locale

/** Человекочитаемое имя языка по коду (на русском), иначе — сам код. */
private fun langLabel(code: String): String {
    val disp = Locale(code).getDisplayLanguage(Locale("ru"))
    return if (disp.isNotBlank() && !disp.equals(code, ignoreCase = true))
        disp.replaceFirstChar { it.uppercase() } + " ($code)" else code
}

/** Настройки озвучки: свитчи, слайдеры, движок/язык/голос, фильтры System 3.x. */
@Composable
fun TtsSettingsScreen(
    tts: TtsSpeaker,
    prefs: SharedPreferences,
    supportsTextFilters: Boolean,
    onTtsToggled: (Boolean) -> Unit,
    applySuppressPages: (String) -> Unit,
    applyReadWindows: (String) -> Unit,
    onBack: () -> Unit,
) {
    var ttsOn by remember { mutableStateOf(prefs.getBoolean("tts", false)) }
    var duck by remember { mutableStateOf(prefs.getBoolean("duck", true)) }
    var autoAdvance by remember { mutableStateOf(prefs.getBoolean("auto_advance", false)) }
    var skipNames by remember { mutableStateOf(prefs.getBoolean("tts_skip_names", false)) }
    var duckPct by remember { mutableIntStateOf(prefs.getInt("duck_pct", 15)) }
    var rate by remember { mutableIntStateOf(prefs.getInt("tts_rate", 100)) }
    var pitch by remember { mutableIntStateOf(prefs.getInt("tts_pitch", 100)) }
    var suppressPages by remember { mutableStateOf(prefs.getString("tts_suppress_pages", "") ?: "") }
    var readWindows by remember { mutableStateOf(prefs.getString("tts_read_windows", "") ?: "") }

    // списки языков/голосов пересчитываются по готовности TTS-движка
    var enginesVersion by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        tts.onReady = { enginesVersion++ }
        onDispose { tts.onReady = null }
    }

    val engines = remember(enginesVersion) { tts.engines() }
    val engineIndex = engines.indexOfFirst { it.name == tts.currentEnginePackage() }
        .let { if (it >= 0) it else 0 }

    val languages = remember(enginesVersion) {
        tts.availableLanguages().ifEmpty { listOf("ru", "ja", "en") }
    }
    var curLang by remember(languages) {
        mutableStateOf(if ("ru" in languages) "ru" else languages.first())
    }
    val voices = remember(enginesVersion, curLang) { tts.voicesForLang(curLang) }
    val savedVoice = tts.voiceForLang(curLang)
        ?: prefs.getString("tts_voice_$curLang", null)?.also { tts.setVoiceForLang(curLang, it) }
    val voiceOptions = listOf("(по умолчанию)") + voices.map { it.name }
    val voiceIndex = voices.indexOfFirst { it.name == savedVoice }.let { if (it >= 0) it + 1 else 0 }

    ScreenScaffold(title = "Озвучка (TTS)", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp)
        ) {
            LabeledSwitch("Читать текст", ttsOn) { on ->
                ttsOn = on
                prefs.edit().putBoolean("tts", on).apply()
                NativeBridge.setTts(on)
                tts.setEnabled(on)
                onTtsToggled(on)
            }
            LabeledSwitch("Приглушать музыку", duck) { on ->
                duck = on
                prefs.edit().putBoolean("duck", on).apply()
                tts.duckMusic = on
            }
            LabeledSwitch("Листать после чтения", autoAdvance) { on ->
                autoAdvance = on
                prefs.edit().putBoolean("auto_advance", on).apply()
                tts.autoAdvance = on
            }
            LabeledSwitch("Не читать имена говорящих", skipNames) { on ->
                skipNames = on
                prefs.edit().putBoolean("tts_skip_names", on).apply()
                tts.readNames = !on
            }
            Text(
                "Игровой режим АВТО лучше выключить — иначе он будет листать раньше голоса.",
                fontSize = 12.sp,
                color = AliceColors.TextSecondary,
            )

            LabeledSlider("Музыка при чтении", duckPct, 0..100) { v ->
                duckPct = v
                tts.duckPercent = v
                prefs.edit().putInt("duck_pct", v).apply()
            }
            LabeledSlider("Скорость речи", rate, 50..200) { v ->
                rate = v
                tts.setRate(v / 100f)
                prefs.edit().putInt("tts_rate", v).apply()
            }
            LabeledSlider("Тон голоса", pitch, 50..200) { v ->
                pitch = v
                tts.setPitch(v / 100f)
                prefs.edit().putInt("tts_pitch", v).apply()
            }

            SectionLabel("Движок озвучки")
            DropdownField(
                options = engines.map { it.label },
                selectedIndex = engineIndex,
                onSelect = { i ->
                    val pkg = engines[i].name
                    if (pkg != tts.currentEnginePackage()) {
                        tts.setEngine(pkg)
                        prefs.edit().putString("tts_engine", pkg).apply()
                    }
                },
            )

            SectionLabel("Язык озвучки")
            DropdownField(
                options = languages.map { langLabel(it) },
                selectedIndex = languages.indexOf(curLang).coerceAtLeast(0),
                onSelect = { i -> curLang = languages[i] },
            )

            SectionLabel("Голос")
            DropdownField(
                options = voiceOptions,
                selectedIndex = voiceIndex,
                onSelect = { i ->
                    val name = if (i == 0) null else voices[i - 1].name
                    tts.setVoiceForLang(curLang, name)
                    prefs.edit().putString("tts_voice_$curLang", name).apply()
                },
            )

            if (supportsTextFilters) {
                SectionLabel("Не читать страницы (номера через запятую)")
                OutlinedTextField(
                    value = suppressPages,
                    onValueChange = { v ->
                        suppressPages = v
                        prefs.edit().putString("tts_suppress_pages", v.trim()).apply()
                        applySuppressPages(v.trim())
                    },
                    placeholder = { Text("напр. 11,19", color = AliceColors.TextSecondary) },
                    modifier = Modifier.fillMaxWidth(),
                )
                SectionLabel("Читать только окна (номера, пусто = все)")
                OutlinedTextField(
                    value = readWindows,
                    onValueChange = { v ->
                        readWindows = v
                        prefs.edit().putString("tts_read_windows", v.trim()).apply()
                        applyReadWindows(v.trim())
                    },
                    placeholder = { Text("напр. 5", color = AliceColors.TextSecondary) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
