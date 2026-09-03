package com.v2m.app

import java.io.ByteArrayOutputStream

/** Params as CLI options (for the report header). */
fun paramsCli(p: V2mEngine.Params): String = buildString {
    append("--onset-threshold ${p.onsetThreshold} ")
    append("--frame-threshold ${p.frameThreshold} ")
    append("--min-note-len ${p.minNoteLen} ")
    append("--energy-tol ${p.energyTol} ")
    append("--program ${p.program} ")
    append("--velocity-compress ${p.velocityCompress} ")
    append(if (p.useMelodiaTrick) "--melodia-trick " else "--no-melodia-trick ")
    append(if (p.includePitchBends) "--pitch-bends " else "--no-pitch-bends ")
    append("--tempo ${p.tempoBpm} ")
    append("--quantize ${arrayOf("off", "auto", "beat", "eighth", "sixteenth")[p.quantize.coerceIn(0, 4)]} ")
    append("--tempo-tolerance ${p.toleranceMs} ")
    append("--harmonize-merge ${p.harmonizeMerge} ")
    append("--min-bend ${p.minBendBins} ")
    append("--global-shift ${p.globalShift} ")
    append("--mode-snap ${p.modeSnap}")
}

/** Parameters + report as a JSON document (for the Sequencer Specific meta). */
fun buildParamsJson(wavName: String, p: V2mEngine.Params, report: String, key: KeyInfo?): String {
    fun s(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    val params = buildString {
        append("\"onsetThreshold\":${p.onsetThreshold},")
        append("\"frameThreshold\":${p.frameThreshold},")
        append("\"minNoteLen\":${p.minNoteLen},")
        append("\"energyTol\":${p.energyTol},")
        append("\"program\":${p.program},")
        append("\"velocityCompress\":${p.velocityCompress},")
        append("\"useMelodiaTrick\":${p.useMelodiaTrick},")
        append("\"includePitchBends\":${p.includePitchBends},")
        append("\"tempoBpm\":${p.tempoBpm},")
        append("\"quantize\":${p.quantize},")
        append("\"toleranceMs\":${p.toleranceMs},")
        append("\"harmonizeMerge\":${p.harmonizeMerge},")
        append("\"minBendBins\":${p.minBendBins},")
        append("\"globalShift\":${p.globalShift},")
        append("\"modeSnap\":${p.modeSnap}")
    }
    return "{\"file\":${s(wavName)},\"params\":{$params}," +
            "\"key\":${s(key?.name ?: "")},\"report\":${s(report)}}"
}

/** Make note events well-formed: a note may start (Note-on) only when it is
 *  not already sounding, and may end (Note-off / Note-on with velocity 0)
 *  only when it is sounding. The engine can emit a repeated Note-on of a
 *  sounding pitch and a Note-off of a silent pitch when harmonize-merge
 *  stitches fragments; MuseScore misreads the tempo of such files (e.g. 95
 *  BPM shown as 119). Other events are copied verbatim, with full status
 *  bytes (no running status). Returns the input unchanged if it is not a
 *  valid SMF. */
fun normalizeMidi(midi: ByteArray): ByteArray {
    if (midi.size < 14 || midi[0] != 'M'.code.toByte() || midi[1] != 'T'.code.toByte() ||
        midi[2] != 'h'.code.toByte() || midi[3] != 'd'.code.toByte()) return midi
    val hlen = ((midi[4].toInt() and 0xFF) shl 24) or ((midi[5].toInt() and 0xFF) shl 16) or
        ((midi[6].toInt() and 0xFF) shl 8) or (midi[7].toInt() and 0xFF)
    if (hlen < 6 || 8 + hlen > midi.size) return midi
    val ntrks = ((midi[10].toInt() and 0xFF) shl 8) or (midi[11].toInt() and 0xFF)
    if (ntrks == 0) return midi

    val out = ByteArrayOutputStream()
    out.write(midi, 0, 8 + hlen) // header unchanged

    var p = 8 + hlen
    for (t in 0 until ntrks) {
        if (p + 8 > midi.size) return midi
        if (midi[p] != 'M'.code.toByte() || midi[p + 1] != 'T'.code.toByte() ||
            midi[p + 2] != 'r'.code.toByte() || midi[p + 3] != 'k'.code.toByte()) return midi
        val tlen = ((midi[p + 4].toInt() and 0xFF) shl 24) or ((midi[p + 5].toInt() and 0xFF) shl 16) or
            ((midi[p + 6].toInt() and 0xFF) shl 8) or (midi[p + 7].toInt() and 0xFF)
        p += 8
        val end = p + tlen
        if (end > midi.size) return midi
        val body = ByteArrayOutputStream()
        val active = HashSet<Int>() // (channel shl 8) or pitch
        var running = 0
        var droppedDelta = 0 // tick distance of dropped events, paid forward to the next kept one
        while (p < end) {
            val deltaStart = p
            while (p < end && (midi[p].toInt() and 0x80) != 0) p++
            if (p >= end) break
            p++ // last delta byte
            var st = midi[p].toInt() and 0xFF
            val stPos = p
            if (st and 0x80 == 0) {
                if (running == 0) break
                st = running // data byte reused as status: the note data starts here
            } else {
                running = st
                p++
            }
            val dataStart = p
            val type = st and 0xF0
            val chan = st and 0x0F
            var drop = false
            when (type) {
                0x80, 0x90 -> {
                    val note = midi[p].toInt() and 0xFF
                    val vel = midi[p + 1].toInt() and 0xFF
                    val key = (chan shl 8) or note
                    if (type == 0x90 && vel > 0) drop = !active.add(key) // repeated attack
                    else drop = !active.remove(key) // stray release
                    p += 2
                }
                0xA0, 0xB0, 0xE0 -> p += 2
                0xC0, 0xD0 -> p += 1
                0xF0 -> {
                    if (st == 0xFF) p++ // meta type byte
                    var ml = 0
                    while (p < end && (midi[p].toInt() and 0x80) != 0) {
                        ml = (ml shl 7) or (midi[p].toInt() and 0x7F)
                        p++
                    }
                    if (p >= end) break
                    ml = (ml shl 7) or (midi[p].toInt() and 0x7F)
                    p++
                    p += ml
                }
                else -> {} // 0xF1..0xF6 system messages: no data
            }
            if (p > end) break
            if (drop) {
                // Keep the event's own delta-time: skipping it would pull every
                // following note earlier and misalign bars (MuseScore then
                // recalculates the tempo from the notes).
                droppedDelta += readVlq(midi, deltaStart)
            } else {
                writeVlq(body, readVlq(midi, deltaStart) + droppedDelta)
                droppedDelta = 0
                if (stPos == dataStart) { // running status: re-insert the full status
                    body.write(st)
                    body.write(midi, dataStart, p - dataStart)
                } else {
                    body.write(midi, stPos, p - stPos)
                }
            }
        }
        writeVlq(body, droppedDelta) // delta-time before end-of-track
        body.write(0xFF); body.write(0x2F); body.write(0x00)
        out.write('M'.code); out.write('T'.code); out.write('r'.code); out.write('k'.code)
        val len = body.size()
        out.write((len shr 24) and 0xFF); out.write((len shr 16) and 0xFF)
        out.write((len shr 8) and 0xFF); out.write(len and 0xFF)
        out.write(body.toByteArray())
        p = end
    }
    return out.toByteArray()
}

/** Append an extra track with a Sequencer Specific meta event (FF 7F)
 *  carrying the JSON; returns a new valid SMF file. */
fun midiWithMetaTrack(midi: ByteArray, json: String): ByteArray {
    val data = json.toByteArray(Charsets.UTF_8)
    val body = ByteArrayOutputStream()
    body.write(0xFF); body.write(0x7F)
    var l = data.size
    val vlq = IntArray(4)
    var i = 3
    vlq[i] = l and 0x7F
    l = l shr 7
    while (l > 0) {
        i--
        vlq[i] = (l and 0x7F) or 0x80
        l = l shr 7
    }
    for (k in i until 4) body.write(vlq[k])
    body.write(data)
    body.write(0xFF); body.write(0x2F); body.write(0x00) // end of track

    val track = ByteArrayOutputStream()
    track.write(0x4D); track.write(0x54); track.write(0x72); track.write(0x6B) // MTrk
    val len = body.size()
    track.write((len shr 24) and 0xFF); track.write((len shr 16) and 0xFF)
    track.write((len shr 8) and 0xFF); track.write(len and 0xFF)
    track.write(body.toByteArray())

    val out = ByteArrayOutputStream()
    out.write(midi, 0, 10)
    val ntrks = ((midi[10].toInt() and 0xFF) shl 8) or (midi[11].toInt() and 0xFF)
    out.write((ntrks + 1) shr 8)
    out.write((ntrks + 1) and 0xFF)
    out.write(midi, 12, midi.size - 12)
    out.write(track.toByteArray())
    return out.toByteArray()
}

/** Variable-length quantity at [from]; the caller guarantees a valid event. */
private fun readVlq(b: ByteArray, from: Int): Int {
    var i = from
    var v = 0
    while (true) {
        val x = b[i].toInt() and 0xFF
        v = (v shl 7) or (x and 0x7F)
        i++
        if (x and 0x80 == 0) return v
    }
}

private fun writeVlq(out: ByteArrayOutputStream, v0: Int) {
    var v = v0
    val bytes = IntArray(4)
    var i = 3
    bytes[i] = v and 0x7F
    v = v ushr 7
    while (v > 0) {
        i--
        bytes[i] = (v and 0x7F) or 0x80
        v = v ushr 7
    }
    for (k in i until 4) out.write(bytes[k])
}
