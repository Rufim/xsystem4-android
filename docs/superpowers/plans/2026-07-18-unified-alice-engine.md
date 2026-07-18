# Единое приложение xsystem4 + xsystem35 — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Собрать одно Android-приложение, запускающее игры на движках xsystem4 (System 4) и xsystem35 (System 3.x), с паритетом фич TTS / читы / авто-листание на обоих.

**Architecture:** База — репозиторий `xsystem4-android`. Движок xsystem35 подключается git-сабмодулем и собирается как второй `ExternalProject` в верхнеуровневом `CMakeLists.txt`, разделяя единый `libSDL2.so` с xsystem4. Обе `.so` кладутся в общий `jniLibs`; Gradle пакует их в один APK. Каждая игровая активити работает в своём процессе (`:engine4` / `:engine35`) ради чистой переинициализации SDL. Движко-независимый Kotlin (TTS, читы, ML Kit, панель) выносится в общий базовый класс.

**Tech Stack:** C (движки), CMake + Ninja + Android NDK (кросс-компиляция нативных `.so`), Kotlin + Gradle (Android-приложение), SDL2 2.32.10 + SDL_ttf + SDL_mixer (для xsystem35), JNI, Google ML Kit Translate, JUnit4 (Kotlin unit-тесты).

## Global Constraints

