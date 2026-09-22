package com.v2m.app

import java.util.Locale

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
        String.format(Locale.ROOT, Strings.sumRange, pitchName(pmin), pitchName(pmax)),
        String.format(Locale.ROOT, Strings.sumConf, String.format(Locale.ROOT, "%.2f", conf)),
    )
    num("poly_mean")?.let { poly ->
        val max = num("poly_max")?.toInt()
        val v = String.format(Locale.ROOT, "%.1f", poly) + (max?.let { "/$it" } ?: "")
        parts += String.format(Locale.ROOT, Strings.sumPoly, v)
    }
    num("n")?.let { n ->
        val count = if (n == n.toLong().toDouble()) n.toLong().toString()
        else String.format(Locale.ROOT, "%.0f", n)
        val density = num("density_per_s")?.let { String.format(Locale.ROOT, " (%.1f/с)", it) } ?: ""
        parts += String.format(Locale.ROOT, Strings.sumOnsets, count + density)
    }
    num("stable_ratio")?.let {
        parts += String.format(Locale.ROOT, Strings.sumStable, it * 100.0)
    }
    num("drift_cents_mean")?.let { mean ->
        num("drift_cents_p95")?.let { p95 ->
            val v = String.format(Locale.ROOT, "%.1f/%.1f", mean, p95)
            parts += String.format(Locale.ROOT, Strings.sumDrift, v)
        }
    }
    return String.format(Locale.ROOT, Strings.framesSummary, parts.joinToString(", "))
}
