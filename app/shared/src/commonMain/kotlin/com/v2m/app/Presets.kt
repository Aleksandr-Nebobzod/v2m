package com.v2m.app

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

/** Хранилище пользовательских пресетов — properties-файл в каталоге данных
 *  ([AppData.dir]; на desktop ~/.v2m, где уже лежат prefs.properties; на
 *  Android — каталог файлов приложения). Ключ записи — имя пресета, значение —
 *  закодированный дифф. Формат и доступ к файлу — [readProps]/[writeProps]
 *  (этап 4в: общий код вместо java.util.Properties). */
class PresetStore(private val path: String) {

    fun list(): List<Preset> {
        val p = readProps(path)
        return p.keys.map { name ->
            Preset(name, parseDiff(p[name] ?: ""))
        }.sortedBy { it.name }
    }

    /** Применить пресет [name]; null, если его нет. */
    fun load(name: String): Preset? {
        val v = readProps(path)[name] ?: return null
        return Preset(name, parseDiff(v))
    }

    /** Сохранить пресет (добавить или перезаписать существующее имя). */
    fun save(p: Preset) {
        val props = readProps(path)
        props[p.name] = encodeDiff(p.diffs)
        writeProps(path, props, "v2m presets (name = diff from engine defaults)")
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
 *  пресет не входит; keySel = 0, сглаживание и стабильность питча = 1
 *  («выкл», билд #54) считаются дефолтом). */
fun diffFromDefaults(params: V2mEngine.Params, keySel: Int, smoothingWindow: Int,
                     pitchMedianWindow: Int): Map<String, String> {
    val cur = Preferences.paramsToProps(params, keySel, smoothingWindow, pitchMedianWindow)
    val defs = Preferences.paramsToProps(V2mEngine.Params.defaults(), 0, 1, 1)
    cur.remove("program"); defs.remove("program")
    val diff = LinkedHashMap<String, String>()
    for (k in cur.keys.sorted()) {
        val v = cur.getValue(k)
        if (v != defs[k]) diff[k] = v
    }
    return diff
}

/** Параметры движка по пресету: дефолты + диффы (клампы те же, что в
 *  Preferences.load); program — текущий инструмент пользователя. */
fun presetParams(p: Preset, currentProgram: Int): V2mEngine.Params =
    Preferences.paramsFromProps(p.diffs).copy(program = currentProgram)

/** keySel пресета (0 = авто, дефолт). */
fun presetKeySel(p: Preset): Int = p.diffs["keySel"]?.toIntOrNull()?.coerceIn(0, 24) ?: 0

/** smoothingWindow пресета (нечётное 1..15, дефолт 1 = «выкл», билд #54). */
fun presetSmoothing(p: Preset): Int =
    ((p.diffs["smoothingWindow"]?.toIntOrNull() ?: 1) or 1).coerceIn(1, 15)

/** pitchMedianWindow пресета (нечётное 1..7, дефолт 1 = «выкл», билд #54). */
fun presetPitchMedian(p: Preset): Int =
    ((p.diffs["pitchMedianWindow"]?.toIntOrNull() ?: 1) or 1).coerceIn(1, 7)
