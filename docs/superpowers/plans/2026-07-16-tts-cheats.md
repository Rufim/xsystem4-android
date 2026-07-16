# TTS + Cheat Panel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Личная сборка xsystem4-android читает ADV-текст через Android TTS (глуша штатный голос) и даёт боковую панель читов (браузер + скан по значению глобалов System 4 VM, опциональный ML Kit перевод имён).

**Architecture:** Мост `android_bridge.c` в движке (хуки текста в AnteaterADVEngine, mute голосовых микшеров, доступ к глобалам VM) ↔ JNI ↔ Kotlin (`NativeBridge`, `TtsSpeaker`, `EdgePanel` с вкладками TTS/читы). На Linux мост собирается заглушкой с логом в stdout для быстрой проверки хуков.

**Tech Stack:** C11 (meson на Linux, CMake+NDK на Android), Kotlin, SDL2 JNI, Android TextToSpeech, Google ML Kit Translate (unbundled).

## Global Constraints

- Спека: `docs/superpowers/specs/2026-07-16-tts-cheats-design.md`.
- Репозитории: приложение `~/src/xsystem4-android` (коммиты сюда), движок — submodule `xsystem4/` (коммиты в него отдельно, detached HEAD — сделать ветку `tts-cheats`).
- Android minSdk 21, compileSdk 34, Java 17, NDK `~/Android/Sdk/ndk/29.0.14206865`, только ABI arm64-v8a.
- Сборка Android: `ANDROID_SDK_ROOT=~/Android/Sdk ANDROID_NDK_HOME=~/Android/Sdk/ndk/29.0.14206865 ABI_NAMES=arm64-v8a ./build-shared-libs.sh && cd project && ./gradlew assembleDebug`.
- Сборка Linux (проверка движка): `cd ~/src/xsystem4 … build` НЕ используется — движок правится в submodule `~/src/xsystem4-android/xsystem4`, там же linux-сборка: `meson setup lbuild && ninja -C lbuild` (PATH с `~/.local/bin`).
- Игра для проверки на Linux: `/mnt/sda1/Games/miniGames/Daiteikoku` (запуск: `SDL_VIDEODRIVER=x11 LD_LIBRARY_PATH=~/.local/lib <bin> .`). Телефон: adb, пакет `io.github.kichikuou.xsystem4`.
- Все строки движка — SJIS; наружу мост отдаёт только UTF-8 (`sjis2utf` из libsys4).
- Никаких новых зависимостей движка. В приложении — только `com.google.mlkit:translate`.

---

### Task 1: Мост в движке — хук ADV-текста + Linux-заглушка

**Files:**
- Create: `xsystem4/include/android_bridge.h`
- Create: `xsystem4/src/android_bridge.c`
- Modify: `xsystem4/src/hll/AnteaterADVEngine.c` (4 вставки)
- Modify: `xsystem4/meson.build` (или `src/meson.build` — где перечислены исходники)
- Modify: `xsystem4/CMakeLists.txt` (список `target_sources`)

**Interfaces:**
- Produces (для Task 2–4):
  - `void bridge_adv_add_text(struct string *sjis_text);`
  - `void bridge_adv_line_break(void);   // NewLine и NewPage`
  - `void bridge_adv_add_voice(int voice_no);`
  - `void bridge_set_tts_enabled(bool on);` (пока только флаг; JNI в Task 3)
  - Внутренний эмиттер: `static void bridge_emit(const char *utf8_text, bool has_voice)` — на Linux печатает `[ADV] voice=%d |%s|` в stdout при `getenv("XS4_BRIDGE_DEBUG")`.

- [ ] **Step 1: создать ветку в submodule движка**

```bash
cd ~/src/xsystem4-android/xsystem4 && git checkout -b tts-cheats
```

- [ ] **Step 2: написать заголовок**

`xsystem4/include/android_bridge.h`:
```c
/* Мост движок <-> Android (TTS, читы). На не-Android платформах
 * собирается заглушкой с отладочным выводом в stdout. */
#ifndef SYSTEM4_ANDROID_BRIDGE_H
#define SYSTEM4_ANDROID_BRIDGE_H

#include <stdbool.h>

struct string;

// ADV-текст (вызывается из AnteaterADVEngine, поток VM)
void bridge_adv_add_text(struct string *sjis_text);
void bridge_adv_line_break(void);
void bridge_adv_add_voice(int voice_no);

// Управление (вызывается из JNI / заглушки)
void bridge_set_tts_enabled(bool on);

#endif
```

- [ ] **Step 3: написать общую часть моста**

