package com.v2m.app

/** Одна строка-паспорт записи для фрейма «Отчёт» (билд #38): кадровые
 *  признаки [json] (схема v2m-frame-features-2) коротко, для глаза — чем
 *  была запись (тесситура, уверенность, полифония, атаки, стабильность
 *  питча). Мини-парсер фиксированных ключей — без JSON-зависимости:
 *  каждый ключ в документе встречается ровно один раз. Нет ключей или
 *  сбой значения — null (строка не выводится). */
fun framesSummaryLine(json: String?): String? {
    if (json.isNullOrBlank()) return null
    fun num(key: String): Double? =
        Regex("\"$key\"\\s*:\\s*(-?[0-9.]+)").find(json)?.groupValues?.get(1)?.toDoubleOrNull()
    val pmin = num("pitch_min")?.toInt() ?: return null
    val pmax = num("pitch_max")?.toInt() ?: return null
    val conf = num("conf_p50") ?: return null
    val parts = mutableListOf(
        Strings.sumRange(pitchName(pmin), pitchName(pmax)),
        Strings.sumConf(fmt(conf, 2)),
    )
    num("poly_mean")?.let { poly ->
        val max = num("poly_max")?.toInt()
        val v = fmt(poly, 1) + (max?.let { "/$it" } ?: "")
        parts += Strings.sumPoly(v)
    }
    num("n")?.let { n ->
        val count = if (n == n.toLong().toDouble()) n.toLong().toString() else fmt(n, 0)
        val density = num("density_per_s")?.let { " (${fmt(it, 1)}/с)" } ?: ""
        parts += Strings.sumOnsets(count + density)
    }
    num("stable_ratio")?.let {
        parts += Strings.sumStable(it * 100.0)
    }
    num("drift_cents_mean")?.let { mean ->
        num("drift_cents_p95")?.let { p95 ->
            val v = "${fmt(mean, 1)}/${fmt(p95, 1)}"
            parts += Strings.sumDrift(v)
        }
    }
    return Strings.framesSummary(parts.joinToString(", "))
}
