# История сообщений + полный перевод UI на Compose — план

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans.

**Goal:** Кнопка «История» (50 последних сообщений с именем говорившего) + весь UI приложения на Jetpack Compose + модульная пакетная структура.

**Architecture:** См. спеку `2026-07-18-history-compose-refactor-design.md`. Общий поток ADV-текста разруливает `AdvRouter` (разбор `【Имя】` в одном месте), подписчики — `TtsSpeaker` и `MessageHistory`. Compose поверх SDL — через `ComposeOverlay` (ComposeView + ручные lifecycle-owner'ы). Лаунчер — `ComponentActivity` + Compose.

**Tech Stack:** Kotlin 2.0.21, Compose BOM 2024.12+, activity-compose, StateFlow; движки без изменений кроме снятия TTS-гейта с emit.

## Global Constraints
- Ключи `SharedPreferences` (`bridge_prefs`) не меняются.
- JNI-имена не меняются; колбэк-сигнатуры `NativeBridge` не меняются.
- Функциональность экранов — 1:1 с текущей (включая ML Kit и фильтры System 3.x только при `supportsTextFilters`).
- Тёмная тема в текущей палитре: фон `rgb(24,27,38)`, панель `argb(235,30,34,48)`, акцент зелёный `rgb(76,200,120)`, вторичный текст `rgb(150,160,180)`.
- Тесты `EngineDetectTest`, `TtsSegmenterTest` остаются зелёными.
- Коммит после каждой задачи; сборка `assembleDebug` — критерий готовности задачи.

## Задачи

### A1. Стек Compose
`project/build.gradle`: kotlin_version → `2.0.21`, добавить classpath `org.jetbrains.kotlin.plugin.compose:2.0.21` (или plugins DSL). `app/build.gradle`: применить `org.jetbrains.kotlin.plugin.compose`, `buildFeatures { compose true }`, зависимости: BOM `androidx.compose:compose-bom:2024.12.01`, `androidx.compose.ui:ui`, `material3`, `foundation`, `androidx.activity:activity-compose:1.9.3`, `androidx.lifecycle:lifecycle-runtime-compose`. Сборка + тесты зелёные (UI ещё Views).

### A2. Пакет `ui`
- `ui/Theme.kt`: `AliceTheme` (darkColorScheme из палитры выше), типографика по умолчанию.
- `ui/ComposeOverlay.kt`: полноэкранный `Dialog` поверх любой Activity с `ComposeView`; вручную ставит `setViewTreeLifecycleOwner/SavedStateRegistryOwner/ViewModelStoreOwner` (собственные Registry), `show(content: @Composable (dismiss)->Unit)`, корректный destroy на dismiss.
- `ui/Components.kt`: `ScreenScaffold(title, onBack, content)` (шапка со стрелкой), `LabeledSwitch`, `LabeledSlider`, `DropdownField` — стилизованные под палитру.

### B1. Пакетная разбивка (без изменения логики)
`git mv` + правка package/imports: `bridge/NativeBridge.kt`; `tts/{TtsSpeaker,NameTranslator}.kt`; `cheats/CheatPanel.kt` (временно Views); `launcher/{LauncherActivity,GameList,Engine,PEResourceExtractor,ManualActivity,Licenses*}.kt`; `engine/{EngineActivity,XSystem4Activity,XSystem35Activity,EdgePanel}.kt`. Тесты переносятся соответственно. Сборка.

### C1. Emit без TTS-гейта (нативка)
- xsystem4 `android_bridge.c`: в `bridge_emit`/`bridge_emit_page` убрать условие `tts_enabled` (оставить проверки methodID).
- xsystem35 `android_bridge.c`: то же в `bridge_emit`/`bridge_emit_page`/`bridge_emit_window`.
- Пересобрать обе `.so` (arm64+x86_64 xsystem35; arm64 xsystem4 + вернуть SDL 2.32.10), проверить запуск.

### C2. AdvRouter + MessageHistory
- `bridge/AdvRouter.kt`: объект; вход `onText(raw)/onPage()`; разбор `speakerOf` (перенести из TtsSpeaker в `tts/TtsSegmenter` — он уже там) с `pendingSpeaker`; выход — слушатели `(speaker: String?, text: String)` и `onPage`. `TtsSpeaker.speak(speaker, text)` — принимает готового спикера (убрать внутренний pendingSpeaker; «читать имя при смене» остаётся в TtsSpeaker).
- `history/MessageHistory.kt`: объект, `MutableStateFlow<List<HistoryEntry>>`, `add(speaker,text)` с обрезкой до 50.
- `EngineActivity`: `NativeBridge.init` → AdvRouter; подписать TTS и историю.
- Обновить `TtsSegmenterTest` при переносе (тесты зелёные).

### C3. Экран «История» + кнопка
`history/HistoryScreen.kt`: `ScreenScaffold("История")`, `LazyColumn` по `MessageHistory.entries` (collectAsState), автопрокрутка к последнему (`LaunchedEffect(size){ listState.animateScrollToItem(last) }`), итем: спикер акцентом (если есть) + текст. Кнопка «История» в панель (между TTS и Читами). Показ через `ComposeOverlay`.

### D1. Панель на Compose
`engine/EnginePanel.kt`: ручка у правого края (полупрозрачная), по тапу — колонка кнопок + scrim (тап мимо закрывает). API: `addButton(title, onClick)`-эквивалент через список `PanelAction`. Заменяет `EdgePanel` (удалить). Кнопка стоп/плей TTS и оверлей «стр·окно» — остаются View? Нет: перенести в тот же Compose-оверлей панели (постоянный ComposeView поверх SDL: индикатор, стоп/плей, ручка).

### D2. Настройки TTS на Compose
`engine/TtsSettingsScreen.kt`: свитчи (читать текст/приглушать музыку/листать после чтения), подсказка про АВТО, слайдеры (музыка при чтении 0–100, скорость 50–200, тон 50–200), dropdown: движок озвучки, язык (все с голосами, названия на русском), голос для языка «(по умолчанию)+имена»; при `supportsTextFilters` — поля «Читать только окна», «Не читать страницы». Все текущие prefs-ключи и колбэки TtsSpeaker — как сейчас.

### D3. Читы на Compose
`cheats/CheatRepository.kt`: состояние (filter, scanValue, rows, total, translateNames), методы reload/scanNew/scanNarrow/write — поверх `NativeBridge.cheat*` + `NameTranslator`. `cheats/CheatsScreen.kt`: адаптивная раскладка (landscape: контролы слева, список справа с разделителем), поля с чёрным текстом на светлом фоне не нужны — тема тёмная, свои цвета; правка значения — Compose-диалог с числовым полем. Удалить `CheatPanel.kt`.

### D4. Чистка EngineActivity
Удалить: `showFullscreenDialog`, `makeSwitch`, `addSlider`, `dialogLabel`, View-поля. Оставить: SDL-жизненный цикл, `synthesizeTap/advanceGame`, связку AdvRouter/TTS/History/prefs, вызовы Compose-экранов. Цель — файл < ~200 строк.

### E1. Лаунчер на Compose
`launcher/LauncherActivity` → `ComponentActivity`; Compose: топ-бар «xsystems» + меню (Обновить, Установить из ZIP, Помощь, Лицензии), список игр (иконка из `getIconBitmap` как `ImageBitmap`, имя; серый — ошибка с диалогом), лонг-тап — диалог удаления, прогресс-диалог установки (существующий `GameList.install` + observer). SAF-интент как сейчас (`ACTION_GET_CONTENT` через `rememberLauncherForActivityResult`).

### E2. Лицензии на Compose
`LicensesMenuActivity/LicensesActivity` → один `LicensesActivity` (ComponentActivity): список файлов из assets/licenses → экран текста. `ManualActivity` (WebView) остаётся WebView внутри Compose (`AndroidView`) или как есть — на усмотрение при реализации (не UI-критично).

### F. Финал
Полная пересборка (нативка при C1 уже), `testDebugUnitTest`, установка на телефон, проверка: Daiteikoku (TTS, читы, история, настройки), Daiakuji (то же + фильтры/меню движка), лаунчер (список, удаление нет!, лицензии). Обновить `docs/UNIFIED_APP.md` (история, Compose). Коммиты по задачам + push всех репо.

## Порядок
A1 → A2 → B1 → C1 → C2 → C3 → D1 → D2 → D3 → D4 → E1 → E2 → F. Каждая задача заканчивается `assembleDebug` OK + commit.
