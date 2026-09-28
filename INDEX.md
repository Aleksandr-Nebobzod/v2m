# v2m — индекс проекта

Индекс рабочей области `v2m` (аудио → MIDI-транскрипция). Дата генерации: 2026-08-29.

## Назначение

Рабочая область для транскрипции аудио в MIDI при помощи C++-порта нейросети
Spotify Basic Pitch ([basicpitch.cpp](https://github.com/sevagh/basicpitch.cpp)).
Аудио-вход (`data/test.wav`) обрабатывается CLI-приложением `basicpitch` (ONNXRuntime + Eigen),
результат записывается в MIDI. Справочные MIDI-файлы (Muse — «New Born») служат
эталоном для сравнения; `data/mid.txt` — выгрузка MIDI-событий в формате midicsv для инспекции.

## Структура

```text
v2m/
├── CLAUDE.md                          # инструкции для Claude Code (этот проект)
├── INDEX.md                           # этот файл — индекс рабочей области
├── README.md                          # команды, принципы обработки, ручки CLI, примеры (подвал — лист регистрации изменений)
├── docs/                              # документация проекта
│   ├── history.md                     # ход работ по датам (журнал; отчёт по ритму)
│   ├── brd.md                         # требования Р1-Р3, Т01 (подвал — журнал изменений)
│   ├── open_questions.md              # ручки-кандидаты, вопросы OQ-NN (журнал)
│   ├── backlog.md                     # отложенные задачи OQ-NN (тембр, громкость, моно/поли, гармонизация, ритмизация)
│   └── ui.md                          # описание пользовательского интерфейса (справочник для агентов)
├── basicpitch/                        # код (перенесён из data/ 2026-08-29)
│   ├── src/
│   │   └── basicpitch.cpp/            # клон sevagh/basicpitch.cpp (git, с субмодулями)
│   │       ├── src/                   # общий код инференса и создания MIDI
│   │       │   ├── basicpitch.hpp     # заголовочный файл API + ModelParams (ручки)
│   │       │   ├── ort_inference.cpp  # инференс ONNXRuntime
│   │       │   ├── midi_notes.cpp     # конвертация нот в MIDI (libremidi)
│   │       │   ├── spectrogram.*      # спектрограмма входа: multi-resolution STFT (2026-09-12)
│   │       │   └── audio_effects.*    # аудиоэффекты до модели: срезы, gate, компрессор-экспандер (2026-09-12)
│   │       ├── src_cli/               # Linux CLI (CMakeLists.txt, basicpitch.cpp)
│   │       ├── src_wasm/              # функция WASM для веб-демо
│   │       ├── ort-model/             # модель: model.onnx, model.ort, model.with_runtime_opt.ort
│   │       │   └── model/             # сгенерированные веса: model.ort.c, model.ort.h
│   │       ├── scripts/               # сборка ORT-модели, python-инференс
│   │       ├── vendor/                # субмодули: basic-pitch v0.4.0, eigen, libnyquist,
│   │       │                          #   libremidi v4.5.0, onnxruntime, oboe-resampler
│   │       └── web/                   # веб-демо (app.js, basicpitch.js)
│   ├── src/midi2musicxml/             # наш конвертер MIDI → MusicXML (C++17, без зависимостей; 2026-08-30)
│   ├── src/libv2m/                    # C-библиотека: v2m.h, libv2m.cpp, v2m_jni.cpp (JNI), build/libv2m.so
│   ├── build/                         # сборочный каталог CMake (Unix Makefiles, Release)
│   │   └── basicpitch                 # собранный CLI-бинарник (~2.7 МБ, ELF x86-64)
│   ├── input/                         # входные файлы (пусто)
│   └── output/                        # выходные файлы (пусто)
├── libs/                              # клоны библиотек-кандидатов (эксперименты; в .gitignore)
│   ├── lomse/                         # lenmus/lomse — движок нотной записи (отложен, 2026-08-30)
│   └── verovio/                        # verovio — рендер нот в SVG (MusicXML/MEI; отложен, 2026-08-31)
├── app/                               # Kotlin-приложение (KMP: shared + desktopApp + androidApp, Compose; 2026-08-30; Android — 2026-09-21)
└── data/                              # музыка и данные экспериментов
    ├── *.wav                          # аудио-входы (test.wav, test1/3/4/5/6/7.wav, 260903_march.wav)
    ├── mid.txt                        # MIDI-события в формате midicsv (для инспекции)
    ├── *.mid                          # результаты и справочные MIDI (muse4, 0_test)
    ├── Muse — New Born *.mid (6 файлов)   # эталонные транскрипции (MIDIfind.com)
    ├── counIn.mid, tonica.mid         # счёт и трезвучие звуковых кнопок GUI (копии — в composeResources/files)
    ├── metronome.svg, music_note_2.svg # иконки звуковых кнопок GUI (копии — в desktopMain/drawable)
    ├── experience.db                  # база опыта транскрипций (копия aura-базы)
    ├── test7_report.md                # отчёт об анализе test7.wav: метрика, выводы, план (2026-09-04)
    ├── exp/                           # скрипты анализа и импорта (analyze_mid.py, score_vs_etalon.py, import_history.py)
    ├── input/, output/                # входы экспериментов, результаты прогонов
    └── test5.out                      # промежуточный вывод эксперимента
```

## Карта компонентов

```mermaid
flowchart LR
    WAV[data/test.wav] --> CLI[basicpitch CLI<br/>src_cli/basicpitch.cpp]
    CLI --> ORT[ort_inference.cpp<br/>ONNXRuntime + Eigen]
    ORT --> MODEL[ort-model/<br/>model.ort]
    ORT --> NOTES[midi_notes.cpp<br/>libremidi]
    NOTES --> MIDI[MIDI-файл]
    MIDI --> CSV[midicsv → mid.txt]
    LIBREMIDI[vendor/libremidi] -. субмодуль .-> NOTES
    MIDI --> M2X[midi2musicxml<br/>MIDI → MusicXML]
    M2X --> LOMSE[libs/lomse<br/>нотная запись MusicXML]
    CLI -. thin wrapper .-> V2M[libv2m<br/>C ABI]
    APP["app/desktopApp, app/androidApp<br/>Compose"] --> JNI[v2m_jni.cpp] --> V2M
    WAV -. PCM .-> APP
```

## Рабочий процесс

1. Аудио-файл (WAV) подаётся в CLI: `basicpitch/build/basicpitch <wav> <out dir> [options]`.
2. `ort_inference.cpp` выполняет инференс нейросети (модель `ort-model/model.ort`, веса в `.c/.h` встроены в бинарник).
3. `midi_notes.cpp` формирует MIDI-события через libremidi (ручки — см. `README.md`); результат — `.mid`-файл в каталоге вывода.
4. Для сравнения с эталоном события выгружаются в `mid.txt` (формат midicsv).

## Ключевые файлы

| Путь | Роль |
|---|---|
| `basicpitch/src/basicpitch.cpp/src_cli/CMakeLists.txt` | сборочный скрипт CLI (источник конфигурации `build/`) |
| `basicpitch/src/basicpitch.cpp/src/basicpitch.hpp` | API + struct `ModelParams` (ручки, дефолты) |
| `basicpitch/src/basicpitch.cpp/src/audio_effects.*` | аудиоэффекты до модели: НЧ/ВЧ-срезы, шумоподавитель, компрессор-экспандер (2026-09-12) |
| `basicpitch/src/libv2m/v2m.h` | C-API: `v2m_transcribe`, `v2m_spectrogram`, `v2m_process_audio` (обработка без транскрипции) |
| `basicpitch/src/basicpitch.cpp/ort-model/` | модель в форматах ONNX/ORT и сгенерированные веса |
| `basicpitch/build/basicpitch` | собранный бинарник CLI (работает автономно) |
| `data/mid.txt` | текстовое представление MIDI-событий (midicsv) |
| `data/test.wav` | аудио-вход |
| `docs/ui.md` | описание GUI (справочник для агентов) |
| `app/shared/src/commonMain/kotlin/com/v2m/app/` | Общий код: `Strings.kt` (тексты, `ParamHelp`), `Midi.kt` (`MidiNote`, `SongData`), `Key.kt` (тональность, такты), `Gamma.kt` (гаммы спектрограммы), `Build.kt` (номер билда; генерируется задачей `generateBuild` из `v2m.build` в `app/gradle.properties`), `AudioCapture.kt` (2026-09-22) |
| `app/shared/src/jvmShared/kotlin/com/v2m/app/` | Общее для JVM и Android: `V2mEngine.kt` (JNI-мост), `Preferences.kt` (prefs.properties), `AppData.kt` (каталог данных), `Wav.kt`, `MidiMeta.kt`, `AbcExport.kt`, `NoteEdit.kt`, `Instruments.kt`, `Presets.kt`, `FramesSummary.kt`, `Log.kt` |
| `app/desktopApp/src/desktopMain/kotlin/com/v2m/app/` | GUI desktop: `App.kt` (фреймы, состояние), `NoteChart.kt` (гистограмма), `SpectrogramView.kt` (вкладка «Спектр», 2026-09-12), `MidiPlayer.kt`, `WavPlayer.kt`, `NativeCapture.kt` (ALSA) |
| `app/androidApp/src/main/kotlin/com/v2m/app/android/MainActivity.kt` | Android-каркас: ядро, каталог данных, prefs (интерфейс — этап 4 плана) |
| `data/Muse — New Born [MIDIfind.com].mid` и др. | эталонные MIDI для сравнения |

## Примечания

- 2026-08-29: реструктуризация — код в `basicpitch/`, музыка и данные в `data/`, документация в `docs/`. Удалён дубль `libs/libremidi` (копия `vendor/libremidi`); удалены устаревшие сборочные каталоги.
- 2026-08-30: заведён `libs/` — внешние исходники библиотек (эксперименты); клонирован `lomse` (lenmus/lomse, движок нотной записи; читает MusicXML/LMD/MNX, MIDI напрямую не импортирует).
- 2026-08-31: в `libs/` перенесён `verovio` (клон из корня; headless-рендер нот в SVG); оба — кандидаты на рендер нот, отложены (рабочий путь — MuseScore); в репозиторий не включаются (`.gitignore`).
- 2026-09-22: этап 3 плана Android — слой логики перенесён в `app/shared` (`commonMain` — модели, строки, тональность, гаммы; `jvmShared` — JNI, prefs, WAV, MIDI, пресеты); каталог данных приложения — `AppData` (desktop `~/.v2m`, Android `filesDir`).
- 2026-09-04: актуализирован блок `data/` (новые файлы экспериментов: `experience.db`, `exp/`, `test7_report.md`, звуковые файлы кнопок GUI, `input/`/`output/`).
- Сборочный каталог `basicpitch/build` переконфигурирован после реструктуризации (свежая сборка).
- Собранный бинарник `basicpitch/build/basicpitch` автономен (веса модели встроены) и работает независимо от переконфигурации.
- `data/bin/`, `data/build/`, `data/output/` пусты — зарезервированы. !ai
