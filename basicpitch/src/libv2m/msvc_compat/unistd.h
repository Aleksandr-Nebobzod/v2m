// Шим для MSVC: заголовка <unistd.h> в Windows нет, но его включают семь
// заголовков ресемплера Oboe (vendor/oboe-resampler/*.h — чужой код, не
// правим; ещё IntegerRatio.h включает <sys/types.h> — он в MSVC есть).
// POSIX-имён из <unistd.h> ресемплер не использует (ssize_t, usleep, read,
// write, access, close, pipe не встречаются ни в .h, ни в .cpp), поэтому
// достаточно пустого заголовка. Каталог подключается только для Windows
// (basicpitch/src/libv2m/CMakeLists.txt, ветка WIN32): на Linux системный
// <unistd.h> перекрывать нельзя.
#pragma once
