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
