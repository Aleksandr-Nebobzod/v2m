package com.v2m.app

/** Каталог данных приложения — единое место определения (этап 3 плана
 *  docs/260921_android_plan.md; этап 4в — переехал в commonMain, каталог
 *  хранится строкой пути). Desktop — `~/.v2m` ([platformDefaultDataDir]);
 *  Android — `filesDir` приложения, его задаёт MainActivity при старте
 *  ([setDir]) — `user.home` там не определён. Потребители берут каталог
 *  только отсюда, путь к файлу собирает [dataPath]. */
object AppData {
    private var custom: String? = null

    /** Каталог данных; на desktop — по умолчанию `~/.v2m`. */
    val dir: String get() = custom ?: platformDefaultDataDir()

    /** Переопределить каталог (Android: `filesDir`). Вызывать до первых
     *  обращений к prefs и журналу. */
    fun setDir(d: String) {
        custom = d
    }
}

/** Путь к файлу [name] в каталоге данных ([AppData.dir]). */
fun dataPath(name: String): String = AppData.dir + "/" + name
