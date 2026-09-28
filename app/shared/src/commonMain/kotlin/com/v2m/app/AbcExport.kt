package com.v2m.app

/** Экспорт разобранной песни текстом ABC-нотации (билд #35, п.6: кнопка
 *  «Экспорт» предлагает .mid/.musicxml/.abc). Поток — один голос (как
 *  строки таблицы ABC): аккорд-кластеры нот с общим стартовым тиком,
 *  между ними паузы «z». Полифонические хвосты (старт ноты до конца
 *  предыдущей) не моделируются — хвост обрезается к старту следующей
 *  ноты (!ai: вокальные данные v2m монофоничны, аккорды имеют общий
 *  старт). «Ноты нулевой длины не экспортируются» (А.М., п.6): события
 *  endTick <= startTick отбрасываются (muted-ноты исключены уже фильтром
 *  нормализации до вызова). Тактовые черты — на границах тактов; ноты
 *  длиннее остатка такта разбиваются с tie «-» (перенос ноты через
 *  барлайн в abc невыразим). Длительности — множители L=1/8, как в
 *  таблице (abcLen), но без «~»: в файле abc этот маркер недопустим,
 *  неточные — ближайшей dyadic-дробью. */
fun exportAbc(midi: ByteArray, title: String, key: KeyInfo?): String {
    val song = parseMidiSong(midi) // бросает IllegalArgumentException на не-SMF
    val division = song.division
    val barQ = song.tsNum * 4.0 / song.tsDen // quarters in a measure

    // Аккордовые кластеры: ноты с общим стартовым тиком; длительность кластера — максимум концов
    data class Cluster(val startQ: Double, val pitches: MutableList<Int>, var endQ: Double)
    val byStart = LinkedHashMap<Long, Cluster>()
    for (n in song.notes) {
        if (n.endTick <= n.startTick) continue // ноты нулевой длины не экспортируются
        val endQ = n.endTick.toDouble() / division
        val c = byStart.getOrPut(n.startTick) { Cluster(n.startTick.toDouble() / division, mutableListOf(), endQ) }
        c.pitches += n.pitch
        if (endQ > c.endQ) c.endQ = endQ
    }

    val sb = StringBuilder()
    sb.append("X:1\n")
    sb.append("T:").append(title.replace('\n', ' ').trim().ifEmpty { "v2m" }).append('\n')
    sb.append("M:").append(song.tsNum).append('/').append(song.tsDen).append('\n')
    sb.append("L:1/8\n")
    sb.append("Q:1/4=").append(Math.round(song.tempoBpm).toInt()).append('\n')
    if (key != null) sb.append("K:").append(abcKeyName(key)).append('\n')

    var posQ = 0.0 // quarters напечатано (поддерживается синхронно токенам)

    /** Тактовая черта с переводом строки, когда [posQ] на границе такта. */
    fun barIfNeeded() {
        val m = posQ % barQ
        if (posQ > 1e-9 && (m < 1e-9 || barQ - m < 1e-9)) sb.append(" |\n")
    }

    /** Паузы z от [startPos] длительностью [durQ] (с разбиением по тактам). */
    fun printRest(startPos: Double, durQ: Double) {
        posQ = startPos
        var left = durQ
        while (left > 1e-9) {
            val rest = barQ - posQ % barQ
            val piece = minOf(left, rest)
            posQ += piece
            left -= piece
            if (piece >= 1e-9) sb.append(" z").append(abcLenExport(piece))
            barIfNeeded()
        }
    }

    /** Ноты (аккорд в скобках) от [startPos] длительностью [durQ]; ноты,
     *  пересекающие барлайн, разбиваются с tie «-» на границе такта. */
    fun printNotes(startPos: Double, pitches: List<Int>, durQ: Double) {
        posQ = startPos
        var left = durQ
        val tok = if (pitches.size == 1) noteLabel(pitches[0], key)
        else pitches.sorted().joinToString("") { noteLabel(it, key) }.let { "[$it]" }
        while (left > 1e-9) {
            val rest = barQ - posQ % barQ
            val piece = minOf(left, rest)
            posQ += piece
            left -= piece
            if (piece >= 1e-9) {
                sb.append(' ').append(tok).append(abcLenExport(piece))
                if (left > 1e-9) sb.append('-')
            }
            barIfNeeded()
        }
    }

    for (c in byStart.values) {
        if (c.startQ > posQ + 1e-9) printRest(posQ, c.startQ - posQ) // пауза до кластера
        val start = maxOf(posQ, c.startQ) // перекрытие: хвост предыдущей обрезан
        printNotes(start, c.pitches, c.endQ - start)
    }
    // Финальный такт дополняется паузой — в abc такты должны быть полными
    val tail = barQ - posQ % barQ
    if (tail < barQ - 1e-9 && tail > 1e-9) printRest(posQ, tail)
    if (posQ > 1e-9) barIfNeeded()
    sb.append('\n')
    return sb.toString()
}

/** K:-имя тональности: корень в abc-орфографии (^/_ знаки, как noteLabel)
 *  плюс лад: мажор — голый корень («C»), минор-семейство — «m» («Am»);
 *  dorian/lydian — полным именем («K:CDorian» — поддержка импортёрами не
 *  гарантирована, !ai). */
private fun abcKeyName(key: KeyInfo): String {
    val root = when (val pc = key.rootPc) {
        1 -> "^C"
        3 -> "_E"
        6 -> "^F"
        8 -> "_A"
        10 -> "_B"
        else -> arrayOf("C", "D", "E", "F", "G", "A", "B")[
            intArrayOf(0, 2, 4, 5, 7, 9, 11).indexOf(pc)]
    }
    val mode = when {
        key.modeName.startsWith("major") || key.modeName.startsWith("Ionian") -> ""
        key.modeName.startsWith("dorian") -> "Dorian"
        key.modeName.startsWith("lydian") -> "Lydian"
        else -> "m" // natural/harmonic/melodic minor
    }
    return root + mode
}

/** Множитель длительности относительно L=1/8 (доли такта [quarters]) —
 *  файловая версия abcLen: целое при ошибке до 5%, иначе dyadic-дробь
 *  (2..256 знаменатель), всегда без «~»; числитель не опускается («/2»
 *  в abc делит предыдущую длительность — только явная дробь «1/2»).
 *  Суб-тиковые длительности (quarters < 1/256 L-единицы) не возникают:
 *  все позиции тиковые. */
fun abcLenExport(quarters: Double): String {
    val e = quarters / 0.5 // in eighths: the L multiplier
    val r = Math.round(e).toInt()
    if (r >= 1 && Math.abs(e - r) / r <= 0.05) return r.toString()
    var best = "1/256"
    var bestErr = Double.MAX_VALUE
    for (k in 1..8) { // denominators 2..256 (16th note..256th)
        val d = 1 shl k
        val n = Math.round(e * d).toInt().coerceAtLeast(1)
        val err = Math.abs(e - n.toDouble() / d) / (n.toDouble() / d)
        if (err <= 0.05) return "$n/$d"
        if (err < bestErr) {
            bestErr = err
            best = "$n/$d"
        }
    }
    return best
}
