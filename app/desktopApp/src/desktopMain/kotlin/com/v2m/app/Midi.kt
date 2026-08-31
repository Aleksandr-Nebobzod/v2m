package com.v2m.app

/** A note as parsed from a standard MIDI file (for display only). */
data class MidiNote(
    val startTick: Long,
    val endTick: Long,
    val pitch: Int,
    val velocity: Int,
    val tempoUs: Long,
    val division: Int,
) {
    val name: String
        get() {
            val names = arrayOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
            return names[pitch % 12] + (pitch / 12 - 1)
        }
    val startSec: Double get() = tickToSec(startTick, tempoUs, division)
    val endSec: Double get() = tickToSec(endTick, tempoUs, division)
    val durationQuarters: Double get() = (endTick - startTick).toDouble() / division
}

/** Parsed song: notes plus the musical frame (tempo, time signature). */
data class SongData(
    val notes: List<MidiNote>,
    val tempoUs: Long,
    val tsNum: Int,
    val tsDen: Int,
    val division: Int,
) {
    val tempoBpm: Double get() = 60_000_000.0 / tempoUs
    private val measureLen: Long get() = tsNum.toLong() * division

    fun measureOf(tick: Long): Int = (tick / measureLen).toInt() + 1
    fun beatOf(tick: Long): Double = (tick % measureLen) / division.toDouble() + 1.0
}

private fun tickToSec(tick: Long, tempoUs: Long, division: Int): Double =
    tick.toDouble() / division * tempoUs / 1e6

/** A display row: a note, or a rest between notes (chord notes share the tick). */
data class NoteRow(
    val measure: Int,
    val beat: Double,
    val pitch: Int,
    val durationQuarters: Double,
    val velocity: Int,
    val isRest: Boolean,
    val startSec: Double,
    val endSec: Double,
)

/** Notes with rests filled in between them, in playing order. */
fun buildRows(song: SongData): List<NoteRow> {
    val rows = mutableListOf<NoteRow>()
    var prevEnd = 0L
    for (n in song.notes.sortedBy { it.startTick }) {
        if (n.startTick > prevEnd) {
            rows += NoteRow(
                song.measureOf(prevEnd), song.beatOf(prevEnd), 0,
                (n.startTick - prevEnd).toDouble() / song.division, 0, true,
                tickToSec(prevEnd, song.tempoUs, song.division),
                tickToSec(n.startTick, song.tempoUs, song.division),
            )
        }
        rows += NoteRow(
            song.measureOf(n.startTick), song.beatOf(n.startTick), n.pitch,
            n.durationQuarters, n.velocity, false,
            n.startSec, n.endSec,
        )
        prevEnd = maxOf(prevEnd, n.endTick)
    }
    return rows
}

/** Minimal SMF parser: notes with absolute ticks; first tempo is used. */
fun parseMidiSong(midi: ByteArray): SongData {
    var p = 0
    fun u8(): Int = midi[p++].toInt() and 0xFF
    fun u16(): Int { val v = (u8() shl 8) or u8(); return v }
    fun u32(): Long { var v = 0L; repeat(4) { v = (v shl 8) or u8().toLong() }; return v }
    fun vlq(): Long {
        var v = 0L
        repeat(4) {
            val b = u8()
            v = (v shl 7) or (b and 0x7f).toLong()
            if (b and 0x80 == 0) return v
        }
        return v
    }

    if (u32() != 0x4D546864L) throw IllegalArgumentException("не SMF") // MThd
    val hlen = u32()
    u16() // format (0/1/2) — not needed
    val ntrks = u16()
    val division = u16()
    if (division and 0x8000 != 0) throw IllegalArgumentException("SMPTE не поддерживается")
    repeat((hlen - 6).toInt()) { u8() }

    data class Active(val startTick: Long, val vel: Int)
    val active = HashMap<Long, Active>() // (channel<<8)|pitch -> note
    val notes = ArrayList<MidiNote>()
    var tempoUs = 500_000L
    var tsNum = 4
    var tsDen = 4

    repeat(ntrks) {
        if (u32() != 0x4D54726BL) throw IllegalArgumentException("нет MTrk") // MTrk
        val tlen = u32()
        val end = p + tlen.toInt()
        var tick = 0L
        var running = 0
        while (p < end) {
            tick += vlq()
            var st = u8()
            if (st and 0x80 == 0) {
                if (running == 0) break
                p--
                st = running
            } else {
                running = st
            }
            val type = st and 0xF0
            val chan = st and 0x0F
            val isMeta = st == 0xFF
            when (type) {
                0x80, 0x90 -> {
                    val key = u8()
                    val vel = u8()
                    if (type == 0x90 && vel > 0) {
                        active[(chan.toLong() shl 8) or key.toLong()] = Active(tick, vel)
                    } else {
                        active.remove((chan.toLong() shl 8) or key.toLong())?.let { a ->
                            notes.add(MidiNote(a.startTick, tick, key, a.vel, tempoUs, division))
                        }
                    }
                }
                0xA0, 0xB0 -> { u8(); u8() }
                0xC0, 0xD0 -> { u8() }
                0xE0 -> { u8(); u8() } // pitch bend — dropped for display
                0xF0 -> { // 0xFF meta events, 0xF0/0xF7 sysex
                    if (isMeta) {
                        val mtype = u8()
                        val mlen = vlq().toInt()
                        when {
                            mtype == 0x51 && mlen >= 3 -> { // tempo
                                tempoUs = (u8().toLong() shl 16) or (u8().toLong() shl 8) or u8().toLong()
                                repeat(mlen - 3) { u8() }
                            }
                            mtype == 0x58 && mlen >= 2 -> { // time signature
                                tsNum = u8()
                                val dd = u8()
                                if (dd in 0..6) tsDen = 1 shl dd
                                repeat(mlen - 2) { u8() }
                            }
                            else -> repeat(mlen) { u8() }
                        }
                        if (mtype == 0x2F) break
                    } else {
                        val slen = vlq().toInt() // sysex: length-prefixed data
                        repeat(slen) { u8() }
                    }
                }
                else -> { u8() } // 0xF1..0xF6 system messages — no data
            }
        }
        p = end
    }
    return SongData(notes.sortedBy { it.startTick }, tempoUs, tsNum, tsDen, division)
}
