package com.v2m.app

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import java.io.File
import java.util.Locale

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--self-test") {
        selfTest()
        return
    }
    application {
        Window(onCloseRequest = ::exitApplication, title = "v2m — транскрипция аудио в MIDI") {
            App()
        }
    }
}

/** Headless check of the full pipeline (no UI). Usage: --self-test <wav> [out dir] */
private fun selfTest() {
    val wav = File("/mnt/d/a/v2m/data/test4.wav")
    val outDir = File("/tmp/v2m-selftest").apply { mkdirs() }
    val (pcm, sr) = try {
        readWavMono(wav)
    } catch (e: Exception) {
        e.printStackTrace()
        error("wav read failed")
    }
    println("SELF-TEST: pcm=${pcm.size} sr=$sr")
    val params = V2mEngine.Params.defaults().copy(harmonizeMerge = 1, modeSnap = 1f)
    val midi = V2mEngine.transcribe(pcm, sr, params)
        ?: error("transcribe failed")
    println("SELF-TEST: midi bytes=${midi.size} header=" + midi.copyOfRange(0, 4).joinToString { "%02x".format(it) })
    val midiFile = File(outDir, "test4.mid")
    midiFile.writeBytes(midi)
    val ok = V2mEngine.midiToMusicXml(midi, File(outDir, "test4.musicxml").absolutePath)
    val song = parseMidiSong(midi)
    println("SELF-TEST: ${wav.name}: ${song.notes.size} notes -> ${midiFile.name}, musicxml=$ok")
    println("SELF-TEST REPORT:\n" + V2mEngine.lastReport())
    val key = parseKeyFromReport(V2mEngine.lastReport())
    println("SELF-TEST KEY: ${key?.name} (${key?.alterationsText}) " +
            "| 57 = ${noteLabel(57, key)} | 55 = ${noteLabel(55, key)} | 59 = ${noteLabel(59, key)}")
    song.notes.forEach { println("  ${it.name}  %.2f-%.2f s".format(it.startSec, it.endSec)) }

    // Display-format checks: length multipliers (L=1/8), note cell, beat mask
    check(abcLen(0.5) == "1") { "eighth: " + abcLen(0.5) }
    check(abcLen(1.0) == "2") { "quarter: " + abcLen(1.0) }
    check(abcLen(0.25) == "/2") { "16th: " + abcLen(0.25) }
    check(abcLen(0.125) == "/4") { "32nd: " + abcLen(0.125) }
    check(abcLen(0.75) == "3/2") { "dotted quarter: " + abcLen(0.75) }
    check(abcLen(0.44) == "7/8") { "sub-grid: " + abcLen(0.44) }
    check(abcLen(0.53) == "1~") { "near-L: " + abcLen(0.53) }
    check(noteCell(57, false, 1.0, null) == " A 2      ") { "note cell: " + noteCell(57, false, 1.0, null) }
    check(noteCell(57, true, 0.5, null) == " z 1      ") { "rest cell: " + noteCell(57, true, 0.5, null) }
    check(noteCell(61, false, 0.75, null) == "^c 3/2    ") { "sharp cell: " + noteCell(61, false, 0.75, null) }
    check(noteCell(36, false, 1.0, null) == " C,2      ") { "low octave: " + noteCell(36, false, 1.0, null) }
    check(noteCell(84, false, 0.5, null) == " c''1     ") { "high octave: " + noteCell(84, false, 0.5, null) }
    check(noteCell(34, false, 0.5, null) == "_B,,1     ") { "flat low: " + noteCell(34, false, 0.5, null) }
    check(noteLabel(35, null) == "B,,") { "natural low: " + noteLabel(35, null) }
    check(beatFrac(0.0, 4).third == "00/16") { "beat 0: " + beatFrac(0.0, 4) }
    check(beatFrac(1.0, 4).third == "04/16") { "beat 1: " + beatFrac(1.0, 4).third }
    check(beatFrac(0.3, 4).second == "~") { "off-grid beat: " + beatFrac(0.3, 4) }

    // (в, уточнение) The denominator of "Финальный размер" (4 or 8) shapes
    // the bar: 6/8 is 3 quarters = 12 sixteenths, 3/8 is 1.5 = 6; eighths
    // land on the 16th grid evenly (0, 2, 4 ...), the bar end carries over.
    check(beatPos(0.5, 6, 8) == Triple(1, "", "02/16")) { "6/8 first eighth: " + beatPos(0.5, 6, 8) }
    check(beatPos(3.0, 6, 8) == Triple(2, "", "00/16")) { "6/8 bar end: " + beatPos(3.0, 6, 8) }
    check(beatPos(0.5, 3, 8) == Triple(1, "", "02/16")) { "3/8 first eighth: " + beatPos(0.5, 3, 8) }
    check(beatPos(1.5, 3, 8) == Triple(2, "", "00/16")) { "3/8 bar end: " + beatPos(1.5, 3, 8) }

    // (в) Re-barring identity: beatPos under the song's own signature must
    // reproduce the previous row math (beatFrac carry + measure, replicated
    // inline) for every row of a 4/4 file — round(x + 16k) = 16k + round(x).
    demoMidiFile()?.let { f ->
        val s = runCatching { parseMidiSong(f.readBytes()) }.getOrNull()
        if (s != null && s.tsDen == 4) {
            val rows = buildRows(s)
            val bad = rows.count { r ->
                val q0 = (r.measure - 1.0) * s.tsNum + (r.beat - 1.0)
                val (m, t, fr) = beatPos(q0 * 4.0 / s.tsDen, s.tsNum, s.tsDen)
                val oldScaled = (r.beat - 1.0) / s.tsNum * 16
                val oldN = Math.round(oldScaled).toInt()
                val oldMeasure = r.measure + oldN / 16
                val oldTilde = if (Math.abs(oldScaled - oldN) > 0.05) "~" else ""
                val oldFrac = "%02d/%02d".format(oldN % 16, 16)
                m != oldMeasure || t != oldTilde || fr != oldFrac
            }
            check(bad == 0) { "beatPos(auto) differs from the old row math in $bad of ${rows.size} rows" }
            println("SELF-TEST: beatPos(auto) == old row math for all ${rows.size} rows of ${f.name}")
        }
    }
    printNotesExample()
    val mf = parseModeFit("mode fit: C major (Ionian), 90% of notes within 30 cents, snap strength 1,000000")
    check(mf != null && mf.pct == 90 && mf.cents == 30 && mf.strength == 1.0) { "mode fit parse: $mf" }
    check(parseModeFit("no fit here") == null) { "mode fit false positive" }

    // Save-chain check: patchProgram + meta track + re-parse must preserve notes
    val patched = patchProgram(midi, 25) // GM 25 = Acoustic Guitar
    val progByte = patched.toList().zipWithNext()
        .firstOrNull { (a, _) -> a.toInt() and 0xFF == 0xC0 }?.second?.toInt()?.and(0xFF)
    println("SELF-TEST: patched program (GM 25 -> expect 24): $progByte")
    check(progByte == 24) { "patchProgram did not replace the program change" }
    val withMeta = midiWithMetaTrack(patched,
        buildParamsJson("test4.wav", params, V2mEngine.lastReport(), key))
    val song2 = runCatching { parseMidiSong(withMeta) }.getOrNull()
    println("SELF-TEST: save-chain notes ${song.notes.size} -> ${song2?.notes?.size}, bytes=${withMeta.size}")
    check(song2 != null && song2.notes.size == song.notes.size) { "save chain lost notes" }
    val saved = File(outDir, "test4-saved.mid")
    saved.writeBytes(withMeta)

    // Finalize check: tempo/time-signature overrides land in the bytes
    val finalMid = File(outDir, "test4-final.mid")
    finalMid.writeBytes(finalizeMidi(withMeta, 70.0, 2 to 4))
    try {
        val proc = ProcessBuilder("midicsv", finalMid.absolutePath).redirectErrorStream(true).start()
        val csv = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        val tempo = Regex("Tempo, (\\d+)").find(csv)?.groupValues?.get(1)
        val ts = Regex("Time_signature, (\\d+), (\\d+)").find(csv)
        println("SELF-TEST: finalizeMidi Tempo=$tempo (expect 857143), TS=${ts?.groupValues?.get(1)}/${ts?.groupValues?.get(2)} (expect 2/2^2)")
        check(tempo == "857143") { "finalizeMidi tempo: $tempo" }
        check(ts?.groupValues?.get(1) == "2" && ts.groupValues[2] == "2") { "finalizeMidi TS: ${ts?.groupValues}" }
    } catch (e: Exception) {
        println("SELF-TEST: midicsv not available, skip finalize check (${e.message})")
    }

    // Normalize check: the engine can emit repeated Note-on / stray Note-off
    // (harmonize-merge stitching); MuseScore then misreads the exported tempo
    // (95 BPM shown as 119). normalizeMidi must make the file well-formed.
    val test7 = File("/mnt/d/a/v2m/data/output/test7-95.mid")
    if (test7.isFile) {
        val raw = test7.readBytes()
        val norm = normalizeMidi(raw)
        val nNorm = parseMidiSong(norm).notes.size
        val nRaw = parseMidiSong(raw).notes.size
        println("SELF-TEST: normalizeMidi notes $nRaw -> $nNorm")
        val normFile = File(outDir, "test7-95-norm.mid")
        normFile.writeBytes(norm)
        try {
            val proc = ProcessBuilder("musescore3", normFile.absolutePath,
                "-o", File(outDir, "test7-95-norm.mscx").absolutePath)
                .redirectErrorStream(true).start()
            proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            val xml = File(outDir, "test7-95-norm.mscx").takeIf { it.isFile }?.readText()
            val tempo = xml?.let { Regex("<tempo>([0-9.]+)<").find(it)?.groupValues?.get(1) }
            println("SELF-TEST: MuseScore tempo=$tempo (expect 1.58333 = 95 BPM)")
            check(tempo != null && tempo.startsWith("1.5833")) { "MuseScore misreads tempo: $tempo" }
        } catch (e: Exception) {
            println("SELF-TEST: musescore3 not available, skip MuseScore check (${e.message})")
        }
    }

    // Independent cross-check with midicsv (the parser that caught the delta bug)
    try {
        val proc = ProcessBuilder("midicsv", saved.absolutePath).redirectErrorStream(true).start()
        val csv = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        val noteOns = Regex("Note_on_c").findAll(csv).count()
        val progLine = Regex("Program_c, 0, (\\d+)").find(csv)?.groupValues?.get(1)
        println("SELF-TEST: midicsv Note_on=$noteOns (expect ${song.notes.size}), Program_c=$progLine (expect 24)")
        check(noteOns == song.notes.size) { "midicsv sees $noteOns note-ons, expected ${song.notes.size}" }
        check(progLine == "24") { "midicsv sees program $progLine, expected 24" }
    } catch (e: Exception) {
        println("SELF-TEST: midicsv not available, skip cross-check (${e.message})")
    }
}

