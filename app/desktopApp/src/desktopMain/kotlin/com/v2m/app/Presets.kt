package com.v2m.app

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/** Пресет (приёмка #28, замечание 2 А.М.): уникальное имя + набор
 *  «ключ-значение» параметров, отличных от дефолтных (нативные дефолты
 *  движка, см. libv2m v2m_params_default). Ключи — те же, что в
 *  prefs.properties (единый источник имён — Preferences.paramsToProps);
 *  program (инструмент) в пресет не входит — он отдельный выбор
 *  пользователя. */
data class Preset(val name: String, val diffs: Map<String, String>)

/** Встроенные пресеты. Один источник — код (файлы оказались плохой идеей):
 *  наборы ниже набраны в движковых единицах; пустой дифф «02 нормальный» =
 *  дефолты движка. keySel/smoothingWindow не заданы — авто/5. minNoteLen
 *  не ниже нового минимума разрешения (9 кадров ≈ 104 мс — замечание А.М.
 *  2026-09-06; «01 чуткий» ранее ловил ноты 2 кадра = 23 мс — теперь это
 *  исключено, см. Preferences.kt). */
val FACTORY_PRESETS: List<Preset> = listOf(
    Preset("01 чуткий", mapOf(
        "onsetThreshold" to "0.3", "frameThreshold" to "0.15", "minNoteLen" to "9",
        "energyTol" to "20", "harmonizeMerge" to "1", "globalShift" to "0.3",
    )),
    Preset("02 нормальный", emptyMap()),
    Preset("03 авто", mapOf(
        "onsetThreshold" to "0.45", "minNoteLen" to "9", "energyTol" to "8",
        "velocityCompress" to "0.2", "quantize" to "1", "toleranceMs" to "80",
        "harmonizeMerge" to "2", "minBendBins" to "3", "globalShift" to "0.5",
        "modeSnap" to "0.9",
    )),
)

/** Хранилище пользовательских пресетов — data-storage паттерн: на desktop
 *  это properties-файл в пользовательских данных (~/.v2m, где уже лежат
 *  prefs.properties с каталогами и значениями последней сессии); на других
 *  платформах то же API отображается на их хранилище (Android
 *  SharedPreferences / DataStore). Ключ записи — имя пресета, значение —
 *  закодированный дифф. */
class PresetStore(private val file: File) {
    private fun loadProps(): Properties {
        val p = Properties()
        if (file.isFile) runCatching { file.inputStream().use(p::load) }
        return p
    }

    private fun storeProps(p: Properties) {
        try {
            file.parentFile?.mkdirs()
            // Atomic: temp + rename — как Preferences.save (см. там).
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().use { p.store(it, "v2m presets (name = diff from engine defaults)") }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            e.printStackTrace() // presets are best-effort; the app keeps working
        }
    }

    fun list(): List<Preset> {
        val p = loadProps()
        return p.stringPropertyNames().map { name ->
            Preset(name, parseDiff(p.getProperty(name) ?: ""))
        }.sortedBy { it.name }
    }

    /** Применить пресет [name]; null, если его нет. */
    fun load(name: String): Preset? {
        val v = loadProps().getProperty(name) ?: return null
        return Preset(name, parseDiff(v))
    }

    /** Сохранить пресет (добавить или перезаписать существующее имя). */
    fun save(p: Preset) {
        val props = loadProps()
        props.setProperty(p.name, encodeDiff(p.diffs))
        storeProps(props)
    }
}

/** Значения диффов — только числа и булевы (пишутся toString без
 *  спецсимволов), поэтому кодирование простым «k=v,k=v» безопасно. */
internal fun encodeDiff(d: Map<String, String>): String =
    d.entries.joinToString(",") { "${it.key}=${it.value}" }

internal fun parseDiff(s: String): Map<String, String> =
    if (s.isBlank()) emptyMap()
    else s.split(",").mapNotNull { part ->
        val eq = part.indexOf('=')
        if (eq <= 0) null else part.substring(0, eq) to part.substring(eq + 1)
    }.toMap()

/** Дифф текущих настроек от дефолтов движка (без program — инструмент в
 *  пресет не входит; keySel = 0 и smoothingWindow = 5 считаются дефолтом). */
fun diffFromDefaults(params: V2mEngine.Params, keySel: Int, smoothingWindow: Int): Map<String, String> {
    val cur = Preferences.paramsToProps(params, keySel, smoothingWindow)
    val defs = Preferences.paramsToProps(V2mEngine.Params.defaults(), 0, 5)
    cur.remove("program"); defs.remove("program")
    val diff = LinkedHashMap<String, String>()
    for (k in cur.stringPropertyNames().sorted()) {
        val v = cur.getProperty(k)
        if (v != defs.getProperty(k)) diff[k] = v
    }
    return diff
}

/** Параметры движка по пресету: дефолты + диффы (клампы те же, что в
 *  Preferences.load); program — текущий инструмент пользователя. */
fun presetParams(p: Preset, currentProgram: Int): V2mEngine.Params {
    val props = Properties()
    p.diffs.forEach { (k, v) -> props.setProperty(k, v) }
    return Preferences.paramsFromProps(props).copy(program = currentProgram)
}

/** keySel пресета (0 = авто, дефолт). */
fun presetKeySel(p: Preset): Int = p.diffs["keySel"]?.toIntOrNull()?.coerceIn(0, 24) ?: 0

/** smoothingWindow пресета (нечётное 3..15, дефолт 5). */
fun presetSmoothing(p: Preset): Int =
    ((p.diffs["smoothingWindow"]?.toIntOrNull() ?: 5) or 1).coerceIn(3, 15)
