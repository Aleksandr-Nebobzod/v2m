# 260921_android_plan.md — план Android-приложения v2m

Документ создан Дисом 2026-09-21 по запросу А.М. («и приступаем к планированию андроид-приложения»). Платформенные факты по звуку, MIDI и записи — в `260908_kpm_libs_considerations.md` (§1 таблица, §2 слои под замену, §3 вход из памяти); здесь они не повторяются.

Зонтичная задача — OQ-A00 «Портирование на Android» (`docs/backlog.md`): требования к ядру ведутся обычными OQ-NN, требования к реализации на Android — с префиксом A. Инвентаризация сделана по состоянию рабочей копии на 2026-09-21 (билд #58).

Решения А.М. (2026-09-21): UI-код общий (`commonMain`; далее та же основа — для iOS); объём первой версии — полный перенос интерфейса без урезаний; проверка — на телефоне, сборка и отладка — из Android Studio.

## 1. Что уже готово к переносу

| Что | Где | Состояние |
|---|---|---|
| JNI-мост к ядру | `app/shared/src/jvmShared/kotlin/com/v2m/app/V2mEngine.kt` (203 строки) | Платформенно-нейтрален: FloatArray/DoubleArray/ByteArray, без файлов и потоков; `java.nio` и `System.loadLibrary` — уровень `jvmShared` |
| Контракт захвата | `app/shared/src/commonMain/kotlin/com/v2m/app/AudioCapture.kt` (22) | Интерфейс + `Result(pcm, sr)`; реализации платформенные |
| C-API ядра | `basicpitch/src/libv2m/v2m.h` (143) | Стабильный C-ABI: `v2m_transcribe`, `v2m_process_audio`, `v2m_spectrogram`, `v2m_midi_to_musicxml`, `v2m_params_default` |
| JNI-реализация | `basicpitch/src/libv2m/v2m_jni.cpp` (509) | 9 функций `V2mEngine` переносимы как есть; блок `NativeCapture`/ALSA (403-509) и `capture_verbose` (32-62) — desktop-only |
| Ядро | `basicpitch/src/basicpitch.cpp/src/*.cpp` (10 файлов) | Eigen (вендоренный), ONNX Runtime, libremidi (только writer) |

Source set'ы введены на этапе 1б: `commonMain` (платформенно-нейтральный код) и промежуточный `jvmShared` (общий для desktop-JVM и Android: `java.nio`, `System.loadLibrary`); иерархия `commonMain ← jvmShared ← jvmMain/androidMain`. `expect`/`actual` пока не понадобились.

**Разложение логики (этап 3).** `commonMain` — чистый Kotlin: `Key.kt`, `Midi.kt` (`MidiNote`, `SongData`), `Strings.kt` (+ `ParamHelp`), `Gamma.kt`, `Build.kt`, `AudioCapture.kt`. `jvmShared` — всё, что опирается на `java.io`/`java.nio` (есть на Android с API 26, `minSdk` = 26): `V2mEngine.kt`, `Wav.kt`, `MidiMeta.kt`, `AbcExport.kt`, `NoteEdit.kt`, `Instruments.kt`, `Presets.kt`, `Preferences.kt`, `FramesSummary.kt`, `Log.kt`, `AppData.kt`. Урок этапа: `internal` в Kotlin ограничен модулем Gradle — при выносе в библиотеку объявления, читаемые потребителем, становятся `public`.

## 2. Что требует замены (desktop → Android)

| Механизм | Где сейчас | Замена на Android |
|---|---|---|
| `java.awt.FileDialog` — выбор WAV | `App.kt:449` | SAF: `ACTION_OPEN_DOCUMENT` (`ActivityResultContracts.OpenDocument`) |
| `javax.swing.JFileChooser` — сохранение записи и экспорт | `App.kt:376, 384, 844, 855` | SAF: `ACTION_CREATE_DOCUMENT` |
| `javax.swing.JOptionPane` — диалоги «Сохранить?», «Заменить?» | `App.kt:390-397, 421-423` | `AlertDialog` (Compose) |
| `java.awt.Desktop.browse` — ссылки | `App.kt:1874-1888` | `Intent(ACTION_VIEW)` |
| `javax.sound.midi` — плеер MIDI | `MidiPlayer.kt:5-10` | системный `MediaPlayer` (см. 260908 §1) |
| `javax.sound.sampled` — плеер WAV | `WavPlayer.kt:3-5` | `AudioTrack` |
| ALSA-захват через JNI | `v2m_capture.cpp`, `NativeCapture.kt:107-115` | `AudioRecord` → PCM в памяти (260908 §3) |
| `ProcessBuilder("midicsv"\|"musescore3")` | `Main.kt:500, 599, 608, 641, 695, 710, 731` | внешних программ нет — ветки отключить |
| `System.getProperty("user.home")`, `java.io.File` для пресетов и лога | `App.kt:262, 1901`; `Log.kt`; `Preferences.kt` | **сделано на этапе 3:** каталог данных — `AppData` (desktop `~/.v2m`, Android `filesDir`); сами prefs остаются файлом `prefs.properties` — устраивает и Android |

## 3. Ядро libv2m под Android

Факты по сборке (`basicpitch/src/libv2m/CMakeLists.txt`): ONNX Runtime прописан жёстким путём `/usr/lib/x86_64-linux-gnu/libonnxruntime.so` (строка 13), ALSA ищется через `find_library(asound REQUIRED)` (15), JNI-заголовки берутся из `$JAVA_HOME` (19-27). Опций под ANDROID/arm64 нет. Вендоренный ONNX Runtime 1.21.0 лежит полностью (`vendor/onnxruntime/`), модель вкомпилирована весами (`ort-model/model.ort.c`).

**Сделано на этапе 2 (2026-09-21, 17:35).** В `CMakeLists.txt` добавлено ветвление `if(ANDROID)`: путь к `libonnxruntime.so` — из параметра `-DV2M_ORT_LIB` (обязателен), `v2m_capture.cpp` исключён, `asound` и JDK-заголовки не ищутся. `v2m_jni.cpp`: блок `NativeCapture` и ALSA-`#include` под `#ifndef __ANDROID__`. `libv2m.cpp`: алиас `resampler_ns = RESAMPLER_OUTER_NAMESPACE::resampler` (под NDK пространство имён — `oboe`). В `v2m_jni.cpp` 13 целых полей `V2mParams` приведены к `jdouble` явно — clang не принимает неявное сужение в списке инициализации. Сборка идёт из `:shared` (`externalNativeBuild`, `ndkVersion 27.0.12077973`, `abiFilters arm64-v8a`, CMake 3.22.1); AAR подключается зависимостью и задачей `unpackOrt` для линковки.

**ONNX Runtime — три варианта:**

- **A. Готовый AAR** `com.microsoft.onnxruntime:onnxruntime-android` — **проверено 2026-09-21** (скачан 1.21.0 с Maven Central, 29 МБ): внутри 10 заголовков, включая `headers/onnxruntime_cxx_api.h` (ровно тот, что включает `ort_inference.cpp:3`), и `jni/<abi>/libonnxruntime.so` для arm64-v8a, armeabi-v7a, x86, x86_64; SONAME — `libonnxruntime.so`, зависимости — только системные (libdl, liblog, libm, libc). Prefab-модуля нет, но он и не нужен: наш `libv2m.so` собирается с `-I headers/`, линкуется с `libonnxruntime.so` (arm64-v8a), и оба файла кладутся в APK. Версия 1.21.0 совпадает с вендоренной — поведение модели то же, что на desktop.
- **B. Сборка из вендоренного исходника** — официальный путь `vendor/onnxruntime/build.sh --android` (есть `tools/ci_build/github/android/build_aar_package.py`). Версия ровно 1.21.0, но сборка долгая (!ai — порядок десятков минут, требует python-окружения и NDK).
- **C. `onnxruntime-mobile`** — меньше размер, урезанный набор операторов; пригодность под нашу модель не проверена.

**Прочие зависимости:**

- Eigen — переносится без изменений.
- oboe-resampler (`vendor/oboe-resampler/`) — чистый C++ (`<algorithm>`, `<math.h>`, `<sys/types.h>`), Oboe/AAudio не требует; `sys/types.h` в bionic есть.
- libremidi — header-only (`LIBREMIDI_HEADER_ONLY=1`), бэкенды: alsa, jack, pipewire, coremidi, winmm, emscripten, net, dummy; **Android-бэкенда нет**. Нам нужен только SMF-writer (`midi_notes.cpp:6-7`) — проверить, что writer не тянет бэкенд.
- libnyquist — только CLI, в libv2m не входит.

**POSIX-места в ядре:** `ort_inference.cpp:2, 4` (`fcntl.h`, `unistd.h`) и `:77-91` (глушение stderr ONNX через `dup2`/`/dev/null`); `strdup` в `libv2m.cpp` и `midi2musicxml.cpp`. В bionic всё это есть — поведение проверить на устройстве.

## 4. Этапы

| Этап | Содержание | Готово, когда | Состояние |
|---|---|---|---|
| 0. Окружение | Поставить NDK и cmake для Android; подобрать AGP под Gradle 8.13 | `sdkmanager` видит NDK; пустой проект собирается | выполнен: NDK 27.0.12077973, CMake 3.22.1, AGP 8.13.0 |
| 1. Каркас | `androidTarget` в `shared`, модуль `androidApp`, минимальный экран | APK ставится и запускается | выполнен (1а — 17:16, 1б — 17:20); запуск на устройстве — за А.М. |
| 2. Ядро | libv2m.so под arm64-v8a (ONNX Runtime по варианту A или B), упаковка в APK | `v2m_transcribe` возвращает MIDI на устройстве | собран (17:35): APK 22,5 МБ, в нём libv2m.so + libonnxruntime.so; проверка на телефоне — за А.М. |
| 3. Логика | Перенос `Wav.kt`, `AbcExport.kt`, `Midi.kt`, `MidiMeta.kt`, `NoteEdit.kt`, `Instruments.kt`, `Presets.kt`, `Preferences.kt` | Разбор WAV и экспорт совпадают с desktop-версией | выполнен (2026-09-22, 02:22): перенесены также `Key.kt`, `Strings.kt`, `Gamma.kt`, `FramesSummary.kt`, `Log.kt`, `Build.kt`; каталог данных — `AppData`; desktop `--self-test` EXIT=0, APK собран; принят на телефоне 2026-09-22 |
| 4. Разбор и UI | Перенос интерфейса целиком: экраны, все ручки, вкладки (Спектр/Кванты/Тоны/ABC); платформенное — диалоги и файлы через SAF | Приложение проходит путь «файл → транскрипт → экспорт» | 4а–4г выполнены (2026-09-22 — 2026-09-23); проверка на двух платформах — за А.М. |
| 5. Звук | `AudioRecord` (запись), `MediaPlayer` (MIDI), `AudioTrack` (WAV) | Запись с микрофона и прослушивание работают | реализации написаны 2026-09-22 (`AndroidAudio.kt`, `AndroidCapture.kt`); проверка на устройстве — за А.М. |
| 6. Проверка | На устройстве или эмуляторе | Приёмка А.М. | не начат |

## 4а. Этап 4 — разбиение и решения (составлено 2026-09-22)

Разведка перед переносом интерфейса: платформенные точки сосредоточены в пяти файлах. `App.kt` — `java.awt.FileDialog`, `javax.swing.JFileChooser/JOptionPane`, `java.awt.Desktop`, `ProcessBuilder`, `com.v2m.app.resources` (`Res.readBytes`), `System.getProperty("user.home")` (начальные каталоги диалогов и временный MIDI). `Main.kt` — `javax.sound.midi`, `ProcessBuilder` (midicsv/MuseScore), Skia (самотест). `MidiPlayer.kt`, `WavPlayer.kt`, `NativeCapture.kt` — `javax.sound.*` и ALSA. `SpectrogramView.kt` — Skia напрямую (`org.jetbrains.skia.Bitmap` в `spectrogramBitmap`, строка 70). Compose в `:shared` пока не подключён вовсе; `composeResources` (5 svg + 2 mid, 11 КБ) лежат в `:desktopApp`.

**Подэтапы:**

| Подэтап | Содержание | Готово, когда |
|---|---|---|
| 4а. Compose в `:shared` | Плагины `org.jetbrains.compose` и `kotlin.plugin.compose`, зависимости `compose.runtime/foundation/material/ui/components.resources` (`api`) в `commonMain`; `compose.resources` и каталог ресурсов переезжают в `:shared`; `Level.kt` → `commonMain` | **выполнен 2026-09-22:** сборки зелёные, `--self-test` EXIT=0; `Res` публичный (`publicResClass`) |
| 4б. Контракты платформы | Интерфейсы в `commonMain`, реализации по платформам (образец — `AudioCapture`): выбор WAV, сохранение файла, внешнее открытие (ссылка/файл), воспроизведение MIDI и WAV, пиксели → `ImageBitmap`. Диалоги «Сохранить?»/«Заменить?» — на Compose `AlertDialog` в общем коде (`JOptionPane` уходит) | **выполнен 2026-09-22:** контракты в `Platform.kt`, desktop-реализации — `DesktopPlatform.kt`; `App.kt` без awt/swing; самотест вскрыл и подтвердил дефект `wavDurationSec(File)` (исправлен); desktop `--self-test` EXIT=0, `:androidApp:assembleDebug` EXIT=0. В #59 (2026-09-23) починено падение desktop: модальные awt/Swing-диалоги показываются вне Compose-корутины (`SwingUtilities.invokeAndWait` из `withContext(Dispatchers.IO)`) |
| 4в. Экраны в `commonMain` | `App.kt` (2358), `NoteChart.kt` (307), `SpectrogramView.kt` (316), `Level.kt` — перенос; `spectrogramBitmap` переводится на общий `argbToImageBitmap` | Desktop GUI работает как прежде (проверка — запуск и путь «файл → транскрипт → экспорт») | **выполнен 2026-09-23** (ветка `refactoring/ui-commonmain`): `App.kt`, `NoteChart.kt`, `SpectrogramView.kt` в `commonMain`, `spectrogramBitmap` через `argbToImageBitmap`; перенесены также `V2mEngine.kt`, `Preferences.kt`, `Presets.kt`, `Wav.kt`, `MidiMeta.kt`, `NoteEdit.kt`, `Instruments.kt`, `AbcExport.kt`, `FramesSummary.kt`. Введены утилиты общего кода `ByteBuilder`, `Bytes`, `Fmt`, `SimpleProps`, `Time` (expect). В `jvmShared` остался только `PlatformJvm.kt`; в ворота добавлена `:shared:compileCommonMainKotlinMetadata`. Самотест сверен с базовой линией; GUI desktop — за А.М. |
| 4г. Android-экран | `MainActivity` → `ComponentActivity` + `setContent { App(...) }`, реализации контрактов на SAF и `Intent` | Приложение проходит путь «файл → транскрипт → экспорт» на телефоне | реализации контрактов готовы 2026-09-22 (`AndroidPlatform.kt`, площадка — `AndroidHost` в `MainActivity`); **`setContent { App() }` сделан 2026-09-23** (каркасные `TextView` этапов 1–3 и проба prefs удалены, строки `frame_*`/`core_*`/`logic_*`/`locale_info` убраны из ресурсов). Первый запуск на телефоне (билд #59) упал: иконки были SVG-ресурсами, Android их не умеет (`svgPainter`) — **билд #60**: иконки переведены в `Icons.kt` (`ImageVector` в общем коде, пиксельная сверка с исходными SVG в самотесте). Запуск на телефоне — за А.М. |

**Решения:**

1. **Пиксели → `ImageBitmap`.** В Compose Multiplatform 1.7.3 есть общая функция `ImageBitmap(width, height, …)` (`androidx.compose.ui.graphics.ImageBitmapKt`, проверено `javap` по `ui-graphics-desktop-1.7.3.jar`), но общего API записи пикселей нет. Вводится одно место — `argbToImageBitmap(w, h, argb: IntArray): ImageBitmap`: desktop — `org.jetbrains.skia.Bitmap` + `installPixels` + `asComposeImageBitmap()` (как сейчас), Android — `android.graphics.Bitmap.createBitmap(argb, w, h, ARGB_8888)` + `asImageBitmap()`. Это первые `expect`/`actual` в проекте — оправданы: реализация принципиально платформенная.
2. **Ресурсы.** `composeResources` переносятся в `shared/src/commonMain/composeResources`; `packageOfResClass = "com.v2m.app.resources"` сохраняется (импорты `Res.drawable.*` в коде не меняются).
3. **Диалоги.** `JOptionPane` не переносится: подтверждения («Сохранить?», «Заменить?») становятся Compose-`AlertDialog` в общем коде — это убирает и замеченную ранее немодальность Swing-диалогов без parent.
4. **Внешние программы.** `ProcessBuilder` (midicsv, musescore3) — только desktop-самотест; в Android-ветках не участвует.

## 5. Риски и неизвестности

- **Сборка ORT** — риска нет при варианте A (готовый AAR проверен); вариант B остаётся запасным, если понадобится иная версия или урезанная сборка.
- **NDK и CMake для Android** — риск снят 2026-09-21: А.М. установил в `/mnt/d/Android/Sdk` NDK 27.0.12077973 и CMake 3.22.1. Проверено по CMakeLists: `src/libv2m` требует 3.16, `vendor/libremidi` — 3.22 `FATAL_ERROR` (впритык), минимумы ниже 3.5 (`src_cli` 3.0, SYCL-модули Eigen 3.4.3) в сборку не входят, поэтому CMake 4.x тоже был бы пригоден.
- **Версия AGP** — по официальным release notes: AGP 8.11 и 8.13 требуют Gradle не ниже 8.13 (у проекта ровно 8.13), JDK 17 (есть 21), рекомендуемый NDK — 27.0.12077973. Установленные platform'ы: android-34, 36, 36.1, 37.0; по таблице «минимальный AGP на API» для API 36 требуется AGP ≥ 8.9.1, для API 36.1 — ≥ 8.13.0; для API 37 требование выше (!ai — уточнить при настройке). В проекте AGP ещё нет (есть Kotlin 2.1.0, Compose 1.7.3).
- **Глушение stderr ONNX** (`ort_inference.cpp:77-91`, `dup2` на `/dev/null`) — на Android поведение иное, чем на desktop; проверяется на устройстве.
- **libremidi** — Android-бэкенда нет; сборка с `LIBREMIDI_HEADER_ONLY=1` под NDK прошла (этап 2), SMF-writer бэкенд не тянет — риск снят.
- **Gradle JDK в Android Studio** — встроенная JBR 25.0.2 не годится: Kotlin 2.1.0 падает на разборе версии Java 25 (`java.lang.IllegalArgumentException: 25.0.2`), проверено 2026-09-21. В настройках AS нужно выбрать Gradle JDK 21. Альтернатива — переход на Kotlin, знающий Java 25 (!ai — не проверялось, потянет за собой версию Compose).

## 6. Решения А.М. (2026-09-21)

1. **Объём первой версии** — полный перенос интерфейса: ручки и вкладки целиком. Урезание набора не экономит: при общем UI-коде оно добавляет платформенные ветвления, а не убирает работу. Платформенно заменяются только механизмы (файлы, диалоги, звук) — они и составляют отдельные этапы.
2. **Проверка** — реальный телефон; сборка и отладка — из Android Studio (установлена, сконфигурирована на Android 17 / API 37). Целевая ABI — arm64-v8a.
3. **UI-код** — общая основа в `commonMain` (Compose Multiplatform); та же основа далее используется для iOS.
4. **Требования** — отдельное продуктовое требование не заводится: в Р1 п.3 (`docs/brd.md`) слова «перспектива переноса» заменены на «реализация». Работа ведётся зонтичной задачей OQ-A00 в `docs/backlog.md`.