/** A real transcription for the notes-table example. The worktree has no
 *  data/ dir and no test7-95.mid — the newest check7* run is preferred,
 *  then the other worktree runs, then the main-checkout data dir. */
private fun demoMidiFile(): File? {
    val candidates = listOf(
        "/mnt/d/a/v2m/.claude/worktrees/v2m-rhythm-test7/check7e/test7.mid",
        "/mnt/d/a/v2m/.claude/worktrees/v2m-rhythm-test7/check7d/test7.mid",
        "/mnt/d/a/v2m/.claude/worktrees/v2m-rhythm-test7/check7c/test7.mid",
        "/mnt/d/a/v2m/.claude/worktrees/v2m-rhythm-test7/check7b/test7.mid",
        "/mnt/d/a/v2m/.claude/worktrees/v2m-rhythm-test7/check7/test7.mid",
        "/mnt/d/a/v2m/.claude/worktrees/v2m-rhythm-test7/check-t4/test4.mid",
        "/mnt/d/a/v2m/.claude/worktrees/v2m-rhythm-test7/check-t1/test1.mid",
        "/mnt/d/a/v2m/data/output/test7-95.mid",
        "/mnt/d/a/v2m/data/test4.mid",
    )
    for (p in candidates) {
        val f = File(p)
        if (f.isFile) return f
    }
    return null
}