`xsystem4/src/android_bridge.c` (общая часть; JNI добавится в Task 3):
```c
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "system4.h"
#include "system4/string.h"
#include "system4/utfsjis.h"
#include "android_bridge.h"

static bool tts_enabled = false;
static bool line_has_voice = false;
static struct string *line_buf = NULL;

void bridge_set_tts_enabled(bool on) { tts_enabled = on; }

static void bridge_emit(const char *utf8_text, bool has_voice);

void bridge_adv_add_text(struct string *sjis_text)
{
	if (!sjis_text || !sjis_text->size)
		return;
	if (!line_buf)
		line_buf = string_ref(sjis_text);
	else
		string_append(&line_buf, sjis_text);
}

void bridge_adv_add_voice(int voice_no)
{
	(void)voice_no;
	line_has_voice = true;
}

void bridge_adv_line_break(void)
{
	if (line_buf && line_buf->size) {
		char *utf8 = sjis2utf(line_buf->text, line_buf->size);
		bridge_emit(utf8, line_has_voice);
		free(utf8);
	}
	if (line_buf) {
		free_string(line_buf);
		line_buf = NULL;
	}
	line_has_voice = false;
}

#ifndef __ANDROID__
static void bridge_emit(const char *utf8_text, bool has_voice)
{
	if (!getenv("XS4_BRIDGE_DEBUG"))
		return;
	printf("[ADV] voice=%d |%s|\n", has_voice, utf8_text);
	fflush(stdout);
}
#endif
```
Примечание: `string_ref` увеличивает счётчик — чтобы не мутировать строку игры,
в `bridge_adv_add_text` при первом сегменте использовать копию:
`line_buf = string_dup(sjis_text);` (именно `string_dup`, не `string_ref`).

- [ ] **Step 4: вставить хуки в AnteaterADVEngine.c**

В `xsystem4/src/hll/AnteaterADVEngine.c` добавить `#include "android_bridge.h"` после
остальных include, затем:

```c
void ADVLogList_AddText(struct string **text)
{
	bridge_adv_add_text(*text);          // <-- вставка (ДО if(!enabled))
	if (!enabled) return;
	...
}

void ADVLogList_AddNewLine(void)         // и аналогично AddNewPage
{
	bridge_adv_line_break();             // <-- вставка первой строкой
	...
}

void ADVLogList_AddVoice(int voice_no)
{
	bridge_adv_add_voice(voice_no);      // <-- вставка первой строкой
	...
}
```
Важно: вставка в `AddText` идёт до проверки `enabled` (лог игрок может выключить,
а TTS должен работать всегда).

- [ ] **Step 5: подключить к сборкам**

meson: найти список исходников (`grep -n "src/audio.c" xsystem4/src/meson.build`)
и добавить `'android_bridge.c'` рядом (путь по образцу соседей).
CMake: в `xsystem4/CMakeLists.txt` в `target_sources(xsystem4 PRIVATE ...)`
добавить `src/android_bridge.c`.

- [ ] **Step 6: Linux-сборка и проверка на игре**

```bash
cd ~/src/xsystem4-android/xsystem4
export PATH="$HOME/.local/bin:$PATH"
meson setup lbuild >/dev/null && ninja -C lbuild
cd /mnt/sda1/Games/miniGames/Daiteikoku
XS4_BRIDGE_DEBUG=1 SDL_VIDEODRIVER=x11 \
  ~/src/xsystem4-android/xsystem4/lbuild/src/xsystem4 . 2>&1 | grep '\[ADV\]' &
```
Пройти до стартовой сцены (Return — пропуск предупреждения; клик «Играть» делает
пользователь, либо игра резюмируется в сцену сама). Ожидаемо: строки
`[ADV] voice=0 |ХХ день, ХХ месяц.|` и т.п. в stdout.

- [ ] **Step 7: закоммитить (submodule)**

```bash
cd ~/src/xsystem4-android/xsystem4
git add include/android_bridge.h src/android_bridge.c src/hll/AnteaterADVEngine.c CMakeLists.txt src/meson.build
git commit -m "android_bridge: forward ADV text lines for TTS"
```

---

### Task 2: Мост — заглушение голосовых микшеров

**Files:**
- Modify: `xsystem4/include/android_bridge.h`
- Modify: `xsystem4/src/android_bridge.c`

**Interfaces:**
- Produces: `void bridge_set_voice_muted(bool on);` — mute всех микшеров, чьё имя
  содержит "voice" (без регистра) или "音声"; при выключении — unmute тех же.
  Диагностика: при `XS4_BRIDGE_DEBUG` печатает имена всех микшеров.

- [ ] **Step 1: реализация**

В `android_bridge.h` добавить `void bridge_set_voice_muted(bool on);`
В `android_bridge.c`:
```c
#include "mixer.h"

void bridge_set_voice_muted(bool on)
{
	int n = mixer_get_numof();
	for (int i = 0; i < n; i++) {
		const char *name = mixer_get_name(i);
		if (!name)
			continue;
		if (getenv("XS4_BRIDGE_DEBUG"))
			printf("[MIXER %d] %s\n", i, name);
		// имя в SJIS; "voice" — ASCII, ищем без регистра
		if (strcasestr(name, "voice") || strstr(name, "\x89\xb9\x90\xba" /*音声 SJIS*/))
			mixer_set_mute(i, on ? 1 : 0);
	}
}
```
`strcasestr` — GNU/bionic доступен (в bionic есть); при проблеме — своя петля с
`tolower`. Проверить сигнатуры в `include/mixer.h` перед использованием.

- [ ] **Step 2: Linux-проверка имён микшеров**

Пересобрать, запустить игру с `XS4_BRIDGE_DEBUG=1`, во время сцены вызвать mute
временной строчкой в `bridge_adv_line_break()` (`static bool once; if(!once){once=true;bridge_set_voice_muted(true);}`),
посмотреть список `[MIXER]` в stdout. Если голосового микшера нет — зафиксировать
имена в комментарии и оставить путь через микшеры с наилучшим совпадением
(запасной план каналов задействуем только если на устройстве голос не заглохнет).
Убрать временную строчку.

- [ ] **Step 3: коммит (submodule)**

