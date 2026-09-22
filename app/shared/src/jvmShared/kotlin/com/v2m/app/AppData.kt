package com.v2m.app

import java.io.File

/** Каталог данных приложения — единое место определения (этап 3 плана
 *  docs/260921_android_plan.md). Desktop — `~/.v2m` (как было до переноса:
 *  prefs.properties, v2m-debug.log); Android — `filesDir` приложения, его
 *  задаёт MainActivity при старте ([setDir]) — `System.getProperty("user.home")`
 *  там не определён. Потребители берут каталог только отсюда. */
object AppData {
    private var custom: File? = null

    /** Каталог данных; на desktop — по умолчанию `~/.v2m`. */
    val dir: File get() = custom ?: desktopDefault()

    /** Переопределить каталог (Android: `filesDir`). Вызывать до первых
     *  обращений к prefs и журналу. */
    fun setDir(d: File) {
        custom = d
    }

    private fun desktopDefault(): File =
        File(System.getProperty("user.home") ?: ".", ".v2m")
}
