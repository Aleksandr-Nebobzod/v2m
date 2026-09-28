#!/bin/sh
# v2m — транскриптор мелодий: запуск java-desktop версии (Linux x86_64).
#
# Требуется Java 17 или новее (проверяется ниже). Нативные библиотеки
# (libv2m.so и зависимости ONNX Runtime) лежат в подкаталоге lib/ рядом
# со скриптом — в систему ничего не устанавливается.
#
# Запуск:  ./run.sh [аргументы приложения]

set -e

DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAR="$DIR/v2m-linux-x64-1.0.0.jar"
LIBDIR="$DIR/lib"

if [ ! -f "$JAR" ]; then
    echo "v2m: не найден $JAR" >&2
    echo "     распакуйте архив целиком (jar и каталог lib/ должны лежать рядом с run.sh)" >&2
    exit 1
fi

# Java: JAVA_HOME имеет приоритет, иначе — из PATH.
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_BIN="$JAVA_HOME/bin/java"
else
    JAVA_BIN=java
fi

if ! command -v "$JAVA_BIN" >/dev/null 2>&1; then
    echo "v2m: Java не найдена." >&2
    echo "     Установите Java 17 или новее, например: sudo apt install openjdk-17-jre" >&2
    exit 1
fi

# Версия Java: 21.0.11 -> 21; 1.8.0_402 -> 8.
VER=$("$JAVA_BIN" -version 2>&1 | awk -F'"' '/version/ {print $2; exit}')
case "$VER" in
    1.*) MAJOR=$(echo "$VER" | cut -d. -f2) ;;
    *)   MAJOR=$(echo "$VER" | cut -d. -f1) ;;
esac
case "$MAJOR" in
    ''|*[!0-9]*)
        echo "v2m: не удалось определить версию Java (получено «$VER»)" >&2
        exit 1
        ;;
esac
if [ "$MAJOR" -lt 17 ]; then
    echo "v2m: нужна Java 17 или новее, найдена $VER" >&2
    echo "     Установите новую Java, например: sudo apt install openjdk-17-jre" >&2
    exit 1
fi

# libv2m.so ищется через java.library.path, её зависимости (ONNX Runtime) —
# через LD_LIBRARY_PATH; каталог lib/ должен идти раньше системных путей.
LD_LIBRARY_PATH="$LIBDIR${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export LD_LIBRARY_PATH

exec "$JAVA_BIN" -Djava.library.path="$LIBDIR" -jar "$JAR" "$@"
