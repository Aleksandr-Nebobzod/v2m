package com.v2m.app

import androidx.compose.ui.graphics.Color
import kotlin.math.log10

/** Уровни для полосы-индикатора (билд #40). Источники:
 *  - [mic] — запись: настоящий RMS сэмплов микрофона (публикует NativeCapture);
 *  - [midi] — встроенное воспроизведение: «громкость нот» — сумма velocity
 *    звучащих нот MIDI (публикует MidiPlayer), не реальный сигнал
 *    синтезатора (решение А.М. 2026-09-09: для MIDI достаточно громкости
 *    нот; реальный PCM — где он есть: запись/будущие WAV-источники). */
object AudioLevel {
    @Volatile var mic = 0f
    @Volatile var midi = 0f
}

/** Уровень в dBFS (20·lg(level)); ≤ 1e-5 считается −200 дБ (тишина —
 *  глубже любых порогов). Общая точка для зон/заливки и debug-меток. */
fun levelDb(level: Float): Float =
    if (level <= 1e-5f) -200f else 20f * log10(level)

/** Цвет зоны уровня; null — «молчание», полоса невидима. Пороги по RMS
 *  в dBFS (20·lg(level)): молчание < −42; серый «малый» до −30; салатовый
 *  «нормальный» до −12; малиновый «перегруз» выше. Калибровка на слух —
 *  константы здесь. */
fun meterZoneColor(level: Float): Color? {
    val db = levelDb(level)
    return when {
        db < -42f -> null
        db < -30f -> Color(0xFF9E9E9E) // серый
        db < -12f -> Color(0xFF8BC34A) // салатовый
        else -> Color(0xFFE53935)      // малиновый
    }
}

/** Доля заливки полосы слева: линейно от −42 dBFS (0) до 0 dBFS (1). */
fun meterFill(level: Float): Float =
    ((levelDb(level) + 42f) / 42f).coerceIn(0f, 1f)
