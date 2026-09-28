package com.v2m.app

/** Время, каталог данных и загрузка нативной библиотеки — expect/actual
 *  (этап 4в): общий код не может обращаться к `java.*`. Реализация одна на
 *  JVM и Android — `PlatformJvm.kt` в `jvmShared`. */

/** Текущее время в миллисекундах от эпохи. */
expect fun nowEpochMillis(): Long

/** Метка журнала `HH:mm:ss.SSS` (локальное время) — формат журнала тестирования. */
expect fun formatLogStamp(ms: Long): String

/** Автоимя записи `yyMMdd_HHmm_v2m.wav` (локальное время). Текстовые части
 *  маски — в кавычках литерала (урок #42: буквы вне кавычек SimpleDateFormat
 *  читает как символы паттерна и падает). */
expect fun formatRecName(ms: Long): String

/** Каталог данных по умолчанию: desktop — `~/.v2m`. На Android не определён —
 *  там каталог задаёт приложение ([AppData.setDir] с `filesDir`). */
expect fun platformDefaultDataDir(): String

/** Загрузить нативную библиотеку ядра (libv2m); повторный вызов безопасен. */
expect fun loadV2mLibrary()
