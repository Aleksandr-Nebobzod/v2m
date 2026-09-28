@echo off
rem v2m — транскриптор мелодий: запуск java-desktop версии (Windows x64).
rem
rem Требуется Java 17 или новее (проверяется ниже). Нативные библиотеки
rem (v2m.dll и зависимости ONNX Runtime) лежат в подкаталоге lib\ рядом
rem со скриптом — в систему ничего не устанавливается.
rem
rem Запуск:  run.bat

setlocal

set "DIR=%~dp0"
set "JAR=%DIR%v2m-windows-x64-1.0.0.jar"
set "LIBDIR=%DIR%lib"

if not exist "%JAR%" (
    echo v2m: не найден %JAR%
    echo      Распакуйте архив целиком: jar и каталог lib\ должны лежать рядом с run.bat
    pause
    exit /b 1
)

rem Java: JAVA_HOME имеет приоритет, иначе — из PATH.
set "JAVA_BIN="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_BIN (
    for /f "delims=" %%i in ('where java 2^>nul') do (
        if not defined JAVA_BIN set "JAVA_BIN=%%i"
    )
)

if not defined JAVA_BIN (
    echo v2m: Java не найдена.
    echo      Установите Java 17 или новее: https://adoptium.net/
    pause
    exit /b 1
)

rem Версия Java: 21.0.11 -> 21; 1.8.0_402 -> 8.
set "VER="
for /f "tokens=3" %%v in ('"%JAVA_BIN%" -version 2^>^&1 ^| findstr /i "version"') do if not defined VER set "VER=%%~v"
for /f "tokens=1 delims=." %%a in ("%VER%") do set "MAJOR=%%a"
if "%MAJOR%"=="1" for /f "tokens=2 delims=." %%b in ("%VER%") do set "MAJOR=%%b"

if not defined MAJOR (
    echo v2m: не удалось определить версию Java ^(получено "%VER%"^)
    pause
    exit /b 1
)
if %MAJOR% LSS 17 (
    echo v2m: нужна Java 17 или новее, найдена %VER%
    echo      Установите новую Java: https://adoptium.net/
    pause
    exit /b 1
)

rem Каталог lib\ должен идти раньше системных путей поиска DLL.
set "PATH=%LIBDIR%;%PATH%"

"%JAVA_BIN%" -Djava.library.path="%LIBDIR%" -jar "%JAR%" %*
if errorlevel 1 pause
