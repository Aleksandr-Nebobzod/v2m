v2m — транскриптор мелодий / melody transcriber
================================================
Аудио в ноты: запись с микрофона или файл WAV -> MIDI, ABC-нотация, MusicXML.
Audio to notes: microphone recording or a WAV file -> MIDI, ABC notation, MusicXML.

Страница приложения / product page: http://attplus.in/v2m


Требования / Requirements
-------------------------
* Linux x86_64 (этот архив) / Linux x86_64 (this archive)
* Java 17 или новее / Java 17 or newer
  Debian/Ubuntu:  sudo apt install openjdk-17-jre
  Проверить / check:  java -version


Запуск / Running
----------------
  ./run.sh

Если файл не запускается, разрешите его выполнение / if it is not executable:
  chmod +x run.sh
  ./run.sh

Windows: распакуйте архив и запустите run.bat (нужна сборка для Windows —
файл v2m-windows-x64-*.jar; в этом архиве его нет).
Windows: unzip and run run.bat (requires the Windows build,
v2m-windows-x64-*.jar, which this archive does not contain).


Что внутри / Contents
---------------------
  run.sh                         запуск / launcher (Linux)
  run.bat                        запуск / launcher (Windows, для сборки под Windows)
  v2m-linux-x64-1.0.0.jar        приложение / the application
  lib/libv2m.so                  ядро транскрипции (C++, JNI) / transcription core
  lib/*.so                       ONNX Runtime и его зависимости / ONNX Runtime and deps

В систему ничего не устанавливается: все библиотеки лежат внутри архива,
приложению не нужны права root и доступ в интернет.
Nothing is installed system-wide: every library ships inside the archive,
the application needs neither root rights nor internet access.


Если что-то не работает / Troubleshooting
-----------------------------------------
«Java не найдена» / "Java not found"
    Установите Java 17+ (см. выше) или укажите JAVA_HOME.

Окно не появляется / no window appears
    Нужен графический сеанс (X11 или Wayland). Запуск по SSH без
    проброса графики окна не покажет. / A graphical session is required.

Запись с микрофона не работает / microphone recording fails
    Нужен ALSA (пакет libasound2) и доступное устройство захвата. /
    ALSA (libasound2) and a capture device are required.

Проверка без графики / headless check
    ./run.sh --self-test
    Прогоняет встроенный самотест и печатает отчёт / runs the built-in self-test.


Автор / Author
--------------
attplus.in
Ядро транскрипции — C++-порт basicpitch.cpp (MIT License, Sevag H);
алгоритм-основа — Spotify Basic Pitch (Apache License 2.0).
Transcription core is a C++ port of basicpitch.cpp (MIT License, Sevag H);
the underlying algorithm is Spotify Basic Pitch (Apache License 2.0).
