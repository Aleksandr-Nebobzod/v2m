#!/bin/sh
# Сборка комплекта v2m для скачивания (java-desktop версия, Linux x86_64).
#
# Комплект самодостаточен: приложение (uber-jar Compose Desktop), ядро
# транскрипции libv2m.so и его зависимости (ONNX Runtime и пр.) — внутри
# архива. Пользователю нужна только Java 17+ (см. README.txt).
#
# Запуск:  ./make.sh            — собрать архив в out/
#          ./make.sh --no-build — не пересобирать jar и libv2m.so

set -e

DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$DIR/.." && pwd)
OUT="$DIR/out"
STAGE="$DIR/out/v2m-linux-x64-1.0.0"
JAR_SRC="$ROOT/app/desktopApp/build/compose/jars/v2m-linux-x64-1.0.0.jar"
SO_SRC="$ROOT/basicpitch/src/libv2m/build/libv2m.so"
TAR="$OUT/v2m-linux-x64-1.0.0.tar.gz"

BUILD=yes
[ "$1" = "--no-build" ] && BUILD=no

if [ "$BUILD" = yes ]; then
    echo "== сборка ядра libv2m.so =="
    cmake -S "$ROOT/basicpitch/src/libv2m" -B "$ROOT/basicpitch/src/libv2m/build" >/dev/null
    cmake --build "$ROOT/basicpitch/src/libv2m/build" >/dev/null
    echo "== сборка приложения (uber-jar) =="
    (cd "$ROOT/app" && ./gradlew :desktopApp:packageUberJarForCurrentOS -q)
fi

[ -f "$JAR_SRC" ] || { echo "make.sh: не найден $JAR_SRC" >&2; exit 1; }
[ -f "$SO_SRC" ]  || { echo "make.sh: не найден $SO_SRC"  >&2; exit 1; }

echo "== сборка комплекта =="
rm -rf "$STAGE"
mkdir -p "$STAGE/lib"
cp "$JAR_SRC" "$STAGE/"
cp "$SO_SRC" "$STAGE/lib/"
cp "$DIR/run.sh" "$DIR/run.bat" "$DIR/README.txt" "$STAGE/"
chmod +x "$STAGE/run.sh"

# Зависимости libv2m.so (ONNX Runtime и пр.) — с именами, под которыми их
# ищет загрузчик, а не с именами реальных файлов (иначе libonnxruntime.so.1.21
# не найдётся и возьмётся системная).
python3 "$DIR/deps.py" "$SO_SRC" | while IFS="$(printf '\t')" read -r name real; do
    cp -L "$real" "$STAGE/lib/$name"
done

LIBS=$(ls "$STAGE/lib" | wc -l)
echo "   библиотек: $LIBS, размер комплекта: $(du -sh "$STAGE" | cut -f1)"

echo "== упаковка =="
rm -f "$TAR"
# С корневой папкой: распаковка даёт один каталог v2m-linux-x64-1.0.0,
# а не россыпь файлов в текущем (так же, как в инструкции на лендинге).
tar -czf "$TAR" -C "$OUT" "$(basename "$STAGE")"
echo "готово: $TAR ($(du -h "$TAR" | cut -f1))"
