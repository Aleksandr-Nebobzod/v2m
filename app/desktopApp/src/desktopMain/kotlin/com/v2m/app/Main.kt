package com.v2m.app

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import java.io.File

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
            "| note A3 = ${noteLabel(57, key)} | G3 = ${noteLabel(55, key)} | B3 = ${noteLabel(59, key)}")
    song.notes.forEach { println("  ${it.name}  %.2f-%.2f s".format(it.startSec, it.endSec)) }
}