```bash
cd ~/src/xsystem4-android/xsystem4
git add include/android_bridge.h src/android_bridge.c
git commit -m "android_bridge: mute voice mixers while TTS is active"
```

---

### Task 3: Мост — JNI (Android-часть)

**Files:**
- Modify: `xsystem4/src/android_bridge.c` (секция `#ifdef __ANDROID__`)

**Interfaces:**
- Consumes: Kotlin-класс `io.github.kichikuou.xsystem4.NativeBridge` (Task 4) c
  статическими методами: `fun onAdvText(text: String, hasVoice: Boolean)`.
- Produces (JNI-натив-методы, регистрируются по имени символа):
  - `Java_io_github_kichikuou_xsystem4_NativeBridge_nativeInit(JNIEnv*, jclass)`
  - `Java_io_github_kichikuou_xsystem4_NativeBridge_nativeSetTts(JNIEnv*, jclass, jboolean)`

- [ ] **Step 1: JNI-секция**

В конец `android_bridge.c`:
```c
#ifdef __ANDROID__
#include <jni.h>

static JavaVM *jvm = NULL;
static jclass bridge_class = NULL;      // GlobalRef на NativeBridge
static jmethodID mid_on_adv_text = NULL;

JNIEXPORT void JNICALL
Java_io_github_kichikuou_xsystem4_NativeBridge_nativeInit(JNIEnv *env, jclass cls)
{
	(*env)->GetJavaVM(env, &jvm);
	bridge_class = (*env)->NewGlobalRef(env, cls);
	mid_on_adv_text = (*env)->GetStaticMethodID(env, cls, "onAdvText",
	                                            "(Ljava/lang/String;Z)V");
}

JNIEXPORT void JNICALL
Java_io_github_kichikuou_xsystem4_NativeBridge_nativeSetTts(JNIEnv *env, jclass cls, jboolean on)
{
	bridge_set_tts_enabled(on);
	bridge_set_voice_muted(on);
}

static JNIEnv *bridge_env(void)
{
	if (!jvm) return NULL;
	JNIEnv *env;
	if ((*jvm)->GetEnv(jvm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) {
		if ((*jvm)->AttachCurrentThread(jvm, &env, NULL) != JNI_OK)
			return NULL;
	}
	return env;   // поток VM живёт до конца процесса — Detach не требуется
}

static void bridge_emit(const char *utf8_text, bool has_voice)
{
	if (!tts_enabled || !mid_on_adv_text)
		return;
	JNIEnv *env = bridge_env();
	if (!env) return;
	jstring jtext = (*env)->NewStringUTF(env, utf8_text);
	if (!jtext) { (*env)->ExceptionClear(env); return; }
	(*env)->CallStaticVoidMethod(env, bridge_class, mid_on_adv_text,
	                             jtext, (jboolean)has_voice);
	if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
	(*env)->DeleteLocalRef(env, jtext);
}
#endif
```
Нюанс: `NewStringUTF` падает на невалидном modified-UTF8 — суррогатов у нас нет
(sjis2utf даёт BMP), достаточно проверки на NULL.
Общая часть: `static void bridge_emit(...)` объявлена forward — убрать `#ifndef
__ANDROID__` вокруг Linux-версии так, чтобы компилировалась ровно одна из двух.

- [ ] **Step 2: собрать под Android**

```bash
cd ~/src/xsystem4-android
export ANDROID_SDK_ROOT=$HOME/Android/Sdk ANDROID_NDK_HOME=$HOME/Android/Sdk/ndk/29.0.14206865 ABI_NAMES=arm64-v8a
./build-shared-libs.sh 2>&1 | tail -3
```
Ожидаемо: сборка ок (Kotlin-класса ещё нет — JNI связывается лениво по символам,
это нормально).

- [ ] **Step 3: коммит (submodule)**

```bash
cd ~/src/xsystem4-android/xsystem4
git add src/android_bridge.c && git commit -m "android_bridge: JNI plumbing for TTS"
```

---

### Task 4: Kotlin — NativeBridge + TtsSpeaker (+ юнит-тест языкового детектора)

**Files:**
- Create: `project/app/src/main/java/io/github/kichikuou/xsystem4/NativeBridge.kt`
- Create: `project/app/src/main/java/io/github/kichikuou/xsystem4/TtsSpeaker.kt`
- Create: `project/app/src/test/java/io/github/kichikuou/xsystem4/TtsSegmenterTest.kt`
- Modify: `project/app/src/main/java/io/github/kichikuou/xsystem4/XSystem4Activity.kt`
- Modify: `project/app/build.gradle` (testImplementation junit)

**Interfaces:**
- Consumes: JNI-нативы Task 3.
- Produces:
  - `object NativeBridge { fun init(listener:(String,Boolean)->Unit); fun setTts(on:Boolean); @JvmStatic fun onAdvText(text:String,hasVoice:Boolean); external fun nativeInit(); external fun nativeSetTts(on:Boolean) }`
  - `class TtsSpeaker(context)` с `fun speak(text:String)`, `fun setEnabled(on:Boolean)`, `fun shutdown()`.
  - `object TtsSegmenter { fun segments(text:String): List<Pair<String,String>> }` —
    режет строку на пары (языковой тег "ru"/"ja"/"en", кусок текста).

- [ ] **Step 1: юнит-тест сегментатора (falling test)**

