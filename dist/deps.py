#!/usr/bin/env python3
"""Список внешних разделяемых библиотек, которые надо положить в комплект v2m.

Обходит граф зависимостей libv2m.so через ldd и печатает строки
    <путь, под которым библиотеку ищет загрузчик>  ->  <реальный файл>
пропуская то, что есть в любой базовой системе (libc, libm, libstdc++ и пр.).

Зачем: libv2m.so линкуется с ONNX Runtime, установленным в системе разработчика
вне пакетов dpkg. Пользователю, скачавшему архив, этих библиотек взять негде —
комплект несёт их с собой в lib/, а run.sh выставляет LD_LIBRARY_PATH.

Использование:  python3 deps.py [путь к libv2m.so]
"""

import os
import re
import subprocess
import sys

# Есть в любой системе с glibc/gcc; в комплект не кладём.
BASE = {
    "libc.so.6", "libm.so.6", "libdl.so.2", "libpthread.so.0", "librt.so.1",
    "libgcc_s.so.1", "libstdc++.so.6", "ld-linux-x86-64.so.2",
}

LDD_LINE = re.compile(r"=>\s+(/\S+)")


def ldd(path):
    """Прямые зависимости файла — пути, разрешённые загрузчиком."""
    out = subprocess.run(["ldd", path], capture_output=True, text=True).stdout
    return LDD_LINE.findall(out)


def walk(root):
    """Обход графа в ширину. Возвращает {имя для загрузчика: реальный файл}."""
    found, seen, queue = {}, set(), [root]
    while queue:
        for dep in ldd(queue.pop()):
            real = os.path.realpath(dep)
            if real in seen:
                continue
            seen.add(real)
            if os.path.basename(real) in BASE:
                continue
            found[dep] = real
            queue.append(dep)
    return found


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else \
        os.path.join(os.path.dirname(__file__), "..",
                     "basicpitch", "src", "libv2m", "build", "libv2m.so")
    if not os.path.isfile(root):
        sys.exit(f"deps.py: не найден {root} — соберите libv2m (см. README.md)")
    for name, real in sorted(walk(root).items()):
        print(f"{os.path.basename(name)}\t{real}")


if __name__ == "__main__":
    main()
