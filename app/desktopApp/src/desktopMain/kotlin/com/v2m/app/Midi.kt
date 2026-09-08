package com.v2m.app

/** Имя ноты с октавой: 60 -> "C4", 48 -> "C3", 61 -> "C#4" (MidiNote.name
 *  и строки кадровых признаков FramesSummary ссылаются на неё — единое
 *  место определения). */
fun pitchName(pitch: Int): String {
    val names = arrayOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
    return names[pitch % 12] + (pitch / 12 - 1)
}

/** A note as parsed from a standard MIDI file (for display only).
 *  [channel] — MIDI-канал события (для точных байтовых правок ноты);
 *  [cents] — средний микротон ноты (в процентах полутона, −100..+100;
 *  0 = чистый тон) — по питч-бендам канала за время звучания. */
data class MidiNote(
    val startTick: Long,
    val endTick: Long,
    val pitch: Int,
    val velocity: Int,
    val tempoUs: Long,
    val division: Int,
    val channel: Int = 0,
    val cents: Float = 0f,
) {
    val name: String get() = pitchName(pitch)
    val startSec: Double get() = tickToSec(startTick, tempoUs, division)
    val endSec: Double get() = tickToSec(endTick, tempoUs, division)
    val durationQuarters: Double get() = (endTick - startTick).toDouble() / division

    /** Отображаемый микротон: «+32%», «−40%», у чистых нот — пусто
     *  (А.М. 2026-09-06: «у чистых нот процент сдвига не указывать»). */
    val centsText: String get() {
        val pct = Math.round(cents)
        return if (pct == 0) "" else if (pct > 0) "+$pct%" else "$pct%"
    }
}

/** Key of a note for selection/mute layers: tick in the high bits, pitch in
 *  the low byte — unique per (startTick, pitch), survives re-parses. */
fun noteKeyOf(startTick: Long, pitch: Int): Long = (startTick shl 8) or pitch.toLong()
fun noteKeyOf(n: MidiNote): Long = noteKeyOf(n.startTick, n.pitch)

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

/** A display row: a note, or a rest between notes (chord notes share the tick).
 *  [startTick] — абсолютный тик начала (для слоёв muted/выделения). */
data class NoteRow(
    val measure: Int,
    val beat: Double,
    val pitch: Int,
    val durationQuarters: Double,
    val velocity: Int,
    val isRest: Boolean,
    val startSec: Double,
    val endSec: Double,
    val startTick: Long = 0L,
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
            n.startSec, n.endSec, n.startTick,
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

    // Средний микротон ноты (cents): питч-бенд канала действует с момента
    // события до следующего bend; накопление по времени звучания ноты.
    data class Active(
        val startTick: Long, val vel: Int,
        var segFrom: Long, // с какого тика действует текущий bend канала
        var accVal: Long, // Σ raw-бенда × длительность участка
        var accT: Long, // Σ длительность участков
    )
    val active = HashMap<Long, Active>() // (channel<<8)|pitch -> note
    val bendVal = IntArray(16) { 8192 } // действующий bend канала (центр по умолчанию)
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
                    val aKey = (chan.toLong() shl 8) or key.toLong()
                    if (type == 0x90 && vel > 0) {
                        active[aKey] = Active(tick, vel, tick, 0, 0)
                    } else {
                        active.remove(aKey)?.let { a ->
                            // Долить участок от последнего bend до конца ноты
                            if (tick > a.segFrom) {
                                a.accVal += bendVal[chan].toLong() * (tick - a.segFrom)
                                a.accT += tick - a.segFrom
                            }
                            val cents = if (a.accT > 0) {
                                ((a.accVal.toDouble() / a.accT - 8192.0) / 8192.0 * 200.0)
                                    .toFloat().coerceIn(-100f, 100f)
                            } else 0f
                            notes.add(MidiNote(a.startTick, tick, key, a.vel, tempoUs, division, chan, cents))
                        }
                    }
                }
                0xA0, 0xB0 -> { u8(); u8() }
                0xC0, 0xD0 -> { u8() }
                0xE0 -> { // pitch bend: value 0..16383, центр 8192
                    val lsb = u8()
                    val msb = u8()
                    val v = ((msb and 0x7F) shl 7) or (lsb and 0x7F)
                    // Накопить действующий bend по всем звучащим нотам канала
                    if (v != bendVal[chan]) {
                        for ((k, n) in active) {
                            if ((k shr 8).toInt() != chan) continue
                            if (tick > n.segFrom) {
                                n.accVal += bendVal[chan].toLong() * (tick - n.segFrom)
                                n.accT += tick - n.segFrom
                            }
                            n.segFrom = tick
                        }
                        bendVal[chan] = v
                    }
                }
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