`project/app/src/test/java/io/github/kichikuou/xsystem4/TtsSegmenterTest.kt`:
```kotlin
package io.github.kichikuou.xsystem4

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsSegmenterTest {
    @Test fun cyrillicIsRu() =
        assertEquals(listOf("ru" to "Привет, мир!"), TtsSegmenter.segments("Привет, мир!"))
    @Test fun kanaIsJa() =
        assertEquals(listOf("ja" to "こんにちは"), TtsSegmenter.segments("こんにちは"))
    @Test fun latinIsEn() =
        assertEquals(listOf("en" to "Hello world"), TtsSegmenter.segments("Hello world"))
    @Test fun mixedSplits() {
        val s = TtsSegmenter.segments("Привет 「こんにちは」 friend")
        assertEquals(listOf("ru", "ja", "en"), s.map { it.first })
    }
    @Test fun punctuationSticksToPrevious() =
        assertEquals(listOf("ru" to "Да… да."), TtsSegmenter.segments("Да… да."))
}
```
В `build.gradle` dependencies добавить `testImplementation 'junit:junit:4.13.2'`.

- [ ] **Step 2: убедиться, что тест падает**

`cd project && ./gradlew testDebugUnitTest --tests '*TtsSegmenter*'` → FAIL (класса нет).

- [ ] **Step 3: реализовать сегментатор**

В `TtsSpeaker.kt` (тот же файл, отдельный object):
```kotlin
object TtsSegmenter {
    private fun langOf(c: Char): String? = when (c.code) {
        in 0x0400..0x04FF -> "ru"                       // кириллица
        in 0x3040..0x30FF, in 0x4E00..0x9FFF,
        in 0x31F0..0x31FF, in 0xFF66..0xFF9F -> "ja"    // кана/кандзи
        in 0x0041..0x005A, in 0x0061..0x007A,
        in 0xFF21..0xFF3A, in 0xFF41..0xFF5A -> "en"    // латиница (+полноширинная)
        else -> null                                    // цифры/знаки липнут к соседу
    }

    fun segments(text: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, StringBuilder>>()
        var cur: String? = null
        for (c in text) {
            val l = langOf(c)
            if (l != null && l != cur) { cur = l; out.add(l to StringBuilder()) }
            if (out.isEmpty()) { cur = null; continue }  // ведущие знаки без языка — отбросить
            out.last().second.append(c)
        }
        return out.map { it.first to it.second.toString().trim() }
                  .filter { it.second.isNotEmpty() }
    }
}
```

- [ ] **Step 4: тест зелёный**

`./gradlew testDebugUnitTest --tests '*TtsSegmenter*'` → PASS (поправить
реализацию/ожидания по фактическому поведению trim, если разойдутся).

- [ ] **Step 5: NativeBridge и TtsSpeaker**

`NativeBridge.kt`:
```kotlin
package io.github.kichikuou.xsystem4

object NativeBridge {
    private var listener: ((String, Boolean) -> Unit)? = null

    fun init(l: (String, Boolean) -> Unit) {
        listener = l
        nativeInit()
    }
    fun setTts(on: Boolean) = nativeSetTts(on)

    @JvmStatic
    fun onAdvText(text: String, hasVoice: Boolean) {   // зовётся из потока VM
        listener?.invoke(text, hasVoice)
    }

    private external fun nativeInit()
    private external fun nativeSetTts(on: Boolean)
}
```

`TtsSpeaker.kt`:
```kotlin
package io.github.kichikuou.xsystem4

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale

class TtsSpeaker(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var enabled = false
    private var utterance = 0
    private val tts = TextToSpeech(context) { status ->
        ready = status == TextToSpeech.SUCCESS
    }

    fun setEnabled(on: Boolean) { enabled = on; if (!on) tts.stop() }

    fun speak(text: String) {
        if (!enabled || !ready) return
        main.post {
            for ((lang, chunk) in TtsSegmenter.segments(text)) {
                tts.language = when (lang) {
                    "ru" -> Locale("ru"); "ja" -> Locale.JAPANESE; else -> Locale.ENGLISH
                }
                tts.speak(chunk, TextToSpeech.QUEUE_ADD, null, "adv${utterance++}")
            }
        }
    }

    fun shutdown() = tts.shutdown()
}
```
(Смена `tts.language` между QUEUE_ADD-фразами применяется на момент вызова speak —
приемлемо; если на устройстве язык «уедет», перейти на per-utterance
`UtteranceProgressListener` — заметка, не делать заранее.)

`XSystem4Activity.kt` — в `onCreate` после `super.onCreate`:
```kotlin
tts = TtsSpeaker(this)
NativeBridge.init { text, _ -> tts.speak(text) }
NativeBridge.setTts(true)   // ВРЕМЕННО всегда вкл; панель — Task 5
tts.setEnabled(true)
```
поле `private lateinit var tts: TtsSpeaker`, в `onDestroy()` — `tts.shutdown()`.

- [ ] **Step 6: сборка, установка, проверка на устройстве**

```bash
cd ~/src/xsystem4-android/project && ./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n io.github.kichikuou.xsystem4/.LauncherActivity
```
Пользователь запускает игру и начинает новую игру / открывает сцену.
Ожидаемо: телефон читает текст вслух по-русски; штатный голос молчит
(если голос слышен — на устройстве смотреть logcat `adb logcat -s xsystem4`,
имена микшеров, скорректировать фильтр Task 2).

