package com.v2m.app

/** Key parsed from the native report line "mode fit: C major (Ionian), ...". */
data class KeyInfo(
    val rootPc: Int,
    val modeName: String,
    val fifths: Int,
) {
    val name: String get() = ROOT_NAMES[rootPc] + " " + modeName

    /** Pitch classes altered by the key signature. */
    val alterations: List<Int>
        get() {
            val sharps = listOf(6, 1, 8, 3, 10, 5, 0) // F# C# G# D# A# E# B#
            val flats = listOf(10, 3, 8, 1, 6, 11, 4) // Bb Eb Ab Db Gb Cb Fb
            return if (fifths >= 0) sharps.take(fifths) else flats.take(-fifths)
        }

    val alterationsText: String
        get() {
            if (fifths == 0) return "без знаков"
            val names = arrayOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
            return alterations.joinToString(" ") { names[it] }
        }
}

private val ROOT_NAMES = arrayOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")

// fifths for major keys by root pitch class (circle of fifths)
private val MAJOR_FIFTHS = mapOf(
    0 to 0, 7 to 1, 2 to 2, 9 to 3, 4 to 4, 11 to 5, 6 to 6,
    5 to -1, 10 to -2, 3 to -3, 8 to -4, 1 to -5,
)

/** Mode-fit summary parsed from the native report line
 *  "mode fit: C major (Ionian), 90% of notes within 30 cents, snap strength 1.0". */
data class ModeFit(val keyName: String, val pct: Int, val cents: Int, val strength: Double)

fun parseModeFit(report: String): ModeFit? {
    val m = Regex("mode fit: ([^,]+), (\\d+)% of notes within (\\d+) cents, snap strength ([0-9.,]+)")
        .find(report) ?: return null
    return ModeFit(m.groupValues[1].trim(), m.groupValues[2].toInt(),
        m.groupValues[3].toInt(), m.groupValues[4].replace(',', '.').toDouble())
}

/** Parse "mode fit: <root> <mode>, ..." from the native report; null if absent. */
fun parseKeyFromReport(report: String): KeyInfo? {
    val m = Regex("mode fit: (\\S+) ([^,]+?),").find(report) ?: return null
    val rootName = m.groupValues[1]
    val mode = m.groupValues[2].trim()
    val rootPc = ROOT_NAMES.indexOf(rootName)
    if (rootPc < 0) return null
    val fifths = when {
        mode.startsWith("major") || mode.startsWith("Ionian") ->
            MAJOR_FIFTHS[rootPc] ?: return null
        mode.startsWith("dorian") -> MAJOR_FIFTHS[(rootPc + 10) % 12] ?: return null
        mode.startsWith("lydian") -> MAJOR_FIFTHS[(rootPc + 7) % 12] ?: return null
        else -> // natural/harmonic/melodic minor (relative major, +3)
            MAJOR_FIFTHS[(rootPc + 3) % 12] ?: return null
    }
    return KeyInfo(rootPc, mode, fifths)
}

/** Note name in ABC pitch spelling: alteration sign before the letter
 *  (^ sharp, _ flat, = natural — shown when the key signature alters this
 *  pitch class), octave encoded by the letter case and primes/commas, no
 *  octave number: 60 -> "c", 61 -> "^c", 74 -> "d'", 84 -> "c''",
 *  48 -> "C", 36 -> "C,", 35 -> "B,,". */
fun noteLabel(pitch: Int, key: KeyInfo?): String {
    val pc = pitch % 12
    val sign = when (pc) {
        1 -> "^" // C#
        3 -> "_" // Eb
        6 -> "^" // F#
        8 -> "_" // Ab
        10 -> "_" // Bb
        else -> if (key != null && pc in key.alterations) "=" else ""
    }
    val letter = when (pc) {
        1 -> "C"
        3 -> "E"
        6 -> "F"
        8 -> "A"
        10 -> "B"
        else -> arrayOf("C", "D", "E", "F", "G", "A", "B")[
            intArrayOf(0, 2, 4, 5, 7, 9, 11).indexOf(pc)]
    }
    // 60 = middle C = "c"; each octave down lowers the letter case and
    // adds commas, each octave up raises it and adds primes.
    val level = Math.floorDiv(pitch - 60, 12)
    return when {
        level >= 0 -> sign + letter.lowercase() + "'".repeat(level)
        level == -1 -> sign + letter
        else -> sign + letter + ",".repeat(-level - 1)
    }
}

/** Fraction (0..100) of notes whose pitch class belongs to the key's scale
 *  (dorian/lydian modes are handled by name; anything else — major or the
 *  minor family — falls back to the natural scale). Null when the key is
 *  unknown or there are no notes. */
