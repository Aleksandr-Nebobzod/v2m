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

/** Note name with alteration sign before the letter: ^ sharp, _ flat,
 *  = natural (shown when the key signature alters this pitch class). */
fun noteLabel(pitch: Int, key: KeyInfo?): String {
    val pc = pitch % 12
    val octave = pitch / 12 - 1
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
    return sign + letter + octave
}

/** Duration as a fraction of the measure (quarters / tsNum), reduced:
 *  "" for a quarter, "/8" for 1/8, " 3/16" for 3/16, "/1" for a whole measure. */
fun durationFrac(quarters: Double, tsNum: Int): String {
    val q = quarters / tsNum // fraction of the measure
    val n = Math.round(q * 16).toInt()
    if (n == 0) return ""
    val g = gcd(n, 16)
    val num = n / g
    val den = 16 / g
    return when {
        num == 1 && den == 4 -> "" // quarter is the default length
        num == 1 && den == 1 -> "/1"
        num == 1 -> "/$den"
        else -> " $num/$den"
    }
}

/** Beat position (in quarters from the measure start) as a fixed-mask
 *  fraction of the measure: "04/16", "09/16"; "--/--" when not on the
 *  16th grid; returns also the whole measures carried over (should be 0). */
fun beatFrac(beatQuarters: Double, tsNum: Int): Pair<Int, String> {
    val q = beatQuarters / tsNum // fraction of the measure
    val scaled = q * 16
    if (Math.abs(scaled - Math.round(scaled)) > 0.05) {
        return 0 to "--/--"
    }
    var n = Math.round(scaled).toInt()
    val carry = n / 16
    n %= 16
    return carry to "%02d/%02d".format(n, 16)
}

private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
