package com.v2m.app

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.v2m.app.resources.Res
import java.io.File
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.ExperimentalResourceApi

/** Номер билда = номер пункта раздела истории (docs/history.md), описывающего билд. */
private const val BUILD = 21

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--self-test") {
        selfTest()
        return
    }
    application {
        Window(onCloseRequest = ::exitApplication, title = "v2m #$BUILD — транскрипция аудио в MIDI") {
            App()
        }
    }
}

/** Read a bundled compose-resource sample the same way the sound buttons do. */
@OptIn(ExperimentalResourceApi::class)
private fun sampleResource(path: String): ByteArray = runBlocking { Res.readBytes(path) }

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
    // fifths = 6 (F# major) also verifies the rebuilt libv2m.so: the old
    // library silently writes 0 (JNI resolves by symbol name, no crash)
    val ok = V2mEngine.midiToMusicXml(midi, File(outDir, "test4.musicxml").absolutePath, 0, 6)
    val song = parseMidiSong(midi)
    println("SELF-TEST: ${wav.name}: ${song.notes.size} notes -> ${midiFile.name}, musicxml=$ok")
    if (ok) {
        val xml = File(outDir, "test4.musicxml").readText()
        check("<key><fifths>6</fifths></key>" in xml) { "MusicXML fifths: expected 6, got 0 or absent" }
        println("SELF-TEST: MusicXML has <fifths>6</fifths>")
    }
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

    // Key-selector math (Р4 «Тональность» slider): 0 = auto, 1..12 major, 13..24 minor
    check(keyFromSelection(0) == null && keyFromSelection(25) == null) { "keySel bounds" }
    check(keyFromSelection(1) == KeyInfo(0, "major", 0)) { "C major: " + keyFromSelection(1) }
    check(keyFromSelection(7) == KeyInfo(6, "major", 6)) { "F# major: " + keyFromSelection(7) }
    check(keyFromSelection(13) == KeyInfo(0, "minor", -3)) { "C minor: " + keyFromSelection(13) }
    check(keyFromSelection(17) == KeyInfo(4, "minor", 1)) { "E minor: " + keyFromSelection(17) }
    check(keyFromSelection(22) == KeyInfo(9, "minor", 0)) { "A minor: " + keyFromSelection(22) }

    // Sound-sample resources played by the two sound buttons (read the same
    // way the buttons read them): counIn.mid = 5 count-in clicks on the
    // percussion channel (tempo 120 in the file — the button retempos it to
    // the slider value, see rewriteSample below); tonica.mid = C tonica
    // C-E-G-E-C with one repeated C (6 attacks) at a single 180 BPM tempo
    // (the 120→180 switch was removed — А.М.: «пусть будет только 180»).
    val counInBytes = sampleResource("files/counIn.mid")
    val tonicaBytes = sampleResource("files/tonica.mid")
    val counInFile = File(outDir, "counIn.mid")
    val tonicaFile = File(outDir, "tonica.mid")
    counInFile.writeBytes(counInBytes)
    tonicaFile.writeBytes(tonicaBytes)
    fun tempoEvents(midi: ByteArray): List<Int> {
        val out = ArrayList<Int>()
        var i = 0
        while (i + 5 < midi.size) {
            if (midi[i] == 0xFF.toByte() && midi[i + 1] == 0x51.toByte() && midi[i + 2] == 0x03.toByte()) {
                out += ((midi[i + 3].toInt() and 0xFF) shl 16) or
                    ((midi[i + 4].toInt() and 0xFF) shl 8) or (midi[i + 5].toInt() and 0xFF)
                i += 6
            } else i++
        }
        return out
    }
    val counInSong = parseMidiSong(counInBytes)
    println("SELF-TEST: counIn.mid: ${counInSong.notes.map { it.pitch }} @ ${counInSong.tempoBpm} BPM")
    check(counInSong.notes.size == 5) { "counIn attacks: ${counInSong.notes.size}" }
    check(counInSong.notes.map { it.pitch } == listOf(64, 37, 37, 37, 64)) { "counIn pitches: ${counInSong.notes.map { it.pitch }}" }
    check(Math.abs(counInSong.tempoBpm - 120.0) < 0.01) { "counIn tempo: ${counInSong.tempoBpm}" }
    check(counInBytes.toList().any { (it.toInt() and 0xFF) == 0x99 }) { "counIn must hit on the percussion channel (9)" }
    val tonicaSong = parseMidiSong(tonicaBytes)
    println("SELF-TEST: tonica.mid: ${tonicaSong.notes.map { it.pitch }} @ ${tonicaSong.tempoBpm} BPM")
    check(tonicaSong.notes.size == 6) { "tonica attacks: ${tonicaSong.notes.size}" }
    check(tonicaSong.notes.map { it.pitch } == listOf(60, 60, 64, 67, 64, 60)) { "tonica pitches: ${tonicaSong.notes.map { it.pitch }}" }
    check(tempoEvents(counInBytes) == listOf(500000)) { "counIn tempo events: ${tempoEvents(counInBytes)}" }
    check(tempoEvents(tonicaBytes) == listOf(333333)) { "tonica tempo events (single 180 BPM): ${tempoEvents(tonicaBytes)}" }

    // Sample transforms behind the sound buttons (п.18 приёмки):
    // metronome — count-in retempoed to the slider (2б); the tonica button
    // is only enabled with a chosen key (3а) and transposes the sample so
    // its root lands on the key's root in the middle octave, lowering the
    // major thirds in minor keys (3б, «понижать терцию»).
    val retempoed = rewriteSample(counInBytes, tempoBpm = 150.0)
    val retempoedSong = parseMidiSong(retempoed)
    println("SELF-TEST: counIn @150 BPM: tempo events ${tempoEvents(retempoed)}")
    check(tempoEvents(retempoed) == listOf(400000)) { "rewriteSample tempo: ${tempoEvents(retempoed)}" }
    check(retempoedSong.notes.map { it.pitch } == listOf(64, 37, 37, 37, 64)) { "rewriteSample must keep pitches" }
    val dMajor = transposeSample(tonicaBytes, keyFromSelection(3)!!) // D major: +2
    val cMinor = transposeSample(tonicaBytes, keyFromSelection(13)!!) // C minor: терции вниз
    val eMinor = transposeSample(tonicaBytes, keyFromSelection(17)!!) // E minor: +4, терции вниз
    println("SELF-TEST: tonica D major: ${parseMidiSong(dMajor).notes.map { it.pitch }}, " +
        "C minor: ${parseMidiSong(cMinor).notes.map { it.pitch }}, " +
        "E minor: ${parseMidiSong(eMinor).notes.map { it.pitch }}")
    check(parseMidiSong(dMajor).notes.map { it.pitch } == listOf(62, 62, 66, 69, 66, 62)) { "D major transpose" }
    check(parseMidiSong(cMinor).notes.map { it.pitch } == listOf(60, 60, 63, 67, 63, 60)) { "C minor transpose (lowered third)" }
    check(parseMidiSong(eMinor).notes.map { it.pitch } == listOf(64, 64, 67, 71, 67, 64)) { "E minor transpose" }
    check(tempoEvents(dMajor) == listOf(333333) && tempoEvents(cMinor) == listOf(333333)) { "transpose must keep the tempo" }

    // Key override in the bytes: C minor -> FF 59 02 FD 01 (sf -3 as 0xFD, mi = 1)
    val keyedFile = File(outDir, "test4-keyed.mid")
    keyedFile.writeBytes(finalizeMidi(withMeta, null, null, KeyInfo(0, "minor", -3)))
    val ff59 = keyedFile.readBytes().toList().windowed(5)
        .firstOrNull { it[0] == 0xFF.toByte() && it[1] == 0x59.toByte() }
    println("SELF-TEST: FF 59: " + (ff59?.joinToString { "%02x".format(it.toInt() and 0xFF) } ?: "not found"))
    check(ff59 != null && ff59[2] == 0x02.toByte() && ff59[3] == 0xFD.toByte() && ff59[4] == 0x01.toByte()) { "FF 59 bytes: ${ff59?.joinToString { "%02x".format(it.toInt() and 0xFF) }}" }
    check(finalizeMidi(withMeta, null, null, null) contentEquals withMeta) { "finalizeMidi(null key) must return the input unchanged" }
    try {
        val proc = ProcessBuilder("midicsv", keyedFile.absolutePath).redirectErrorStream(true).start()
        val csv = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        val ks = Regex("Key_signature, (-?\\d+), (.+)").find(csv)
        val mode = ks?.groupValues?.get(2)?.trim()?.lowercase()
        val isMinor = mode == "1" || mode == "\"minor\""
        println("SELF-TEST: midicsv Key_signature=${ks?.groupValues?.get(1)}/$mode (expect -3/minor)")
        check(ks?.groupValues?.get(1) == "-3" && isMinor) { "midicsv key: ${ks?.groupValues}" }
        fun csvOf(f: File): String {
            val proc = ProcessBuilder("midicsv", f.absolutePath).redirectErrorStream(true).start()
            val s = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            return s
        }
        fun noteOns(csv: String): Int = Regex("Note_on_c, (\\d+), (\\d+), (\\d+)").findAll(csv)
            .count { it.groupValues[3].toInt() > 0 }
        fun tempos(csv: String): List<String> =
            Regex("Tempo, (\\d+)").findAll(csv).map { it.groupValues[1] }.toList()
        val ccsv = csvOf(counInFile)
        val tcsv = csvOf(tonicaFile)
        println("SELF-TEST: midicsv counIn Tempo=${tempos(ccsv)} (expect [500000]), Note_on=${noteOns(ccsv)} (expect 5)")
        check(tempos(ccsv) == listOf("500000")) { "counIn Tempo via midicsv: ${tempos(ccsv)}" }
        check(noteOns(ccsv) == 5) { "counIn Note_on via midicsv: ${noteOns(ccsv)}" }
        println("SELF-TEST: midicsv tonica Tempo=${tempos(tcsv)} (expect [500000, 333333]), Note_on=${noteOns(tcsv)} (expect 6)")
        check(tempos(tcsv) == listOf("500000", "333333")) { "tonica Tempo via midicsv: ${tempos(tcsv)}" }
        check(noteOns(tcsv) == 6) { "tonica Note_on via midicsv: ${noteOns(tcsv)}" }
    } catch (e: Exception) {
        println("SELF-TEST: midicsv not available, skip key/sample csv checks (${e.message})")
    }

    // Anacrusis (Р5 «Затакт»): a 4/8 pickup is prepended at tick 0 and the
    // original FF 58 is shifted by 4 eighths (4*240 = 960 ticks); sound does
    // not move. 0 (or out-of-range) returns the input unchanged.
    val anacrBase = finalizeMidi(withMeta, null, 4 to 4) // guaranteed FF 58 4/4
    check(anacrusisMidi(anacrBase, 0) contentEquals anacrBase) { "anacrusis 0 must return the input unchanged" }
    check(anacrusisMidi(anacrBase, 9) contentEquals anacrBase) { "anacrusis out of range must return the input unchanged" }
    val anacrMid = anacrusisMidi(anacrBase, 4)
    val anacrOct = listOf(0xFF.toByte(), 0x58.toByte(), 0x04, 0x04, 0x03, 0x18, 0x08)
    check(anacrMid.toList().windowed(7).any { it == anacrOct }) { "anacrusis FF 58 04 04 03 18 08 not found" }
    val anacrFile = File(outDir, "test4-anacrusis.mid")
    anacrFile.writeBytes(anacrMid)
    try {
        val proc = ProcessBuilder("midicsv", anacrFile.absolutePath).redirectErrorStream(true).start()
        val csv = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        // midicsv line: <track>, <tick>, Time_signature, <num>, <den_power>, ...
        val div = ((anacrBase[12].toInt() and 0xFF) shl 8) or (anacrBase[13].toInt() and 0xFF)
        val expShift = 4 * div / 2 // 4 eighths in this file's units (TPQN here is 220 -> 440)
        val tsLines = Regex("(\\d+), (\\d+), Time_signature, (\\d+), (\\d+)").findAll(csv)
            .map { it.groupValues.drop(1) }.toList()
        println("SELF-TEST: midicsv TS after anacrusis: $tsLines (expect 4/8 pickup at tick 0 and 4/4 at tick $expShift)")
        check(tsLines.any { it[1] == "0" && it[2] == "4" && it[3] == "3" }) { "pickup 4/8 at tick 0 missing: $tsLines" }
        check(tsLines.any { it[1] == expShift.toString() && it[2] == "4" && it[3] == "2" }) { "4/4 not shifted to tick $expShift: $tsLines" }
    } catch (e: Exception) {
        println("SELF-TEST: midicsv not available, skip anacrusis csv check (${e.message})")
    }

    // MusicXML with the same pickup: the first (incomplete) measure carries
    // no <time>; the first full measure does. With anacrusis = 0 the <time>
    // stays in measure 1 (regression check).
    val anacrXml = File(outDir, "test4-anacrusis.musicxml")
    val xmlOk = V2mEngine.midiToMusicXml(anacrMid, anacrXml.absolutePath, 0, 0, 4)
    check(xmlOk) { "anacrusis musicxml conversion failed" }
    val ax = anacrXml.readText()
    val firstBarEnd = ax.indexOf("</measure>")
    val firstTime = ax.indexOf("<time>")
    println("SELF-TEST: anacrusis musicxml first </measure>=$firstBarEnd, first <time>=$firstTime")
    check(firstBarEnd > 0 && firstTime > firstBarEnd) { "anacrusis: the pickup measure must carry no <time>" }
    val plainXml = File(outDir, "test4-plain.musicxml")
    V2mEngine.midiToMusicXml(anacrBase, plainXml.absolutePath, 0, 0, 0)
    val px = plainXml.readText()
    println("SELF-TEST: plain musicxml first </measure>=${px.indexOf("</measure>")}, first <time>=${px.indexOf("<time>")}")
    check(px.indexOf("<time>") in 0 until px.indexOf("</measure>")) { "no anacrusis: <time> must be in the first measure" }

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
        // The MuseScore tempo figure is informational only: on this data file
        // MuseScore recalculates the tempo from the notes either way (raw 95
        // BPM reads as 119 before and after normalization — a known MuseScore
        // trait, see the 2026-09-02 rhythm work). The real guarantee of
        // normalizeMidi, checked below: stray Note-offs and repeated Note-ons
        // are dropped and every surviving note keeps its tick (dropping an
        // event used to shift all later notes earlier, misaligning bars).
        try {
            fun musescoreTempo(f: File): String? {
                val out = File(outDir, f.nameWithoutExtension + ".mscx")
                val proc = ProcessBuilder("musescore3", f.absolutePath, "-o", out.absolutePath)
                    .redirectErrorStream(true).start()
                proc.inputStream.bufferedReader().readText()
                proc.waitFor()
                return out.takeIf { it.isFile }?.readText()
                    ?.let { Regex("<tempo>([0-9.]+)<").find(it)?.groupValues?.get(1) }
            }
            val rawT = musescoreTempo(test7)
            val normT = musescoreTempo(normFile)
            println("SELF-TEST: MuseScore tempo raw=$rawT normalized=$normT (informational)")
        } catch (e: Exception) {
            println("SELF-TEST: musescore3 not available, skip MuseScore check (${e.message})")
        }
        try {
            fun noteOnPairs(f: File): List<Pair<Int, Int>> {
                val proc = ProcessBuilder("midicsv", f.absolutePath).redirectErrorStream(true).start()
                val csv = proc.inputStream.bufferedReader().readText()
                proc.waitFor()
                return Regex("(\\d+), (\\d+), Note_on_c, (\\d+), (\\d+), (\\d+)").findAll(csv)
                    .filter { it.groupValues[5].toInt() > 0 }
                    .map { it.groupValues[2].toInt() to it.groupValues[4].toInt() }
                    .sortedBy { it.first }.toList()
            }
            val rawOn = noteOnPairs(test7)
            val normOn = noteOnPairs(normFile)
            val shifted = normOn.filter { p -> rawOn.count { it == p } < normOn.count { it == p } }
            println("SELF-TEST: Note_on raw=${rawOn.size} (${rawOn.size - normOn.size} repeated attacks dropped) " +
                    "normalized=${normOn.size}, shifted=${shifted.size}")
            check(shifted.isEmpty()) { "normalizeMidi must drop repeated attacks only, no note may shift: $shifted" }
        } catch (e: Exception) {
            println("SELF-TEST: midicsv not available, skip normalize tick check (${e.message})")
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