fun harmonyPct(notes: List<MidiNote>, key: KeyInfo?): Int? {
    if (key == null || notes.isEmpty()) return null
    val scale = when {
        key.modeName.startsWith("dorian") -> intArrayOf(0, 2, 3, 5, 7, 9, 10)
        key.modeName.startsWith("lydian") -> intArrayOf(0, 2, 4, 6, 7, 9, 11)
        key.modeName.startsWith("major") || key.modeName.startsWith("Ionian") ->
            intArrayOf(0, 2, 4, 5, 7, 9, 11)
        else -> intArrayOf(0, 2, 3, 5, 7, 8, 10) // minor family
    }
    val inKey = notes.count { (it.pitch - key.rootPc).mod(12) in scale }
    return inKey * 100 / notes.size
}

/** Note cell in the fixed note mask (the cell follows the " | " separator,
 *  whose space is the mask's slot 1): slot 2 — an alteration '^'/'_'/'=' or
 *  a blank placeholder, slot 3 — the letter, slot 4 — the octave marks
 *  (primes/commas) or a blank placeholder, then the length multiplier right
 *  after slot 4. The slots are always present, so the letters and the
 *  multipliers line up in columns: " A 2", "^c 3/2", " d'/4", " C,2",
 *  " z 1". A rest is "z" (no sign, no octave marks). Padded to the fixed
 *  column width of 10. */
fun noteCell(pitch: Int, isRest: Boolean, quarters: Double, key: KeyInfo?): String {
    val label = if (isRest) "z" else noteLabel(pitch, key)
    val alter = if (label[0] == '^' || label[0] == '_' || label[0] == '=') label[0].toString() else " "
    val body = if (alter != " ") label.substring(1) else label
    val octave = if (body.length > 1) body.substring(1) else " "
    return "$alter${body[0]}$octave${abcLen(quarters)}".padEnd(10)
}

/** Length multiplier relative to the report constant L=1/8, as a natural
 *  fraction with a power-of-two denominator: "1" for L itself, "2" for a
 *  quarter, "3/2" for 3 eighths, "/2" for a 16th (unit numerator is
 *  omitted). Within 5% of an integer the integer is exact; within 10% it is
 *  shown with "~". Off-grid values get the nearest dyadic fraction, "~"
 *  appended when the error exceeds 5%. */
fun abcLen(quarters: Double): String {
    val e = quarters / 0.5 // in eighths: the L multiplier
    val r = Math.round(e).toDouble()
    val rErr = Math.abs(e - r) / Math.max(1.0, r)
    if (rErr <= 0.05) return r.toInt().toString()
    if (rErr <= 0.10) return r.toInt().toString() + "~"
    var best = "/16"
    var bestErr = 1.0
    for (k in 1..8) { // denominators 2..256 (16th note..256th)
        val d = 1 shl k
        val n = Math.round(e * d).toInt()
        if (n <= 0) continue
        val err = Math.abs(e - n.toDouble() / d) / (n.toDouble() / d)
        if (err <= 0.05) return if (n == 1) "/$d" else "$n/$d"
        if (err < bestErr) {
            bestErr = err
            best = if (n == 1) "/$d" else "$n/$d"
        }
    }
    return best + "~"
}

/** Measure number (1-based) and the in-measure fraction on the 16th grid
 *  for an absolute position in quarters from the track start, under a time
 *  signature num/den. The measure is num*4/den quarters long, so the
 *  denominator matters: a 6/8 bar is 3 quarters = 12 sixteenths, not 6.
 *  A position never lands on the grid when the 16th division does not
 *  divide the beat evenly, hence the "~" marker (off the grid, nearest
 *  16th) returned separately — the caller places it before the measure
 *  number. With num/den equal to the song's detected signature it
 *  reproduces the legacy beatFrac carry + measure split of a display row. */
fun beatPos(qFromStart: Double, tsNum: Int, tsDen: Int): Triple<Int, String, String> {
    val perBar = tsNum * 16 / tsDen // sixteenths in a bar (integer for den 4/8)
    val t16f = qFromStart * 4.0 // position in sixteenths (may be fractional)
    val n = Math.round(t16f).toInt()
    val approx = Math.abs(t16f - n) > 0.05
    return Triple(n / perBar + 1, if (approx) "~" else "", "%02d/%02d".format(n % perBar, 16))
}

/** Beat position (in quarters from the measure start) as the measures
 *  carried over past the measure end, the "~" marker and the fixed-mask
 *  fraction "04/16", "09/16" — the legacy display split for a row of the
 *  current measure, kept for the unit checks; the production table uses
 *  beatPos under the effective signature (see App.kt). */
fun beatFrac(beatQuarters: Double, tsNum: Int): Triple<Int, String, String> {
    val (measure, tilde, frac) = beatPos(beatQuarters, tsNum, 4)
    return Triple(measure - 1, tilde, frac)
}