- [ ] **Step 7: эмпирика — имя говорящего**

По Linux-логу Task 1 Step 6 (или logcat на устройстве) посмотреть, как выглядят
строки с репликами персонажей: есть ли отдельная строка/префикс с именем
(например `【名前】`, `имя「…」` или имя отдельной короткой строкой перед репликой).
- Если имя отличимо детерминированным правилом → реализовать в `TtsSpeaker.speak`:
  выделить имя, хранить `lastSpeaker`, произносить имя только при
  `name != lastSpeaker`, тело реплики — всегда.
- Если нет → зафиксировать вывод комментарием в TtsSpeaker.kt («имена не текут
  через ADV-лог — не озвучиваем», риск №1 спеки) и двигаться дальше.

- [ ] **Step 8: коммит (app-репозиторий)**

```bash
cd ~/src/xsystem4-android
git add project/app/src/main/java/io/github/kichikuou/xsystem4/NativeBridge.kt \
        project/app/src/main/java/io/github/kichikuou/xsystem4/TtsSpeaker.kt \
        project/app/src/test/java/io/github/kichikuou/xsystem4/TtsSegmenterTest.kt \
        project/app/src/main/java/io/github/kichikuou/xsystem4/XSystem4Activity.kt \
        project/app/build.gradle
git commit -m "feat: TTS narration of ADV text via NativeBridge"
```

---

### Task 5: Kotlin — боковая панель (ручка + drawer) с переключателем TTS

**Files:**
- Create: `project/app/src/main/java/io/github/kichikuou/xsystem4/EdgePanel.kt`
- Modify: `project/app/src/main/java/io/github/kichikuou/xsystem4/XSystem4Activity.kt`

**Interfaces:**
- Consumes: `NativeBridge.setTts`, `TtsSpeaker.setEnabled`.
- Produces: `class EdgePanel(activity, root: ViewGroup)` — сам добавляет ручку и
  drawer в `root`; `fun addTab(title: String, content: View)`; публичный
  `val prefs: SharedPreferences` (имя файла "bridge_prefs").

- [ ] **Step 1: EdgePanel программно (без XML — виджеты простые)**

`EdgePanel.kt`:
```kotlin
package io.github.kichikuou.xsystem4

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*

class EdgePanel(private val activity: Activity, root: ViewGroup) {
    val prefs = activity.getSharedPreferences("bridge_prefs", 0)!!
    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val drawer = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.argb(230, 30, 34, 48))
        setPadding(dp(12), dp(12), dp(12), dp(12))
        visibility = View.GONE
    }
    private val tabs = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
    private val content = FrameLayout(activity)
    private val pages = mutableListOf<View>()

    init {
        drawer.addView(tabs)
        drawer.addView(content, LinearLayout.LayoutParams(-1, -1))
        val handle = View(activity).apply {
            setBackgroundColor(Color.argb(90, 255, 255, 255))
            setOnClickListener { toggle() }
        }
        root.addView(handle, FrameLayout.LayoutParams(dp(6), dp(48),
            Gravity.END or Gravity.CENTER_VERTICAL))
        root.addView(drawer, FrameLayout.LayoutParams(dp(300), -1, Gravity.END))
    }

    fun toggle() { drawer.visibility = if (drawer.visibility == View.VISIBLE) View.GONE else View.VISIBLE }

    fun addTab(title: String, page: View) {
        val idx = pages.size
        pages.add(page)
        content.addView(page, FrameLayout.LayoutParams(-1, -1))
        page.visibility = if (idx == 0) View.VISIBLE else View.GONE
        tabs.addView(Button(activity).apply {
            text = title
            setOnClickListener { pages.forEachIndexed { i, p ->
                p.visibility = if (i == idx) View.VISIBLE else View.GONE } }
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }
}
```
`root` — это `SDLActivity.mLayout`? Он `protected static`; из Kotlin-наследника
доступен как `SDLActivity.mLayout`. Он `RelativeLayout` — Gravity-параметры
FrameLayout не сработают. Решение: добавить поверх собственный FrameLayout:
`val overlay = FrameLayout(this); mLayout.addView(overlay, RelativeLayout.LayoutParams(-1,-1))`
и передавать его в EdgePanel.

- [ ] **Step 2: вкладка TTS + интеграция в активити**

В `XSystem4Activity.onCreate` (заменяя ВРЕМЕННОЕ включение из Task 4):
```kotlin
val overlay = android.widget.FrameLayout(this)
SDLActivity.mLayout.addView(overlay,
    android.widget.RelativeLayout.LayoutParams(-1, -1))
val panel = EdgePanel(this, overlay)

tts = TtsSpeaker(this)
NativeBridge.init { text, _ -> tts.speak(text) }

val ttsPage = android.widget.LinearLayout(this).apply {
    orientation = android.widget.LinearLayout.VERTICAL
    val sw = android.widget.Switch(this@XSystem4Activity).apply {
        text = "Читать текст (TTS)"
        isChecked = panel.prefs.getBoolean("tts", false)
        setOnCheckedChangeListener { _, on ->
            panel.prefs.edit().putBoolean("tts", on).apply()
            NativeBridge.setTts(on); tts.setEnabled(on)
        }
    }
    addView(sw)
}
panel.addTab("TTS", ttsPage)
val startOn = panel.prefs.getBoolean("tts", false)
NativeBridge.setTts(startOn); tts.setEnabled(startOn)
```

