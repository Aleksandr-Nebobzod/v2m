package com.v2m.app

/** Журнал отладки (запрос А.М. 2026-09-06): строки «где кликнул и что
 *  произошло» уходят платформе ([Platform.log]) — на desktop это файл
 *  `~/.v2m/v2m-debug.log` плюс перехваченный стандартный вывод (все печати
 *  приложения, в т.ч. `e.printStackTrace`, попадают в тот же файл), на
 *  Android — файл под `filesDir`. Debug-точки — NoteChart.kt,
 *  App.kt playNoteTone/ABC.
 *
 *  Release-сборка: перед ней выставить [DEBUG] = false — все вызовы
 *  обёрнуты в if (Log.DEBUG), константа инлайнится, недостижимые ветки
 *  вырезаются компилятором: в байт-код release вызовы Log не попадают.
 *  Перехват стандартного вывода (desktop) тоже ставится только при DEBUG —
 *  его включает установка платформы ([installDesktopPlatform]). */
object Log {
    /** false — только перед release-сборкой (см. док объекта). */
    const val DEBUG = true

    /** Заголовок сессии в журнале. Вызывать после установки платформы
     *  ([installDesktopPlatform] / [installAndroidPlatform]). */
    fun install() {
        if (!DEBUG) return
        Platform.log.append("=== v2m #$BUILD: журнал тестирования ===")
    }

    /** Debug-строка в журнал (и консоль). Вызовы — только под if (Log.DEBUG). */
    fun d(tag: String, msg: String) {
        if (DEBUG) Platform.log.append("${formatLogStamp(nowEpochMillis())} [$tag] $msg")
    }
}
