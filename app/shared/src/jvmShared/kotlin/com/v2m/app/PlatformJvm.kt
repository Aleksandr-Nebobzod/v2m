package com.v2m.app

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** actual-реализации для JVM и Android (этап 4в): обе платформы дают время и
 *  форматирование через `java.text` с `Locale.ROOT`, различие только в
 *  каталоге данных по умолчанию (на Android его задаёт приложение). */

actual fun nowEpochMillis(): Long = System.currentTimeMillis()

actual fun formatLogStamp(ms: Long): String =
    SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date(ms))

actual fun formatRecName(ms: Long): String =
    SimpleDateFormat("yyMMdd'_'HHmm'_v2m.wav'", Locale.ROOT).format(Date(ms))

actual fun platformDefaultDataDir(): String =
    File(System.getProperty("user.home") ?: ".", ".v2m").absolutePath

actual fun loadV2mLibrary() {
    System.loadLibrary("v2m")
}