- [ ] **Step 3: сборка, установка, проверка**

`./gradlew assembleDebug && adb install -r ...` — на устройстве: ручка видна у
правого края, тап открывает панель, переключатель включает/выключает чтение и
глушение голоса; состояние переживает перезапуск.

- [ ] **Step 4: коммит (app)**

```bash
cd ~/src/xsystem4-android
git add project/app/src/main/java/io/github/kichikuou/xsystem4/EdgePanel.kt \
        project/app/src/main/java/io/github/kichikuou/xsystem4/XSystem4Activity.kt
git commit -m "feat: edge panel with TTS toggle"
```

---

### Task 6: Мост — API читов (список/чтение/запись/скан глобалов)

**Files:**
- Modify: `xsystem4/include/android_bridge.h`
- Modify: `xsystem4/src/android_bridge.c`

**Interfaces:**
- Produces (C, общие для Linux-заглушки и JNI):
  - `struct bridge_var { int page_slot; int varno; char *name_utf8; int value; };`
    (`page_slot` = слот heap-страницы; для самой глобальной страницы = 0 — у
    xsystem4 глобальная страница живёт в heap slot 0, см. `vm.c:2454`)
  - `int bridge_cheat_list(const char *filter_utf8, struct bridge_var **out, int max);`
    — все int/bool-глобалы верхнего уровня, имя из `ain->globals[i].name`
    (SJIS→UTF-8), фильтр = подстрока (без регистра не требуется);
  - `int bridge_cheat_read(int page_slot, int varno, int *ok);`
  - `bool bridge_cheat_write(int page_slot, int varno, int value);`
  - `int bridge_cheat_scan(int value, bool narrow, struct bridge_var **out, int max);`
    — рекурсивный обход от глобальной страницы (структуры/массивы по слотам heap,
    посещённые слоты в hash-set), кандидаты хранятся в static-массиве моста;
  - `void bridge_cheat_free(struct bridge_var *arr, int n);`

- [ ] **Step 1: реализация в android_bridge.c**

```c
#include "vm.h"
#include "vm/heap.h"
#include "vm/page.h"
#include "xsystem4.h"        // ain
#include "system4/ain.h"

#define SCAN_MAX 100000
static struct { int page_slot; int varno; } *scan_cands = NULL;
static int scan_n = 0;

static bool page_slot_valid(int slot)
{
	return heap_index_valid(slot) && heap[slot].type == VM_PAGE && heap_get_page(slot);
}

static int read_var(int page_slot, int varno, int *ok)
{
	*ok = 0;
	if (!page_slot_valid(page_slot)) return 0;
	struct page *p = heap_get_page(page_slot);
	if (varno < 0 || varno >= p->nr_vars) return 0;
	enum ain_data_type t = variable_type(p, varno, NULL, NULL);
	if (t != AIN_INT && t != AIN_BOOL && t != AIN_LONG_INT) return 0;
	*ok = 1;
	return p->values[varno].i;
}
```
(точные имена `heap_index_valid`/`heap[slot]` сверить с `include/vm/heap.h` —
использовать фактический API; если прямого валидатора нет, обходиться
`heap_get_page` + собственной проверкой границ.)

Скан:
```c
static void scan_page(int page_slot, int value, int depth)
{
	if (scan_n >= SCAN_MAX || depth > 8 || !page_slot_valid(page_slot)) return;
	struct page *p = heap_get_page(page_slot);
	for (int i = 0; i < p->nr_vars; i++) {
		enum ain_data_type t = variable_type(p, i, NULL, NULL);
		switch (t) {
		case AIN_INT: case AIN_BOOL: case AIN_LONG_INT:
			if (p->values[i].i == value && scan_n < SCAN_MAX) {
				scan_cands[scan_n].page_slot = page_slot;
				scan_cands[scan_n].varno = i;
				scan_n++;
			}
			break;
		case AIN_STRUCT: case AIN_ARRAY_TYPE: // + AIN_ARRAY_INT и т.п. — сверить enum
			if (p->values[i].i > 0)
				scan_page(p->values[i].i, value, depth + 1);
			break;
		default: break;
		}
	}
}
```
Точные значения enum для массивов взять из `system4/ain.h` (там семейство
`AIN_ARRAY_*`); критерий «спускаться» — вариант по факту: `variable_type`
возвращает конкретный тип; для массивов ref-типов у страницы `ARRAY_PAGE`
элементы имеют тип элемента. Повторные посещения слотов допустимы (дерево страниц
ациклично в System 4; глубина ограничена depth=8).

`bridge_cheat_scan(value,narrow,...)`: narrow=false → `scan_n=0; scan_page(0,...)`;
narrow=true → компакция scan_cands перечитыванием `read_var`. Наружу отдаётся
до `max` первых кандидатов с именами: имя = для верхнего уровня
`ain->globals[varno].name` (только если page_slot==0), иначе синтетическое
`"[slot %d].%d"` — этого достаточно для value-сканов.

`bridge_cheat_list`: обход только страницы 0, имена из `ain->globals`,
`sjis2utf` для UTF-8, фильтр `strstr` по UTF-8.

- [ ] **Step 2: Linux-проверка заглушкой**