- Рабочий каталог базы: `/home/rufim/src/xsystem4-android`.
- `applicationId` / `namespace` итогового приложения: `io.github.rufim.alice` (не конфликтует с уже установленным `io.github.kichikuou.xsystem4`).
- Пакет всех Kotlin-классов приложения: `io.github.rufim.alice`.
- Каноническое имя JNI-класса моста: `io.github.rufim.alice.NativeBridge`. **Оба** движка должны экспортировать символы вида `Java_io_github_rufim_alice_NativeBridge_native*`.
- ABI по умолчанию для сборки: только `arm64-v8a` и `x86_64` (`export ABI_NAMES="arm64-v8a x86_64"`).
- Единая версия SDL: `SDL 2.32.10` для обоих движков (один `libSDL2.so` в APK).
- NDK: `/home/rufim/Android/Sdk/ndk/30.0.14904198`; SDK: `/home/rufim/Android/Sdk` (`ANDROID_HOME` уже выставлен).
- minSdk 21, compileSdk/targetSdk 34.
- `vmvar_t` в xsystem35 = `uint16_t` (значения переменных 0..65535).
- Коммиты частые; каждый Task заканчивается рабочим, проверяемым артефактом. Сообщения коммитов на русском, с трейлером `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.
- Личное использование; APK не распространяется.

---

## Обзор файловой структуры

Создаётся:
- `.gitmodules` (доп. запись) + `xsystem35/` — сабмодуль движка xsystem35.
- `xsystem35/src/android_bridge.c`, `xsystem35/src/android_bridge.h` — мост движок↔Android (создаём в дереве сабмодуля; далее в форк Rufim/xsystem35-sdl2).
- `project/app/src/main/java/io/github/rufim/alice/*.kt` — перенос и адаптация Kotlin.
- `project/app/src/main/java/io/github/rufim/alice/EngineActivity.kt` — общий базовый класс.
- `project/app/src/main/java/io/github/rufim/alice/XSystem35Activity.kt` — активити System 3.x.
- `project/app/src/test/java/io/github/rufim/alice/EngineDetectTest.kt`, `TtsSegmenterTest.kt`.

Модифицируется:
- `CMakeLists.txt` (верхний) — добавить сборку xsystem35 + install его `.so`.
- `xsystem35/src/texthook.c`, `texthook.h` — Android-режим.
- `xsystem35/src/variable.c`, `variable.h` — `v_name_count()`.
- `xsystem35/src/CMakeLists.txt` — добавить `android_bridge.c` в сборку.
- `project/app/build.gradle` — namespace, applicationId, MIDI/coroutines зависимости.
- `project/app/src/main/AndroidManifest.xml` — две активити с `android:process`, `<queries>`.
- Kotlin: `LauncherActivity`, `GameList`, `NativeBridge`, `XSystem4Activity` → пакет/логика.

---

## Фаза 0 — Каркас единой сборки

### Task 0.1: Подключить xsystem35 сабмодулем и собрать его штатно

**Files:**
- Modify: `.gitmodules`
- Create: `xsystem35/` (сабмодуль)

**Interfaces:**
- Produces: рабочее дерево `xsystem35/` с `src/`, `android/`, `CMakeLists.txt` в корне сабмодуля.

- [ ] **Step 1: Форкнуть и добавить сабмодуль**

```bash
cd /home/rufim/src/xsystem4-android
gh repo fork kichikuou/xsystem35-sdl2 --clone=false --remote=false 2>/dev/null || true
git submodule add https://github.com/Rufim/xsystem35-sdl2.git xsystem35
cd xsystem35 && git submodule update --init --recursive && cd ..
```

- [ ] **Step 2: Проверить наличие ключевых файлов**

Run: `ls xsystem35/src/texthook.c xsystem35/src/variable.c xsystem35/CMakeLists.txt`
Expected: все три пути существуют.

- [ ] **Step 3: Пробная нативная сборка xsystem35 отдельно (санити)**

Прежде чем объединять, убедиться, что xsystem35 вообще собирается его собственным CMake под Android arm64:

```bash
export ANDROID_NDK_HOME=/home/rufim/Android/Sdk/ndk/30.0.14904198
cd /home/rufim/src/xsystem4-android/xsystem35
cmake -B /tmp/xs35test -S . -GNinja \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_ANDROID_ARCH_ABI=arm64-v8a \
  -DANDROID_PLATFORM=21 \
  -DANDROID_USE_LEGACY_TOOLCHAIN_FILE=OFF \
  -DCMAKE_TOOLCHAIN_FILE=${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake
ninja -C /tmp/xs35test xsystem35
```
Expected: цель `xsystem35` собирается, появляется `/tmp/xs35test/xsystem35` или `libxsystem35.*`. Если падает на flex/bison/зависимостях — доставить пакеты (`flex`, `bison`) и повторить. Зафиксировать в заметке, какие цели/имена артефактов даёт сборка (понадобится в Task 0.2).

- [ ] **Step 4: Commit**

```bash
cd /home/rufim/src/xsystem4-android
git add .gitmodules xsystem35
git commit -m "build: подключить xsystem35 сабмодулем

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 0.2: Собрать обе `.so` из одного верхнего CMake с общим SDL2

**Files:**
- Modify: `CMakeLists.txt` (верхний)

**Interfaces:**
- Consumes: имена целей/артефактов xsystem35 из Task 0.1 Step 3.
- Produces: `project/app/src/main/jniLibs/<abi>/libxsystem35.so` рядом с `libxsystem4.so`, обе против одного `libSDL2.so`.

Проблема: xsystem4 собирает SDL2 внутри себя; xsystem35 тянет SDL 2.32.10 + ttf + mixer через FetchContent. Чтобы в APK был один `libSDL2.so`, xsystem35 должен использовать уже собранный SDL2 из stage. Стратегия: добавить xsystem35 `ExternalProject`, зависящий от сборки SDL2 (через `CMAKE_FIND_ROOT_PATH=${stagingDir}`), и передать ему `-DSDL2_DIR`/`-DCMAKE_PREFIX_PATH=${stagingDir}`, чтобы `find_package(SDL2)` нашёл готовый. SDL_ttf/SDL_mixer, нужные xsystem35, добавить как отдельные `ExternalProject` в stage.

- [ ] **Step 1: Добавить SDL2_ttf и SDL2_mixer как ExternalProject**

В `CMakeLists.txt` перед блоком `xsystem4` добавить (URL/хеши — как в `xsystem35/android/app/jni/CMakeLists.txt`):

```cmake
ExternalProject_Add(
  sdl2_ttf
  URL https://github.com/libsdl-org/SDL_ttf/releases/download/release-2.22.0/SDL2_ttf-2.22.0.tar.gz
  URL_HASH SHA1=da5e86b601ad299a697878fab1af6f3be47b529d
  CMAKE_ARGS ${COMMON_CMAKE_ARGS}
             -DSDL2TTF_SAMPLES=OFF
             -DSDL2TTF_INSTALL=ON
             -DSDL2TTF_VENDORED=ON
)
ExternalProject_Add(
  sdl2_mixer
  URL https://github.com/libsdl-org/SDL_mixer/releases/download/release-2.8.0/SDL2_mixer-2.8.0.tar.gz
  URL_HASH SHA1=a58c69f9d00e44833b9e00e1adb58d85759ca499
  CMAKE_ARGS ${COMMON_CMAKE_ARGS}
             -DSDL2MIXER_OPUS=OFF -DSDL2MIXER_FLAC=OFF -DSDL2MIXER_MOD=OFF
             -DSDL2MIXER_MIDI=OFF -DSDL2MIXER_WAVPACK=OFF
             -DSDL2MIXER_SAMPLES=OFF -DSDL2MIXER_INSTALL=ON
)
```

> Примечание: `sdl2_ttf`/`sdl2_mixer` зависят от SDL2. SDL2 в этой сборке собирается как часть `xsystem4`. Чтобы разорвать порядок, добавить в конце (после объявления `xsystem4`) зависимости шага configure — см. Step 3.

- [ ] **Step 2: Добавить ExternalProject для xsystem35**

После блока `xsystem4` в `CMakeLists.txt`:

```cmake
ExternalProject_Add(
  xsystem35
  SOURCE_DIR ${CMAKE_CURRENT_LIST_DIR}/xsystem35
  CMAKE_ARGS ${COMMON_CMAKE_ARGS}
             -DCMAKE_PREFIX_PATH=${stagingDir}
             -DUSE_SYSTEM_SDL2=ON
  BUILD_ALWAYS YES
)
```

Если корневой `xsystem35/CMakeLists.txt` жёстко делает `FetchContent` SDL (нет опции использовать системный), в форке `Rufim/xsystem35-sdl2` добавить опцию `USE_SYSTEM_SDL2`: при ней вместо `FetchContent_MakeAvailable(SDL ...)` вызывать `find_package(SDL2 REQUIRED)` / `find_package(SDL2_ttf REQUIRED)` / `find_package(SDL2_mixer REQUIRED)`. (Правку корневого CMake сабмодуля закоммитить в форк отдельно.)

- [ ] **Step 3: Прописать порядок зависимостей сборки**

```cmake
ExternalProject_Add_StepDependencies(sdl2_ttf configure xsystem4)
ExternalProject_Add_StepDependencies(sdl2_mixer configure xsystem4)
ExternalProject_Add_StepDependencies(xsystem35 configure xsystem4 sdl2_ttf sdl2_mixer)
```
(зависимость от `xsystem4` гарантирует, что `libSDL2.so` уже в stage до сборки ttf/mixer/xsystem35).

- [ ] **Step 4: Установить `libxsystem35.so` (+ ttf/mixer) в jniLibs**

Дополнить блок `install(...)`:

```cmake
install(
  FILES ${stagingDir}/lib/libxsystem35.so
        ${stagingDir}/lib/libSDL2_ttf.so
        ${stagingDir}/lib/libSDL2_mixer.so
  DESTINATION ${androidProjectDir}/app/src/main/jniLibs/${CMAKE_ANDROID_ARCH_ABI})
```
> Если xsystem35 устанавливает движок под другим именем/путём — поправить `FILES` по факту из Task 0.1 Step 3.

- [ ] **Step 5: Полная нативная сборка обеих ABI**

```bash
cd /home/rufim/src/xsystem4-android
export ANDROID_NDK_HOME=/home/rufim/Android/Sdk/ndk/30.0.14904198
export ABI_NAMES="arm64-v8a x86_64"
./build-shared-libs.sh
```
Expected: команда завершается без ошибок.

- [ ] **Step 6: Проверить наличие и единственность libSDL2.so**

Run:
```bash
ls project/app/src/main/jniLibs/arm64-v8a/
```
Expected: присутствуют `libxsystem4.so`, `libxsystem35.so`, `libSDL2.so`, `libSDL2_ttf.so`, `libSDL2_mixer.so`, `libcglm.so` — по одному файлу `libSDL2.so`.

- [ ] **Step 7: Commit**

```bash
git add CMakeLists.txt project/app/src/main/jniLibs
git commit -m "build: собирать libxsystem35.so общим CMake с единым SDL2

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Фаза 1 — Нативные хуки xsystem35 (движок)

### Task 1.1: Android-режим text-hook в xsystem35

**Files:**
- Modify: `xsystem35/src/texthook.h`
- Modify: `xsystem35/src/texthook.c`
- Create: `xsystem35/src/android_bridge.h`

**Interfaces:**
- Produces: `bridge_adv_message(const char *utf8)`, `bridge_adv_newline(void)`, `bridge_adv_page_break(void)`, `bridge_adv_keywait(void)` — вызываются из texthook; реализация в `android_bridge.c` (Task 1.2).

- [ ] **Step 1: Объявить bridge-хуки в android_bridge.h**

Создать `xsystem35/src/android_bridge.h`:

```c
#ifndef XSYSTEM35_ANDROID_BRIDGE_H
#define XSYSTEM35_ANDROID_BRIDGE_H
#include <stdbool.h>

// ADV-текст для TTS (utf8-строка одного сообщения)
void bridge_adv_message(const char *utf8);
void bridge_adv_newline(void);
void bridge_adv_page_break(void);
void bridge_adv_keywait(void);

// Управление (зовётся из JNI)
void bridge_set_tts_enabled(bool on);
void bridge_advance_message(void);

// Читы (Task 1.3)
struct bridge_var { int page; int varno; char *name_utf8; int value; };
int  bridge_cheat_list(const char *filter_utf8, struct bridge_var **out, int max);
int  bridge_cheat_scan(int value, bool narrow, struct bridge_var **out, int max);
bool bridge_cheat_write(int page, int varno, int value);
void bridge_cheat_free(struct bridge_var *arr, int n);

#endif
```

- [ ] **Step 2: Добавить режим TEXTHOOK_ANDROID в texthook.h**

В enum `texthook_mode` добавить `TEXTHOOK_ANDROID` после `TEXTHOOK_COPY`.

- [ ] **Step 3: Вызывать bridge из texthook.c**

В `texthook.c` подключить `#include "android_bridge.h"`. В каждую из функций `texthook_message/newline/nextpage/keywait` добавить ветку режима `TEXTHOOK_ANDROID`. Пример для `texthook_message` — внутри `switch (mode)` (в `#else`-ветке, не-Emscripten):

```c
	case TEXTHOOK_ANDROID: {
		char *utf = toUTF8(m);
		bridge_adv_message(utf);
		free(utf);
		break;
	}
```
Аналогично: `texthook_newline` → `case TEXTHOOK_ANDROID: bridge_adv_newline(); break;`, `texthook_nextpage` → `bridge_adv_page_break();`, `texthook_keywait` → `bridge_adv_keywait();`.

- [ ] **Step 4: Санити-компиляция заголовка**

Run: `gcc -fsyntax-only -I xsystem35/src xsystem35/src/texthook.c 2>&1 | head` (ожидаемы предупреждения о недостающих SDL-инклудах — важно, что нет ошибок в наших правках синтаксиса texthook). Полная проверка — сборкой в Task 1.4.

- [ ] **Step 5: Commit**

```bash
git -C xsystem35 add src/texthook.c src/texthook.h src/android_bridge.h
git -C xsystem35 commit -m "tts: Android-режим text-hook"
```

---

### Task 1.2: Мост android_bridge.c (TTS-эмиттер + JNI)

**Files:**
- Create: `xsystem35/src/android_bridge.c`
- Modify: `xsystem35/src/CMakeLists.txt`

**Interfaces:**
- Consumes: bridge-хуки из Task 1.1.
- Produces: JNI-нативы `Java_io_github_rufim_alice_NativeBridge_native{Init,SetTts,Advance}`; статический `tts_enabled`.

- [ ] **Step 1: Реализация моста (общая часть + Linux-заглушка + Android JNI)**

Создать `xsystem35/src/android_bridge.c` по образцу xsystem4-версии, но под API xsystem35 (SDL push event, JNI-класс `io.github.rufim.alice.NativeBridge`). Каркас:

```c
/* Мост xsystem35 <-> Android: ADV-текст для TTS, синтетический ввод, читы. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <SDL.h>
#include "android_bridge.h"
#include "variable.h"

static bool tts_enabled = false;
void bridge_set_tts_enabled(bool on) { tts_enabled = on; }

// Синтетический Enter в очередь SDL — игра листает диалог.
void bridge_advance_message(void) {
	SDL_Event ev;
	memset(&ev, 0, sizeof(ev));
	ev.type = SDL_KEYDOWN;
	ev.key.state = SDL_PRESSED;
	ev.key.keysym.sym = SDLK_RETURN;
	ev.key.keysym.scancode = SDL_SCANCODE_RETURN;
	SDL_PushEvent(&ev);
	ev.type = SDL_KEYUP;
	ev.key.state = SDL_RELEASED;
	SDL_PushEvent(&ev);
}

static void bridge_emit(const char *utf8);
static void bridge_emit_page(void);

void bridge_adv_message(const char *utf8) { if (utf8 && *utf8) bridge_emit(utf8); }
void bridge_adv_newline(void) { /* текст уже отдан в message */ }
void bridge_adv_page_break(void) { bridge_emit_page(); }
void bridge_adv_keywait(void) { /* точка ожидания клавиши; резерв для авто-листания */ }
```

Читы (`bridge_cheat_*`) добавляются в Task 1.3 — на этом шаге объявить их как заглушки, возвращающие 0/false, чтобы файл собирался; заменить в 1.3.

Ниже — платформенные ветки:

```c
#ifndef __ANDROID__
static void bridge_emit(const char *utf8) {
	if (getenv("XS35_BRIDGE_DEBUG")) { printf("[ADV] |%s|\n", utf8); fflush(stdout); }
}
static void bridge_emit_page(void) {
	if (getenv("XS35_BRIDGE_DEBUG")) { printf("[ADV] --- page ---\n"); fflush(stdout); }
}
#else
#include <jni.h>
#include <android/log.h>
static JavaVM *jvm = NULL;
static jclass bridge_class = NULL;
static jmethodID mid_on_adv_text = NULL;
static jmethodID mid_on_adv_page = NULL;

