// POSIX-совместимость для сборки libv2m под Windows (MSVC).
// Проект перенаправляет потоки на время прогона модели: гасит шум ONNX в
// stderr (ort_inference.cpp) и собирает отчёт в stdout (v2m_jni.cpp). Для этого
// нужны pipe/dup/dup2/open/close/read — POSIX-вызовы в Linux, их аналоги с
// подчёркиванием в MSVC (<io.h>). Одно определение на обе платформы; header
// подключают оба файла (он лежит рядом с ort_inference.cpp, каталог ${BP}/src
// есть в путях включения сборки libv2m).
#pragma once

#ifdef _WIN32
#include <cstdio>
#include <fcntl.h>
#include <io.h>

#ifndef STDOUT_FILENO // в MSVC эти имена определяет <stdio.h>
#define STDOUT_FILENO 1
#define STDERR_FILENO 2
#endif

using v2m_ssize = int;
inline int v2m_pipe(int *fd) { return _pipe(fd, 65536, _O_BINARY); }
#define V2M_DEVNULL "NUL"
#define v2m_dup _dup
#define v2m_dup2 _dup2
#define v2m_open _open
#define v2m_close _close
#define v2m_read _read
#else
#include <fcntl.h>
#include <unistd.h>

using v2m_ssize = ssize_t;
inline int v2m_pipe(int *fd) { return pipe(fd); }
#define V2M_DEVNULL "/dev/null"
#define v2m_dup dup
#define v2m_dup2 dup2
#define v2m_open open
#define v2m_close close
#define v2m_read read
#endif