Во временный `main`-путь не лезем: в Linux-секции `bridge_emit` добавить
одноразовый вызов по env `XS4_CHEAT_TEST=<число>`: при первом эмите текста
выполнить `bridge_cheat_scan(atoi(getenv(...)), false, ...)` и напечатать
количество и первые 10 кандидатов с именами. Запустить игру, дойти до сцены,
убедиться, что скан не падает и находит кандидатов. Удалить/оставить за env —
оставить (безвредно).

- [ ] **Step 3: коммит (submodule)**

```bash
cd ~/src/xsystem4-android/xsystem4
git add include/android_bridge.h src/android_bridge.c
git commit -m "android_bridge: VM globals list/read/write/scan for cheat panel"
```

---

### Task 7: JNI-нативы читов + вкладка «Читы» (браузер + правка)

**Files:**
- Modify: `xsystem4/src/android_bridge.c` (JNI-секция)
- Create: `project/app/src/main/java/io/github/kichikuou/xsystem4/CheatPanel.kt`
- Modify: `project/app/src/main/java/io/github/kichikuou/xsystem4/XSystem4Activity.kt`

**Interfaces:**
- Produces (JNI, в объекте NativeBridge):
  - `external fun cheatList(filter: String): Array<String>` — элементы
    "pageSlot\tvarno\tname\tvalue" (плоская строка — минимум JNI-кода);
  - `external fun cheatScan(value: Int, narrow: Boolean): Array<String>` — тот же формат;
  - `external fun cheatRead(pageSlot: Int, varno: Int): Long` —  (-1L«не ок») —
    вернуть `((long)ok<<32)|value` для простоты: Kotlin разбирает;
  - `external fun cheatWrite(pageSlot: Int, varno: Int, value: Int): Boolean`
- Produces (Kotlin): `class CheatPanel(activity, prefs): View-страница` c
  `val view: View` для `EdgePanel.addTab`.

- [ ] **Step 1: JNI-нативы**

В JNI-секцию `android_bridge.c` — четыре функции: обёртки над Task 6 API,
строящие `jobjectArray` строк формата "slot\tvarno\tname\tvalue" через
`snprintf` + `NewStringUTF`. Не забыть `bridge_cheat_free`.

- [ ] **Step 2: CheatPanel — браузер**

`CheatPanel.kt` (ListView + адаптер, EditText-фильтр, кнопка «Обновить»,
клик по строке → AlertDialog с числовым полем → `NativeBridge.cheatWrite`):
```kotlin
package io.github.kichikuou.xsystem4

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.text.InputType
import android.widget.*

class CheatPanel(private val activity: Activity, private val prefs: SharedPreferences) {
    data class Row(val slot: Int, val varno: Int, val name: String, var value: Int) {
        override fun toString() = "$name = $value"
    }
    private val rows = mutableListOf<Row>()
    private val adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, rows)
    private val filter = EditText(activity).apply { hint = "фильтр по имени" }
    val view = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(filter)
        val refresh = Button(activity).apply { text = "Обновить"; setOnClickListener { reload() } }
        addView(refresh)
        val list = ListView(activity).apply {
            adapter = this@CheatPanel.adapter
            setOnItemClickListener { _, _, pos, _ -> edit(rows[pos]) }
        }
        addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun parse(lines: Array<String>): List<Row> = lines.mapNotNull {
        val p = it.split('\t')
        if (p.size == 4) Row(p[0].toInt(), p[1].toInt(), p[2], p[3].toInt()) else null
    }

    fun reload() {
        rows.clear()
        rows.addAll(parse(NativeBridge.cheatList(filter.text.toString())))
        adapter.notifyDataSetChanged()
    }

    private fun edit(row: Row) {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(row.value.toString())
        }
        AlertDialog.Builder(activity).setTitle(row.name).setView(input)
            .setPositiveButton("OK") { _, _ ->
                input.text.toString().toIntOrNull()?.let { v ->
                    if (NativeBridge.cheatWrite(row.slot, row.varno, v)) {
                        row.value = v; adapter.notifyDataSetChanged()
                    } else Toast.makeText(activity, "не записалось", Toast.LENGTH_SHORT).show()
                }
            }.setNegativeButton("Отмена", null).show()
    }
}
```
В `XSystem4Activity`: `panel.addTab("Читы", CheatPanel(this, panel.prefs).view)`.
В `NativeBridge` добавить 4 external fun (сигнатуры из Interfaces).

- [ ] **Step 3: пересобрать нативную часть + APK, установка, проверка**

`./build-shared-libs.sh && cd project && ./gradlew assembleDebug && adb install -r ...`
Проверка: открыть панель → Читы → «Обновить» — список приходит; изменить любую
безобидную переменную и увидеть эффект/отсутствие падения.

- [ ] **Step 4: коммиты (submodule + app)**

```bash
cd ~/src/xsystem4-android/xsystem4 && git add src/android_bridge.c && git commit -m "android_bridge: JNI cheat natives"
cd ~/src/xsystem4-android && git add project/app/src/main/java/io/github/kichikuou/xsystem4/CheatPanel.kt \
    project/app/src/main/java/io/github/kichikuou/xsystem4/NativeBridge.kt \
    project/app/src/main/java/io/github/kichikuou/xsystem4/XSystem4Activity.kt
git commit -m "feat: cheat panel — globals browser with editing"
```

---

### Task 8: Скан по значению (UI сужения)

