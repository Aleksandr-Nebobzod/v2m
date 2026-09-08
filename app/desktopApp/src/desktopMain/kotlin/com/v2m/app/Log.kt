package com.v2m.app

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Журнал отладки (запрос А.М. 2026-09-06): пишет в ~/.v2m/v2m-debug.log,
 *  попутно перехватывая стандартный вывод (System.out/System.err) — все
 *  печати приложения (в т.ч. e.printStackTrace в catch-ветках) попадают в
 *  тот же файл. Единый журнал тестирования: «где кликнул и что произошло»
 *  (debug-точки — NoteChart.kt, App.kt playNoteTone/ABC).
 *
 *  Release-сборка: перед ней выставить [DEBUG] = false — все вызовы
 *  обёрнуты в if (Log.DEBUG), константа инлайнится, недостижимые ветки
 *  вырезаются компилятором: в байт-код release вызовы Log не попадают.
 *  Перехват стандартного вывода тоже ставится только при DEBUG (install). */
object Log {
    /** false — только перед release-сборкой (см. док объекта). */
    const val DEBUG = true

    private val file = File(System.getProperty("user.home"), ".v2m" + File.separator + "v2m-debug.log")
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    /** Разовый перехват System.out/System.err (tee: файл + консоль). */
    fun install() {
        if (!DEBUG) return
        try {
            file.parentFile?.mkdirs()
            val fos = FileOutputStream(file, true) // append — сессии теста не стирают друг друга
            val originalOut = System.out
            val originalErr = System.err
            System.setOut(PrintStream(Tee(fos, originalOut), true, Charsets.UTF_8))
            System.setErr(PrintStream(Tee(fos, originalErr), true, Charsets.UTF_8))
            println("=== v2m #$BUILD: журнал тестирования ===")
        } catch (e: Exception) {
            e.printStackTrace() // журнал — best-effort; без него приложение работает
        }
    }

    /** Debug-строка в журнал (и консоль). Вызовы — только под if (Log.DEBUG). */
    fun d(tag: String, msg: String) {
        if (DEBUG) println("${stamp.format(Date())} [$tag] $msg")
    }

    /** Перенаправляет поток в два вывода одновременно (файл + консоль). */
    private class Tee(private val a: OutputStream, private val b: OutputStream) : OutputStream() {
        override fun write(b0: Int) {
            a.write(b0); b.write(b0)
        }
        override fun write(buf: ByteArray, off: Int, len: Int) {
            a.write(buf, off, len); b.write(buf, off, len)
        }
        override fun flush() {
            a.flush(); b.flush()
        }
    }
}
