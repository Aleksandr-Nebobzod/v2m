package com.v2m.app

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Форматирование чисел без локали — замена `String.format(Locale.ROOT, …)`
 *  в общем коде (этап 4в): разделитель — точка, разделителей разрядов нет.
 *  Округление — half-up «от нуля», как у `%f` в Java (проверяется в самотесте
 *  сверкой с `String.format`); знак сохраняется и у −0.0. */

/** Число с [digits] знаками после точки (аналог `%.{digits}f`). */
fun fmt(x: Double, digits: Int): String {
    if (x.isNaN()) return "NaN"
    if (x.isInfinite()) return if (x > 0) "Infinity" else "-Infinity"
    var pow = 1.0
    repeat(digits) { pow *= 10 }
    val scaled = x * pow
    val rounded = if (scaled >= 0) floor(scaled + 0.5) else ceil(scaled - 0.5)
    // Вне диапазона Long точное разбиение невозможно — отдаём как есть
    if (!rounded.isFinite() || abs(rounded) >= 9.0e18) return x.toString()
    val neg = x < 0 || (x == 0.0 && 1.0 / x < 0) // знак −0.0 Java сохраняет
    val n = abs(rounded).toLong()
    val p = pow.toLong()
    val frac = if (digits > 0) "." + (n % p).toString().padStart(digits, '0') else ""
    return (if (neg) "-" else "") + (n / p).toString() + frac
}

/** То же для [Float] (места вызова хранят уровни и доли во Float). */
fun fmt(x: Float, digits: Int): String = fmt(x.toDouble(), digits)

/** Число в поле ширины [width] — дополнение пробелами слева (аналог `%{width}.{digits}f`). */
fun fmtPad(x: Double, digits: Int, width: Int): String = fmt(x, digits).padStart(width)

/** Целое с ведущими нулями (аналог `%0{width}d`): знак не входит в ширину
 *  цифр — `-1` при ширине 3 даёт `-01`, как у Java. */
fun zeroPad(n: Int, width: Int): String {
    val s = n.toString()
    if (s.length >= width) return s
    val neg = s.startsWith("-")
    val digits = (if (neg) s.substring(1) else s).padStart(width - (if (neg) 1 else 0), '0')
    return if (neg) "-$digits" else digits
}