**Files:**
- Modify: `project/app/src/main/java/io/github/kichikuou/xsystem4/CheatPanel.kt`

**Interfaces:**
- Consumes: `NativeBridge.cheatScan(value, narrow)`.

- [ ] **Step 1: UI**

В `CheatPanel.view` над списком добавить горизонтальный ряд:
```kotlin
private val scanValue = EditText(activity).apply {
    hint = "значение"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
}
private val scanInfo = TextView(activity)
// в init view:
addView(LinearLayout(activity).apply {
    addView(scanValue, LinearLayout.LayoutParams(0, -2, 1f))
    addView(Button(activity).apply { text = "Поиск"; setOnClickListener { scan(false) } })
    addView(Button(activity).apply { text = "Сузить"; setOnClickListener { scan(true) } })
})
addView(scanInfo)

private fun scan(narrow: Boolean) {
    val v = scanValue.text.toString().toIntOrNull() ?: return
    val res = NativeBridge.cheatScan(v, narrow)
    scanInfo.text = "кандидатов: ${res.size}"
    rows.clear()
    if (res.size <= 500) rows.addAll(parse(res))
    adapter.notifyDataSetChanged()
}
```

- [ ] **Step 2: сборка, установка, сценарий с деньгами**

На устройстве: посмотреть деньги в игре → «Поиск» → потратить/получить →
«Сузить» → повторить до ≤5 кандидатов → изменить значение → проверить в игре.

- [ ] **Step 3: коммит (app)**

```bash
cd ~/src/xsystem4-android
git add project/app/src/main/java/io/github/kichikuou/xsystem4/CheatPanel.kt
git commit -m "feat: cheat panel — Cheat-Engine-style value scan"
```

---

### Task 9: ML Kit перевод имён переменных (опция)

**Files:**
- Modify: `project/app/build.gradle` (зависимость)
- Create: `project/app/src/main/java/io/github/kichikuou/xsystem4/NameTranslator.kt`
- Modify: `project/app/src/main/java/io/github/kichikuou/xsystem4/CheatPanel.kt`

**Interfaces:**
- Produces: `class NameTranslator { fun translate(name:String, cb:(String)->Unit); fun ensureModel(onReady:(Boolean)->Unit); fun close() }` — JA→RU, кэш в памяти.

- [ ] **Step 1: зависимость**

`build.gradle` dependencies: `implementation 'com.google.mlkit:translate:17.0.2'`.

- [ ] **Step 2: NameTranslator**

```kotlin
package io.github.kichikuou.xsystem4

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.*

class NameTranslator {
    private val cache = HashMap<String, String>()
    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.JAPANESE)
            .setTargetLanguage(TranslateLanguage.RUSSIAN)
            .build())

    fun ensureModel(onReady: (Boolean) -> Unit) {
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { onReady(true) }
            .addOnFailureListener { onReady(false) }
    }

    fun translate(name: String, cb: (String) -> Unit) {
        cache[name]?.let { cb(it); return }
        if (!name.any { it.code in 0x3040..0x9FFF }) { cb(name); return }
        translator.translate(name)
            .addOnSuccessListener { cache[name] = it; cb(it) }
            .addOnFailureListener { cb(name) }
    }

    fun close() = translator.close()
}
```

- [ ] **Step 3: интеграция в CheatPanel**

Switch «Переводить имена» над фильтром; при включении — `ensureModel`,
на false — тост «модель недоступна» и откат Switch. Row получает поле
`var display: String` (=name), `toString()` → `"$display = $value"`;
после reload/scan при включённом переводе для каждого имени зовётся
`translator.translate(name) { row.display = it; adapter.notifyDataSetChanged() }`.
Состояние — `prefs.putBoolean("translate_names", …)`.

- [ ] **Step 4: сборка, установка, проверка (Wi-Fi нужен для первой загрузки модели)**

Ожидаемо: имена вида `研究Ｌｖ` показываются как перевод; при отсутствии
Google-сервисов/сети — тост, опция выключена, панель работает.

- [ ] **Step 5: коммит (app)**

```bash
cd ~/src/xsystem4-android
git add project/app/build.gradle \
    project/app/src/main/java/io/github/kichikuou/xsystem4/NameTranslator.kt \
    project/app/src/main/java/io/github/kichikuou/xsystem4/CheatPanel.kt
git commit -m "feat: optional ML Kit translation of variable names"
```

---

### Task 10: финальная сборка, документация

**Files:**
- Modify: `/mnt/sda1/DaiteikokuAndroid/PROGRESS.md`

- [ ] **Step 1: полная пересборка и установка**

```bash
cd ~/src/xsystem4-android && ./build-shared-libs.sh && cd project && ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
cp app/build/outputs/apk/debug/app-debug.apk /mnt/sda1/DaiteikokuAndroid/xsystem4-android-tts-cheats-arm64-debug.apk
```

- [ ] **Step 2: сквозная проверка на устройстве (пользователь)**

TTS на стартовой сцене; голос заглушен; панель открывается/закрывается;
деньги найдены и изменены; перевод имён включается.

- [ ] **Step 3: обновить PROGRESS.md** — раздел о новых возможностях сборки,
путь APK, где ветки (`xsystem4-android` master + submodule ветка `tts-cheats`).

- [ ] **Step 4: финальный коммит (app)** — PROGRESS.md вне репо, коммитить нечего,
проверить `git status` в обоих репозиториях: чисто.