JNIEXPORT void JNICALL
Java_io_github_rufim_alice_NativeBridge_nativeInit(JNIEnv *env, jobject self) {
	(*env)->GetJavaVM(env, &jvm);
	jclass cls = (*env)->GetObjectClass(env, self);
	bridge_class = (*env)->NewGlobalRef(env, cls);
	mid_on_adv_text = (*env)->GetStaticMethodID(env, bridge_class, "onAdvText",
	                                            "(Ljava/lang/String;Z)V");
	if (!mid_on_adv_text) (*env)->ExceptionClear(env);
	mid_on_adv_page = (*env)->GetStaticMethodID(env, bridge_class, "onAdvPage", "()V");
	if (!mid_on_adv_page) (*env)->ExceptionClear(env);
}

JNIEXPORT void JNICALL
Java_io_github_rufim_alice_NativeBridge_nativeSetTts(JNIEnv *env, jobject self, jboolean on) {
	(void)env; (void)self; tts_enabled = on;
}
JNIEXPORT void JNICALL
Java_io_github_rufim_alice_NativeBridge_nativeAdvance(JNIEnv *env, jobject self) {
	(void)env; (void)self; bridge_advance_message();
}

static JNIEnv *bridge_env(void) {
	if (!jvm) return NULL;
	JNIEnv *env;
	if ((*jvm)->GetEnv(jvm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) {
		if ((*jvm)->AttachCurrentThread(jvm, &env, NULL) != JNI_OK) return NULL;
	}
	return env;
}
static void bridge_emit(const char *utf8) {
	if (!tts_enabled || !mid_on_adv_text) return;
	JNIEnv *env = bridge_env(); if (!env) return;
	jstring s = (*env)->NewStringUTF(env, utf8);
	if (!s) { (*env)->ExceptionClear(env); return; }
	(*env)->CallStaticVoidMethod(env, bridge_class, mid_on_adv_text, s, (jboolean)0);
	if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
	(*env)->DeleteLocalRef(env, s);
}
static void bridge_emit_page(void) {
	if (!tts_enabled || !mid_on_adv_page) return;
	JNIEnv *env = bridge_env(); if (!env) return;
	(*env)->CallStaticVoidMethod(env, bridge_class, mid_on_adv_page);
	if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}
#endif
```

- [ ] **Step 2: Включить android_bridge.c в сборку движка**

В `xsystem35/src/CMakeLists.txt` добавить `android_bridge.c` в список исходников цели `xsystem35` (найти `target_sources`/`add_library` и дописать `android_bridge.c`). На не-Android платформах файл тоже компилируется (ветка-заглушка), поэтому добавлять безусловно.

- [ ] **Step 3: Включить Android-режим text-hook при старте на Android**

В точке инициализации движка на Android (там, где разбираются профили/аргументы — `xsystem35.c`) выставить режим: при сборке `__ANDROID__` вызвать `texthook_set_mode(TEXTHOOK_ANDROID)` по умолчанию. Найти `set_texthook_mode`/`texthook_set_mode(TEXTHOOK_NONE)` в `xsystem35.c` и обернуть:

```c
#ifdef __ANDROID__
	texthook_set_mode(TEXTHOOK_ANDROID);
#endif
```
(разместить после парсинга профиля, чтобы не перетёрлось значением по умолчанию `NONE`).

- [ ] **Step 4: Собрать движок (обе ABI)**

Run:
```bash
cd /home/rufim/src/xsystem4-android
export ANDROID_NDK_HOME=/home/rufim/Android/Sdk/ndk/30.0.14904198
export ABI_NAMES="arm64-v8a x86_64"
./build-shared-libs.sh
```
Expected: сборка проходит; `libxsystem35.so` пересобран.

- [ ] **Step 5: Проверить экспорт JNI-символов**

Run:
```bash
llvm-nm -D project/app/src/main/jniLibs/arm64-v8a/libxsystem35.so 2>/dev/null | grep NativeBridge || \
  ${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm -D project/app/src/main/jniLibs/arm64-v8a/libxsystem35.so | grep NativeBridge
```
Expected: видны `Java_io_github_rufim_alice_NativeBridge_nativeInit`, `...nativeSetTts`, `...nativeAdvance`.

- [ ] **Step 6: Commit**

```bash
git -C xsystem35 add src/android_bridge.c src/CMakeLists.txt src/xsystem35.c
git -C xsystem35 commit -m "tts: мост android_bridge (эмиттер ADV + JNI)"
git -C /home/rufim/src/xsystem4-android add xsystem35 project/app/src/main/jniLibs
git -C /home/rufim/src/xsystem4-android commit -m "build: обновить сабмодуль xsystem35 (мост TTS)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 1.3: API читов поверх variable.c (16-битные переменные)

**Files:**
- Modify: `xsystem35/src/variable.c`, `xsystem35/src/variable.h`
- Modify: `xsystem35/src/android_bridge.c`

**Interfaces:**
- Consumes: `v_name(int)`, `v_ref(int, &ref)` (возвращает `vmvar_t*` = `uint16_t*`), `varPage[]`.
- Produces: `bridge_cheat_list/scan/write/free`; JNI `nativeCheatList/Scan/Write`. Формат строки переменной: `"<page>\t<varno>\t<name>\t<value>"`.

- [ ] **Step 1: Экспортировать число именованных переменных**

В `variable.c` найти статик, куда `v_set_names` пишет `count`, и добавить геттер. В `variable.h` объявить `int v_name_count(void);`. В `variable.c`:

```c
static int var_name_count = 0;   // если поля ещё нет — добавить и присвоить в v_set_names
void v_set_names(int count, char **names) {
	var_name_count = count;
	/* ...существующее тело... */
}
int v_name_count(void) { return var_name_count; }
```
(если счётчик уже хранится под другим именем — вернуть его, не заводя дубль.)

- [ ] **Step 2: Реализовать читы в android_bridge.c**

Заменить заглушки читов на реализацию (16-битные значения, сканирование скалярных глобалов + страниц `varPage`):

```c
#include "variable.h"

int bridge_cheat_read16(int page, int varno, int *ok) {
	*ok = 0;
	if (page == 0) {
		if (varno < 0 || varno >= v_name_count()) return 0;
		struct VarRef ref;
		vmvar_t *p = v_ref(varno, &ref);
		if (!p) return 0;
		*ok = 1; return (int)*p;
	}
	// страницы массивов
	extern struct VarPage varPage[];
	if (page < 0) return 0;
	struct VarPage *vp = &varPage[page];
	if (!vp->value || varno < 0 || varno >= vp->size) return 0;
	*ok = 1; return (int)vp->value[varno];
}

bool bridge_cheat_write(int page, int varno, int value) {
	int ok; bridge_cheat_read16(page, varno, &ok);
	if (!ok) return false;
	if (value < 0) value = 0; if (value > 65535) value = 65535;
	if (page == 0) { struct VarRef ref; *v_ref(varno, &ref) = (vmvar_t)value; }
	else { extern struct VarPage varPage[]; varPage[page].value[varno] = (vmvar_t)value; }
	return true;
}

int bridge_cheat_list(const char *filter_utf8, struct bridge_var **out, int max) {
	*out = NULL;
	int total = v_name_count();
	struct bridge_var *arr = calloc(max, sizeof(*arr));
	int n = 0;
	for (int i = 0; i < total && n < max; i++) {
		const char *nm = v_name(i);
		char *name = nm ? toUTF8(nm) : strdup("?");
		if (filter_utf8 && *filter_utf8 && !strstr(name, filter_utf8)) { free(name); continue; }
		int ok; int v = bridge_cheat_read16(0, i, &ok);
		if (!ok) { free(name); continue; }
		arr[n].page = 0; arr[n].varno = i; arr[n].name_utf8 = name; arr[n].value = v;
		n++;
	}
	*out = arr; return n;
}

#define SCAN_MAX 100000
#define SCAN_MAX_PAGE 4096
static struct { int page; int varno; } *scan_cands = NULL;
static int scan_n = 0;

int bridge_cheat_scan(int value, bool narrow, struct bridge_var **out, int max) {
	*out = NULL;
	if (!scan_cands) scan_cands = calloc(SCAN_MAX, sizeof(*scan_cands));
	extern struct VarPage varPage[];
	if (!narrow) {
		scan_n = 0;
		for (int i = 0; i < v_name_count() && scan_n < SCAN_MAX; i++) {
			int ok; if (bridge_cheat_read16(0, i, &ok) == value && ok) {
				scan_cands[scan_n].page = 0; scan_cands[scan_n].varno = i; scan_n++;
			}
		}
		for (int pg = 1; pg < SCAN_MAX_PAGE && scan_n < SCAN_MAX; pg++) {
			struct VarPage *vp = &varPage[pg];
			if (!vp->value || vp->size <= 0) continue;
			for (int i = 0; i < vp->size && scan_n < SCAN_MAX; i++) {
				if ((int)vp->value[i] == value) {
					scan_cands[scan_n].page = pg; scan_cands[scan_n].varno = i; scan_n++;
				}
			}
		}
	} else {
		int kept = 0;
		for (int i = 0; i < scan_n; i++) {
			int ok; int v = bridge_cheat_read16(scan_cands[i].page, scan_cands[i].varno, &ok);
			if (ok && v == value) scan_cands[kept++] = scan_cands[i];
		}
		scan_n = kept;
	}
	int n = scan_n < max ? scan_n : max;
	struct bridge_var *arr = calloc(n > 0 ? n : 1, sizeof(*arr));
	for (int i = 0; i < n; i++) {
		int ok;
		arr[i].page = scan_cands[i].page; arr[i].varno = scan_cands[i].varno;
		arr[i].value = bridge_cheat_read16(arr[i].page, arr[i].varno, &ok);
		if (arr[i].page == 0) {
			const char *nm = v_name(arr[i].varno);
			arr[i].name_utf8 = nm ? toUTF8(nm) : strdup("?");
		} else {
			char buf[48]; snprintf(buf, sizeof(buf), "[стр %d] #%d", arr[i].page, arr[i].varno);
			arr[i].name_utf8 = strdup(buf);
		}
	}
	*out = arr; return scan_n;
}

void bridge_cheat_free(struct bridge_var *arr, int n) {
	if (!arr) return;
	for (int i = 0; i < n; i++) free(arr[i].name_utf8);
	free(arr);
}
```
(`toUTF8` объявлена в `scenario.h`/`utfsjis.h` — подключить нужный заголовок; проверить точное имя при сборке.)

- [ ] **Step 3: JNI-нативы читов (Android-ветка android_bridge.c)**

Добавить в `#ifdef __ANDROID__`-ветку `nativeCheatList/Scan/Write` по образцу xsystem4 (формат строки `"%d\t%d\t%s\t%d"`, заголовок скана `"TOTAL:%d"`), с именами `Java_io_github_rufim_alice_NativeBridge_nativeCheat*`. Код идентичен xsystem4-версии, кроме имени пакета и поля `page` вместо `page_slot`.

- [ ] **Step 4: Собрать и проверить символы**

Run:
```bash
cd /home/rufim/src/xsystem4-android && export ANDROID_NDK_HOME=/home/rufim/Android/Sdk/ndk/30.0.14904198 && export ABI_NAMES="arm64-v8a x86_64" && ./build-shared-libs.sh
${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm -D project/app/src/main/jniLibs/arm64-v8a/libxsystem35.so | grep nativeCheat
```
Expected: видны `nativeCheatList`, `nativeCheatScan`, `nativeCheatWrite`.

- [ ] **Step 5: Commit**

```bash
git -C xsystem35 add src/variable.c src/variable.h src/android_bridge.c
git -C xsystem35 commit -m "cheats: API переменных VM (16-бит) + JNI"
git -C /home/rufim/src/xsystem4-android add xsystem35 project/app/src/main/jniLibs
git -C /home/rufim/src/xsystem4-android commit -m "build: обновить сабмодуль xsystem35 (читы)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Фаза 2 — Kotlin: общий код, второй активити, единый лаунчер

### Task 2.1: Перенести пакет в io.github.rufim.alice

**Files:**
- Modify: все `.kt` в `project/app/src/main/java/io/github/kichikuou/xsystem4/` → новый пакет/каталог.
- Modify: `project/app/build.gradle`, `AndroidManifest.xml`.

**Interfaces:**
- Produces: единый пакет `io.github.rufim.alice`; `applicationId io.github.rufim.alice`.

- [ ] **Step 1: Переместить каталог и сменить пакет**

```bash
cd /home/rufim/src/xsystem4-android/project/app/src/main/java/io/github
git mv kichikuou/xsystem4 rufim 2>/dev/null || { mkdir -p rufim; git mv kichikuou/xsystem4/* rufim/; }
mv rufim/xsystem4/* rufim/ 2>/dev/null || true
cd /home/rufim/src/xsystem4-android
grep -rl 'io.github.kichikuou.xsystem4' project/app/src/main | xargs sed -i 's/io\.github\.kichikuou\.xsystem4/io.github.rufim.alice/g'
```
(SDL-классы в `org/libsdl/app` не трогать.)

- [ ] **Step 2: Обновить build.gradle**

В `project/app/build.gradle` заменить `namespace "io.github.kichikuou.xsystem4"` на `namespace "io.github.rufim.alice"` и добавить в `defaultConfig`:
```groovy
applicationId "io.github.rufim.alice"
```
Добавить зависимость (нужна XSystem35Activity, MIDI через MediaPlayer — уже в SDK; корутины уже есть). Ничего нового не требуется, но убедиться, что строка `implementation 'com.google.mlkit:translate:17.0.3'` на месте.

- [ ] **Step 3: Обновить AndroidManifest.xml — пакет активити**

Заменить префикс `.` -активити останется рабочим (namespace сменился). Проверить, что `<activity android:name=".LauncherActivity">` и `.XSystem4Activity` резолвятся в новый пакет.

- [ ] **Step 4: Собрать APK (только Kotlin-часть, нативка уже собрана)**

Run:
```bash
cd /home/rufim/src/xsystem4-android/project && ./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
cd /home/rufim/src/xsystem4-android
git add -A project/app/src/main project/app/build.gradle
git commit -m "refactor: пакет io.github.rufim.alice, applicationId единого приложения

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2.2: Определение движка по папке игры (TDD)

**Files:**
- Create: `project/app/src/main/java/io/github/rufim/alice/Engine.kt`
- Create: `project/app/src/test/java/io/github/rufim/alice/EngineDetectTest.kt`

**Interfaces:**
- Produces: `enum class Engine { XSYSTEM4, XSYSTEM35 }`; `fun detectEngine(dir: File): Engine?` — `XSYSTEM35` если есть `System39.ain`; `XSYSTEM4` если есть `System40.ini` или `AliceStart.ini`; иначе `null`.

- [ ] **Step 1: Написать падающий тест**

`EngineDetectTest.kt`:
```kotlin
package io.github.rufim.alice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class EngineDetectTest {
    private fun dirWith(vararg names: String): File {
        val d = Files.createTempDirectory("game").toFile()
        for (n in names) File(d, n).writeText("x")
        return d
    }
    @Test fun detectsSystem39() =
        assertEquals(Engine.XSYSTEM35, detectEngine(dirWith("System39.ain")))
    @Test fun detectsSystem40Ini() =
        assertEquals(Engine.XSYSTEM4, detectEngine(dirWith("System40.ini")))
    @Test fun detectsAliceStart() =
        assertEquals(Engine.XSYSTEM4, detectEngine(dirWith("AliceStart.ini")))
    @Test fun system39WinsOverIni() =
        assertEquals(Engine.XSYSTEM35, detectEngine(dirWith("System39.ain", "AliceStart.ini")))
    @Test fun unknownIsNull() = assertNull(detectEngine(dirWith("readme.txt")))
}
```

- [ ] **Step 2: Запустить тест — убедиться, что падает**

Run: `cd project && ./gradlew testDebugUnitTest --tests '*EngineDetectTest'`
Expected: FAIL (`detectEngine`/`Engine` не определены).

- [ ] **Step 3: Реализовать Engine.kt**

```kotlin
package io.github.rufim.alice
import java.io.File

enum class Engine { XSYSTEM4, XSYSTEM35 }

fun detectEngine(dir: File): Engine? = when {
    File(dir, "System39.ain").exists() -> Engine.XSYSTEM35
    File(dir, "System40.ini").exists() || File(dir, "AliceStart.ini").exists() -> Engine.XSYSTEM4
    else -> null
}
```

- [ ] **Step 4: Запустить тест — зелёный**

Run: `cd project && ./gradlew testDebugUnitTest --tests '*EngineDetectTest'`
Expected: PASS (5 тестов).

- [ ] **Step 5: Commit**

```bash
git add project/app/src/main/java/io/github/rufim/alice/Engine.kt project/app/src/test/java/io/github/rufim/alice/EngineDetectTest.kt
git commit -m "feat: определение движка по маркер-файлам игры

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2.3: Общий базовый класс EngineActivity

**Files:**
- Create: `project/app/src/main/java/io/github/rufim/alice/EngineActivity.kt`
- Modify: `project/app/src/main/java/io/github/rufim/alice/XSystem4Activity.kt`

**Interfaces:**
- Consumes: `NativeBridge`, `TtsSpeaker`, `CheatPanel`, `EdgePanel` (существующие).
- Produces: `abstract class EngineActivity : SDLActivity()` со всей обвязкой панели TTS/читов и колбэков моста; `XSystem4Activity : EngineActivity` (только engine-специфика).

- [ ] **Step 1: Выделить общую часть из XSystem4Activity в EngineActivity**

Создать `EngineActivity.kt`, перенести туда из `XSystem4Activity` всё движко-независимое: инициализацию `NativeBridge.init(...)`, `TtsSpeaker`, `setupBridgeUi`, `EdgePanel`, диалоги TTS/читов (`showTtsDialog`, `showCheatsDialog`, `makeSwitch`, `synthesizeTap`, `showFullscreenDialog`, `ImageButton` play/stop), константы `EXTRA_GAME_ROOT`/`EXTRA_SAVE_DIR`. Абстрактными оставить лишь то, что различается:

```kotlin
abstract class EngineActivity : SDLActivity() {
    companion object {
        const val EXTRA_GAME_ROOT = "GAME_ROOT"
        const val EXTRA_SAVE_DIR = "SAVE_DIR"
    }
    // ...весь общий код панели/TTS/читов (перенесён из XSystem4Activity)...
}
```

- [ ] **Step 2: Свести XSystem4Activity к наследнику**

`XSystem4Activity.kt` оставляет только `getLibraries()`/`getArguments()` под System 4 и наследует `EngineActivity`:

```kotlin
class XSystem4Activity : EngineActivity() {
    override fun getLibraries(): Array<String> = arrayOf("SDL2", "xsystem4")
    override fun getArguments(): Array<String> = arrayOf(
        intent.getStringExtra(EXTRA_GAME_ROOT)!!,
        "-save-folder", intent.getStringExtra(EXTRA_SAVE_DIR)!!)
    // engine-специфичные аргументы — как в текущей версии
}
```
(Точную форму `getArguments()` взять из текущего `XSystem4Activity` без изменений.)

- [ ] **Step 3: Сборка**

Run: `cd project && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add project/app/src/main/java/io/github/rufim/alice/EngineActivity.kt project/app/src/main/java/io/github/rufim/alice/XSystem4Activity.kt
git commit -m "refactor: общий EngineActivity, XSystem4Activity как наследник

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2.4: XSystem35Activity + манифест (два процесса)

**Files:**
- Create: `project/app/src/main/java/io/github/rufim/alice/XSystem35Activity.kt`
- Modify: `project/app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `EngineActivity`; MIDI/input-диалоги из `GameActivity.kt` xsystem35.
- Produces: активити System 3.x в процессе `:engine35`; XSystem4Activity — в `:engine4`.

- [ ] **Step 1: Написать XSystem35Activity**

Взять `GameActivity.kt` из `xsystem35/android/.../GameActivity.kt`, сменить пакет на `io.github.rufim.alice`, сделать наследником `EngineActivity`, оставить его специфику (`getLibraries()=arrayOf("SDL2","xsystem35")`, `getArguments()` с `-gamedir`/`-savedir`, `MidiPlayer`, `inputString`/`inputNumber`, `setTitle`). Общую панель/TTS/читы НЕ дублировать — она в базовом классе.

- [ ] **Step 2: Прописать процессы и вторую активити в манифесте**

В `AndroidManifest.xml`:
- к `<activity android:name=".XSystem4Activity" ...>` добавить `android:process=":engine4"`.
- добавить копию блока для `.XSystem35Activity` с `android:process=":engine35"` (те же `configChanges`, `theme`, `exported=false`).

- [ ] **Step 3: Сборка**

Run: `cd project && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add project/app/src/main/java/io/github/rufim/alice/XSystem35Activity.kt project/app/src/main/AndroidManifest.xml
git commit -m "feat: XSystem35Activity + изоляция движков по процессам

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2.5: Единый лаунчер с диспетчеризацией по движку

**Files:**
- Modify: `project/app/src/main/java/io/github/rufim/alice/GameList.kt`
- Modify: `project/app/src/main/java/io/github/rufim/alice/LauncherActivity.kt`

**Interfaces:**
- Consumes: `detectEngine(dir)`, `XSystem4Activity`, `XSystem35Activity`.
- Produces: `Item.engine: Engine`; клик по игре открывает активити нужного движка.

- [ ] **Step 1: Добавить engine в Item и распознавание в GameList**

В `Item` добавить поле `val engine: Engine`. В `Item.fromDirectory` сначала вызвать `detectEngine(dir)`:
```kotlin
val engine = detectEngine(dir) ?: return Item(dir.name, dir, homedir, null, null,
    context.getString(R.string.toast_no_ini, dir.path)).let { /* engine? */ }
```
Для System 3.x путь сейва определяется иначе (нет `System40.ini`). Минимально: для `XSYSTEM35` использовать сам каталог игры как savedir (System 3.x пишет сейвы рядом), имя игры — из названия папки (уточнить при интеграции). Хранить `engine` в `Item`.

- [ ] **Step 2: Диспетчеризация в LauncherActivity.onListItemClick**

```kotlin
val cls = when (item.engine) {
    Engine.XSYSTEM4 -> XSystem4Activity::class.java
    Engine.XSYSTEM35 -> XSystem35Activity::class.java
}
val i = Intent().setClass(applicationContext, cls)
i.putExtra(EngineActivity.EXTRA_GAME_ROOT, item.path.path)
i.putExtra(EngineActivity.EXTRA_SAVE_DIR, item.savedir!!.path)
startActivity(i)
```

- [ ] **Step 3: Сборка**

Run: `cd project && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add project/app/src/main/java/io/github/rufim/alice/GameList.kt project/app/src/main/java/io/github/rufim/alice/LauncherActivity.kt
git commit -m "feat: единый лаунчер, запуск нужного движка по игре

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Фаза 3 — Интеграция на устройстве и настройка TTS

### Task 3.1: Установка и запуск обеих игр

**Files:** — (проверочная задача, кода нет)

**Interfaces:**
- Consumes: собранный APK, обе `.so` в jniLibs.

- [ ] **Step 1: Собрать полный APK**

```bash
cd /home/rufim/src/xsystem4-android/project && ./gradlew assembleDebug
```
Expected: `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 2: Установить на телефон**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Expected: `Success`.

- [ ] **Step 3: Забросить Daiakuji на устройство**

Скопировать содержимое `/mnt/sda1/torrents/Daiakuji/` в подпапку `Daiakuji` внутри `Android/data/io.github.rufim.alice/files/` на телефоне (MTP или `adb push`). Убедиться, что рядом лежит `System39.ain`.

- [ ] **Step 4: Проверить лаунчер и запуск**

Открыть приложение. Ожидаемо: в списке две игры (Daiteikoku → xsystem4, Daiakuji → xsystem35). Запустить Daiakuji — движок стартует в процессе `:engine35`, показывается вступление игры.
Run для диагностики: `adb logcat -s xs35bridge SDL DEBUG AndroidRuntime`
Expected: нет фатальных ошибок загрузки `.so`; игра рисует кадры.

- [ ] **Step 5: Проверить, что Daiteikoku по-прежнему работает**

Запустить Daiteikoku (xsystem4, процесс `:engine4`) — регресс-проверка. Ожидаемо: работает как раньше, TTS/читы доступны.

---

### Task 3.2: Проверка и настройка TTS/читов на Daiakuji

**Files:** — (настроечная; правки при необходимости в `TtsSpeaker.kt`, `android_bridge.c`)

- [ ] **Step 1: Включить TTS в Daiakuji**

В игре открыть боковую панель → включить TTS. Ожидаемо: ADV-текст Daiakuji читается вслух (яп. сегменты — яп. голосом, рус. перевод — рус. голосом).
Диагностика: `adb logcat -s xs35bridge` — видны строки эмиттера `onAdvText`.

- [ ] **Step 2: Проверить имя говорящего**

Наблюдать, приходит ли имя говорящего в тексте System 3.9 маркером (в Daiteikoku — `【…】`). Если формат другой — донастроить `TtsSegmenter.speakerOf` под фактический формат Daiakuji (правка в `TtsSpeaker.kt`, добавить тест-кейс в `TtsSegmenterTest.kt`). Если имена не выделяются — оставить чтение всего текста как есть (не блокирующее).

- [ ] **Step 3: Проверить авто-листание**

Включить в игре авто-режим + авто-листание TTS. Ожидаемо: после дочитывания реплики движок листает диалог. Если проскакивает модалка/меню — при необходимости использовать сигнал `bridge_adv_keywait` как барьер (аналог модалко-защиты из xsystem4). Правку логики вести в `TtsSpeaker.pumpAdvance` / `android_bridge.c`.

- [ ] **Step 4: Проверить читы**

Открыть панель читов в Daiakuji. Ожидаемо: список именованных глобалов с значениями (0..65535). Скан по значению: найти деньги (ввести текущее значение → сузить после изменения) и записать новое. Проверить, что запись отражается в игре.
Диагностика: `adb logcat -s xs35bridge`.

- [ ] **Step 5: Зафиксировать результаты и правки**

Если на шагах 2–4 понадобились правки — закоммитить их (в `xsystem35` и/или `project`), обновить сабмодуль в корне. Сообщение коммита описывает конкретную настройку.

---

### Task 3.3: Документация и финальная сборка

**Files:**
- Modify: `README.md` (или `docs/`), `/mnt/sda1/DaiteikokuAndroid/PROGRESS.md`

- [ ] **Step 1: Обновить README/PROGRESS**

Описать: единое приложение, два движка, определение по маркер-файлам, изоляция процессов, как класть игры, статус TTS/читов на обоих движках.

- [ ] **Step 2: Финальная сборка release/debug APK**

```bash
cd /home/rufim/src/xsystem4-android && export ANDROID_NDK_HOME=/home/rufim/Android/Sdk/ndk/30.0.14904198 && export ABI_NAMES="arm64-v8a x86_64" && ./build-shared-libs.sh
cd project && ./gradlew assembleDebug
```
Expected: APK собран с обеими `.so`.

- [ ] **Step 3: Залить на GitHub (форки Rufim)**

```bash
git -C /home/rufim/src/xsystem4-android/xsystem35 push
git -C /home/rufim/src/xsystem4-android push rufim HEAD
```

- [ ] **Step 4: Commit финала**

```bash
git -C /home/rufim/src/xsystem4-android add -A
git -C /home/rufim/src/xsystem4-android commit -m "docs: единое приложение xsystem4+xsystem35 — статус и инструкция

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Self-Review — покрытие спеки

- **Единый APK, два движка** → Фаза 0 (Task 0.2), Фаза 2.
- **Изоляция процессов** → Task 2.4.
- **Определение движка (System39.ain / System40.ini / AliceStart.ini)** → Task 2.2 (с тестом).
- **Общий Kotlin в EngineActivity** → Task 2.3.
- **Единый NativeBridge, одинаковые JNI-символы** → Global Constraints + Task 1.2/1.3 (имена `io_github_rufim_alice`).
- **xsystem35 TTS (texthook Android-режим)** → Task 1.1.
- **xsystem35 читы (variable.c, 16-бит)** → Task 1.3.
- **xsystem35 мост/JNI** → Task 1.2/1.3.
- **Авто-листание + модалки** → Task 3.2 (Step 3).
- **Риск SDL2 (один libSDL2.so)** → Task 0.2 (Step 1–2, 6).
- **applicationId новый** → Global Constraints + Task 2.1.
- **Полный паритет фич** → Фаза 1 + Task 3.2.

Открытые вопросы спеки (версия SDL — зафиксирована 2.32.10; формат имени говорящего — Task 3.2 Step 2; модалко-защита — Task 3.2 Step 3) закрыты в плане как настроечные шаги на реальной игре.
```
