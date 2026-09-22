package com.v2m.app

import java.io.ByteArrayOutputStream

/** Правка высоты одной ноты SMF-файла (редактор «Тоны», билд #35):
 *  события NoteOn/NoteOff [pitch] канала [channel] с тиками в
 *  [startTick..endTick] сдвигаются на [deltaPitch] полутонов, а
 *  питч-бенды канала в тиках [startTick, endTick) выбрасываются —
 *  микротон ноты сбрасывается (А.М. 2026-09-06: первое же нажатие [<]/[>]
 *  на микротон-ноте даёт чистый тон: [<] на C+32% — deltaPitch 0,
 *  [>] — deltaPitch +1; у чистых нот оба шага хроматические).
 *
 *  Возвращает исходный массив, если событий ноты нет (правок не было) —
 *  тогда App не создаёт новую версию. Тики — абсолютные в пределах трека,
 *  как ключи слоёв muted/выделения; для формата 1 с нотами не в первом
 *  треке возможны расхождения (!ai). */
fun retuneNoteMidi(
    midi: ByteArray,
    startTick: Long,
    endTick: Long,
    pitch: Int,
    channel: Int,
    deltaPitch: Int,
): ByteArray {
    if (midi.size < 14 || midi[0] != 'M'.code.toByte() || midi[1] != 'T'.code.toByte() ||
        midi[2] != 'h'.code.toByte() || midi[3] != 'd'.code.toByte()) return midi
    val hlen = ((midi[4].toInt() and 0xFF) shl 24) or ((midi[5].toInt() and 0xFF) shl 16) or
        ((midi[6].toInt() and 0xFF) shl 8) or (midi[7].toInt() and 0xFF)
    if (hlen < 6 || 8 + hlen > midi.size) return midi
    val ntrks = ((midi[10].toInt() and 0xFF) shl 8) or (midi[11].toInt() and 0xFF)
    if (ntrks == 0) return midi

    val out = ByteArrayOutputStream()
    out.write(midi, 0, 8 + hlen) // header unchanged
    var changed = false

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
        var tick = 0L
        var running = 0
        var droppedDelta = 0 // tick distance of dropped bend events, paid forward
        while (p < end) {
            val deltaStart = p
            while (p < end && (midi[p].toInt() and 0x80) != 0) p++
            if (p >= end) break
            p++ // last delta byte
            val delta = readVlq(midi, deltaStart)
            tick += delta
            var st = midi[p].toInt() and 0xFF
            val stPos = p
            if (st and 0x80 == 0) {
                if (running == 0) break
                st = running // data byte reused as status
            } else {
                running = st
                p++
            }
            val dataStart = p
            val type = st and 0xF0
            val chan = st and 0x0F
            var drop = false
            var note0 = 0
            var note1 = 0
            var noteChanged = false // the note event was rewritten (full status + data)
            when (type) {
                0x80, 0x90 -> {
                    var note = midi[p].toInt() and 0xFF
                    val vel = midi[p + 1].toInt() and 0xFF
                    if (chan == channel && note == pitch && tick in startTick..endTick) {
                        val shifted = (note + deltaPitch).coerceIn(0, 127)
                        if (shifted != note) {
                            note = shifted
                            changed = true
                            noteChanged = true
                            note0 = note
                            note1 = vel
                        }
                    }
                    p += 2
                }
                0xE0 -> {
                    // bend события в интервале ноты выбрасываются (сброс микротона);
                    // bend на endTick относится уже к следующей ноте и сохраняется
                    if (chan == channel && tick >= startTick && tick < endTick) {
                        drop = true
                        changed = true
                    }
                    p += 2
                }
                0xA0, 0xB0 -> p += 2
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
            if (drop) droppedDelta += delta
            else {
                writeVlq(body, delta + droppedDelta)
                droppedDelta = 0
                when {
                    noteChanged -> { body.write(st); body.write(note0); body.write(note1) }
                    stPos == dataStart -> { // running status: re-insert the full status
                        body.write(st)
                        body.write(midi, dataStart, p - dataStart)
                    }
                    else -> body.write(midi, stPos, p - stPos)
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
    return if (changed) out.toByteArray() else midi
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