/** The notes-table header and up to 6 first rows of a real song, printed
 *  three times — in the auto-detected signature and re-barred under two
 *  export overrides (3/4, then 6/8 to show the denominator handling: a
 *  bar of num*4/den quarters) — to display the row format and the beatPos
 *  re-barring of the (в) edit. */
private fun printNotesExample() {
    val midi = demoMidiFile() ?: return
    val song = runCatching { parseMidiSong(midi.readBytes()) }.getOrNull() ?: return
    val rows = buildRows(song)
    val sizes = mutableListOf(song.tsNum to song.tsDen)
    for (s in listOf(3 to 4, 6 to 8)) if (s !in sizes) sizes.add(s)
    for ((effNum, effDen) in sizes) {
        println("NOTES-TABLE ${midi.name} — размер: $effNum/$effDen")
        println(Strings.notesHeader)
        rows.take(6).forEach { r ->
            val rowQ = (r.measure - 1.0) * song.tsNum + (r.beat - 1.0)
            val qFromStart = rowQ * 4.0 / song.tsDen
            val (measure, tilde, frac) = beatPos(qFromStart, effNum, effDen)
            val cell = noteCell(r.pitch, r.isRest, r.durationQuarters, null)
            val vel = if (r.isRest) "" else r.velocity.toString()
            println(String.format(Locale.ROOT, "  %1s%02d:%s | %s | %7.2f | %3s",
                tilde, measure, frac, cell, r.endSec - r.startSec, vel))
        }
    }
}
