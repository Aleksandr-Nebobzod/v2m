package com.v2m.app

import java.io.ByteArrayOutputStream

/** General MIDI Level 1 instruments, grouped by family; numbers shown 1..128
 *  (GM program numbers are 0..127 — subtract 1 when writing to MIDI). */
val GM_INSTRUMENTS: List<Pair<String, List<Pair<Int, String>>>> = listOf(
    "Пианино" to listOf(
        1 to "Концертный рояль", 2 to "Яркое фортепиано", 3 to "Электронный рояль",
        4 to "Расстроенное пианино из бара", 5 to "Электропиано 1", 6 to "Электропиано 2",
        7 to "Клавесин", 8 to "Клавинет",
    ),
    "Хроматическая перкуссия" to listOf(
        9 to "Челеста", 10 to "Колокольчики", 11 to "Музыкальная шкатулка",
        12 to "Вибрафон", 13 to "Маримба", 14 to "Ксилофон", 15 to "Колокола", 16 to "Цимбалы",
    ),
    "Орган" to listOf(
        17 to "Орган Хаммонда", 18 to "Перкуссионный орган", 19 to "Рок-орган",
        20 to "Церковный орган", 21 to "Язычковый орган", 22 to "Аккордеон",
        23 to "Гармоника", 24 to "Бандонеон",
    ),
    "Гитара" to listOf(
        25 to "Акустическая гитара 1", 26 to "Акустическая гитара 2",
        27 to "Электрогитара (джаз)", 28 to "Электрогитара (чистый звук)",
        29 to "Электрогитара (с приемом palm mute)", 30 to "Перегруженная электрогитара",
        31 to "Дисторшн-электрогитара", 32 to "Гитарные флажолеты",
    ),
    "Бас" to listOf(
        33 to "Акустический бас", 34 to "Электрическая бас-гитара (палец)",
        35 to "Электрическая бас-гитара (медиатор)", 36 to "Безладовый бас",
        37 to "Слэп-бас 1", 38 to "Слэп-бас 2", 39 to "Синтезаторный бас 1", 40 to "Синтезаторный бас 2",
    ),
    "Струнные инструменты" to listOf(
        41 to "Скрипка", 42 to "Альт", 43 to "Виолончель", 44 to "Контрабас",
        45 to "Скрипичное тремоло", 46 to "Скрипичное пиццикато", 47 to "Арфа", 48 to "Литавры",
    ),
    "Музыкальный коллектив" to listOf(
        49 to "Струнный оркестр 1", 50 to "Струнный оркестр 2",
        51 to "Синтезаторный оркестр 1", 52 to "Синтезаторный оркестр 2",
        53 to "Хор, поющий «А»", 54 to "Голос, поющий «О»", 55 to "Синтезаторный хор",
        56 to "Оркестровый акцент",
    ),
    "Медные духовые инструменты" to listOf(
        57 to "Труба", 58 to "Тромбон", 59 to "Туба", 60 to "Приглушенная труба",
        61 to "Валторна", 62 to "Духовой оркестр", 63 to "Синтезаторные духовые 1",
        64 to "Синтезаторные духовые 2",
    ),
    "Язычковые духовые инструменты" to listOf(
        65 to "Сопрано-саксофон", 66 to "Альт-саксофон", 67 to "Тенор-саксофон",
        68 to "Баритон-саксофон", 69 to "Гобой", 70 to "Английский рожок",
        71 to "Фагот", 72 to "Кларнет",
    ),
    "Деревянные духовые инструменты" to listOf(
        73 to "Пикколо", 74 to "Флейта", 75 to "Блокфлейта", 76 to "Флейта Пана",
        77 to "Бутылочные горлышки", 78 to "Сякухати", 79 to "Свисток", 80 to "Окарина",
    ),
    "Синтезаторный ведущий голос" to listOf(
        81 to "Ведущий голос 1 (меандр)", 82 to "Ведущий голос 2 (пилообразная волна)",
        83 to "Ведущий голос 3 (каллиопа)", 84 to "Ведущий голос 4 (чиффер)",
        85 to "Ведущий голос 5 (чаранг)", 86 to "Ведущий голос 6 (голос)",
        87 to "Ведущий голос 7 (квинта)", 88 to "Ведущий голос 8 (бас и ведущий голос)",
    ),
    "Синтезаторный подголосок" to listOf(
        89 to "Подголосок 1 (Нью Эйдж, или «Фантасия»)", 90 to "Подголосок 2 (теплый звук)",
        91 to "Подголосок 3 (полисинтезатор)", 92 to "Подголосок 4 (хор, или «Space Voice»)",
        93 to "Подголосок 5 (искривленный звук)", 94 to "Подголосок 6 (металлический звук)",
        95 to "Подголосок 7 (гало)", 96 to "Подголосок 8 (свип)",
    ),
    "Синтезаторные эффекты" to listOf(
        97 to "FX 1 (дождь)", 98 to "FX 2 (саундтрэк)", 99 to "FX 3 (кристалл)",
        100 to "FX 4 (атмосфера)", 101 to "FX 5 (яркость)", 102 to "FX 6 (гоблины)",
        103 to "FX 7 (эхо)", 104 to "FX 8 (сай-фай)",
    ),
    "Этнические музыкальные инструменты" to listOf(
        105 to "Ситар", 106 to "Банджо", 107 to "Сямисэн", 108 to "Кото",
        109 to "Калимба", 110 to "Волынка", 111 to "Фиддл", 112 to "Шахнай",
    ),
    "Ударные музыкальные инструменты" to listOf(
        113 to "Медные колокольчики", 114 to "Агого", 115 to "Стальные барабаны",
        116 to "Деревянная коробочка", 117 to "Тайко", 118 to "Мелодичный том-том",
        119 to "Электробарабаны", 120 to "Реверсивная крэш-тарелка",
    ),
    "Звуковые эффекты" to listOf(
        121 to "Шум гитарных струн", 122 to "Дыхание", 123 to "Шум прибоя",
        124 to "Птичья трель", 125 to "Телефонный звонок", 126 to "Шум вертолета",
        127 to "Аплодисменты", 128 to "Выстрел",
    ),
)

/** Apply export-time overrides to a MIDI file: the first tempo meta event
 *  (FF 51) is rewritten to [tempoBpm] (µs/beat), the first time-signature
 *  meta event (FF 58) to [tsNum]/[tsDen], the first key-signature meta event
 *  (FF 59) to [key] (sf = fifths — for minor keys already relative to the
 *  major — and mi = 1 for the minor mode); events missing from all tracks
 *  are inserted at the start of the first track. Nulls leave the file
 *  untouched. Returns the input unchanged when it is not a valid SMF. */
fun finalizeMidi(midi: ByteArray, tempoBpm: Double?, ts: Pair<Int, Int>?, key: KeyInfo? = null): ByteArray {
    val tempoOk = tempoBpm != null && tempoBpm > 0.0 && tempoBpm <= 300.0
    if (!tempoOk && ts == null && key == null) return midi
    if (midi.size < 14 || midi[0] != 'M'.code.toByte() || midi[1] != 'T'.code.toByte() ||
        midi[2] != 'h'.code.toByte() || midi[3] != 'd'.code.toByte()) return midi
    val hlen = ((midi[4].toInt() and 0xFF) shl 24) or ((midi[5].toInt() and 0xFF) shl 16) or
        ((midi[6].toInt() and 0xFF) shl 8) or (midi[7].toInt() and 0xFF)
    if (hlen < 6 || 8 + hlen > midi.size) return midi
    val ntrks = ((midi[10].toInt() and 0xFF) shl 8) or (midi[11].toInt() and 0xFF)
    if (ntrks == 0) return midi

    // New tempo as 3-byte µs/quarter; size as num + den_power (2 bytes),
    // the trailing clocks/32nds bytes are kept as they were.
    val tempoBytes = tempoBpm?.let { bpm ->
        val us = Math.round(60_000_000.0 / bpm).coerceIn(0, 0xFFFFFF)
        byteArrayOf((us shr 16).toByte(), (us shr 8).toByte(), us.toByte())
    }
    val sizeBytes = ts?.let { (num, den) ->
        var dd = 0
        while ((1 shl dd) < den) dd++
        byteArrayOf(num.toByte(), dd.toByte())
    }
    // FF 59: sf as a signed byte (two's complement — midicsv prints -3 for 0xFD),
    // mi = 1 for the minor mode. The fifths of a minor KeyInfo already point to
    // the relative major, exactly what the signature asks for.
    val keyBytes = key?.let { byteArrayOf(it.fifths.toByte(), if (it.modeName.contains("minor")) 1 else 0) }

    var p = 8 + hlen
    val tracks = ArrayList<ByteArray>()
    var tempoSeen = false
    var sizeSeen = false
    var keySeen = false
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
        var running = 0
        while (p < end) {
            val deltaStart = p
            while (p < end && (midi[p].toInt() and 0x80) != 0) p++
            if (p >= end) break
            p++
            body.write(midi, deltaStart, p - deltaStart)
            var st = midi[p].toInt() and 0xFF
            if (st and 0x80 != 0) { p++; running = st; body.write(st) }
            else { st = running; if (st == 0) break }
            if (st == 0xFF) {
                if (p >= end) break
                val mt = midi[p].toInt() and 0xFF; p++
                body.write(mt)
                var l = 0
                while (p < end) {
                    val b = midi[p].toInt() and 0xFF; p++
                    body.write(b)
                    l = (l shl 7) or (b and 0x7F)
                    if (b and 0x80 == 0) break
                }
                if (l < 0 || p + l > end) break
                when {
                    mt == 0x51 && l >= 3 && tempoBytes != null && !tempoSeen -> {
                        body.write(tempoBytes); p += l; tempoSeen = true
                    }
                    mt == 0x58 && l >= 4 && sizeBytes != null && !sizeSeen -> {
                        body.write(sizeBytes[0].toInt()); body.write(sizeBytes[1].toInt())
                        body.write(midi, p + 2, l - 2); p += l; sizeSeen = true
                    }
                    mt == 0x59 && l >= 2 && keyBytes != null && !keySeen -> {
                        body.write(keyBytes[0].toInt()); body.write(keyBytes[1].toInt())
                        p += l; keySeen = true
                    }
                    else -> { body.write(midi, p, l); p += l }
                }
                if (mt == 0x2F) break
            } else if (st and 0xF0 == 0xF0) {
                var l = 0
                while (p < end) {
                    val b = midi[p].toInt() and 0xFF; p++
                    body.write(b)
                    l = (l shl 7) or (b and 0x7F)
                    if (b and 0x80 == 0) break
                }
                if (l < 0 || p + l > end) break
                body.write(midi, p, l); p += l
            } else {
                val data = when (st and 0xF0) {
                    0xC0, 0xD0 -> 1
                    0x80, 0x90, 0xA0, 0xB0, 0xE0 -> 2
                    else -> 0
                }
                if (p + data > end) break
                body.write(midi, p, data); p += data
            }
        }
        tracks += body.toByteArray()
        p = end
    }

    // Missing events: prepend to the first track at tick 0.
    val insert = ByteArrayOutputStream()
    if (tempoBytes != null && !tempoSeen) { insert.write(0x00); insert.write(0xFF); insert.write(0x51); insert.write(0x03); insert.write(tempoBytes) }
    if (sizeBytes != null && !sizeSeen) { insert.write(0x00); insert.write(0xFF); insert.write(0x58); insert.write(0x04); insert.write(sizeBytes); insert.write(0x18); insert.write(0x08) }
    if (keyBytes != null && !keySeen) { insert.write(0x00); insert.write(0xFF); insert.write(0x59); insert.write(0x02); insert.write(keyBytes) }
    val prefix = insert.toByteArray()

    val out = ByteArrayOutputStream()
    out.write(midi, 0, 8 + hlen)
    for ((t, data) in tracks.withIndex()) {
        val payload = if (t == 0 && prefix.isNotEmpty()) {
            ByteArray(prefix.size + data.size).also { nb ->
                System.arraycopy(prefix, 0, nb, 0, prefix.size)
                System.arraycopy(data, 0, nb, prefix.size, data.size)
            }
        } else data
        out.write(0x4D); out.write(0x54); out.write(0x72); out.write(0x6B) // MTrk
        val l = payload.size
        out.write((l shr 24) and 0xFF); out.write((l shr 16) and 0xFF)
        out.write((l shr 8) and 0xFF); out.write(l and 0xFF)
        out.write(payload)
    }
    return out.toByteArray()
}

/** Rewrite the tempo and/or transpose the note pitches of a standard MIDI
 *  file (running status understood). [tempoBpm] rewrites every tempo meta
 *  event (FF 51) — the file then plays at exactly this tempo; a file with no
 *  tempo event gets one prepended to the first track. [shift] remaps the
 *  pitch byte of every note event (0x80 and 0x90, zero-velocity note-offs
 *  included). Used by the sound-sample buttons: the count-in clicks play at
 *  the tempo-slider value, the tonica sample is transposed into the chosen
 *  key. Non-SMF input is returned unchanged. */
fun rewriteSample(midi: ByteArray, tempoBpm: Double? = null, shift: ((Int) -> Int)? = null): ByteArray {
    val tempoBytes = tempoBpm?.let { bpm ->
        val us = Math.round(60_000_000.0 / bpm).coerceIn(0, 0xFFFFFF)
        byteArrayOf((us shr 16).toByte(), (us shr 8).toByte(), us.toByte())
    }
    if (tempoBytes == null && shift == null) return midi
    if (midi.size < 14 || midi[0] != 'M'.code.toByte() || midi[1] != 'T'.code.toByte() ||
        midi[2] != 'h'.code.toByte() || midi[3] != 'd'.code.toByte()) return midi
    val hlen = ((midi[4].toInt() and 0xFF) shl 24) or ((midi[5].toInt() and 0xFF) shl 16) or
        ((midi[6].toInt() and 0xFF) shl 8) or (midi[7].toInt() and 0xFF)
    if (hlen < 6 || 8 + hlen > midi.size) return midi
    val ntrks = ((midi[10].toInt() and 0xFF) shl 8) or (midi[11].toInt() and 0xFF)
    if (ntrks == 0) return midi

    var p = 8 + hlen
    val tracks = ArrayList<ByteArray>()
    var tempoSeen = false
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
        var running = 0
        while (p < end) {
            val deltaStart = p
            while (p < end && (midi[p].toInt() and 0x80) != 0) p++
            if (p >= end) break
            p++
            body.write(midi, deltaStart, p - deltaStart)
            var st = midi[p].toInt() and 0xFF
            if (st and 0x80 != 0) { p++; running = st; body.write(st) }
            else { st = running; if (st == 0) break }
            if (st == 0xFF) {
                if (p >= end) break
                val mt = midi[p].toInt() and 0xFF; p++
                body.write(mt)
                var l = 0
                while (p < end) {
                    val b = midi[p].toInt() and 0xFF; p++
                    body.write(b)
                    l = (l shl 7) or (b and 0x7F)
                    if (b and 0x80 == 0) break
                }
                if (l < 0 || p + l > end) break
                if (mt == 0x51 && tempoBytes != null && l >= 3) {
                    body.write(tempoBytes); p += l; tempoSeen = true
                } else {
                    body.write(midi, p, l); p += l
                }
                if (mt == 0x2F) break
            } else if (st and 0xF0 == 0xF0) {
                var l = 0
                while (p < end) {
                    val b = midi[p].toInt() and 0xFF; p++
                    body.write(b)
                    l = (l shl 7) or (b and 0x7F)
                    if (b and 0x80 == 0) break
                }
                if (l < 0 || p + l > end) break
                body.write(midi, p, l); p += l
            } else {
                val data = when (st and 0xF0) {
                    0xC0, 0xD0 -> 1
                    0x80, 0x90, 0xA0, 0xB0, 0xE0 -> 2
                    else -> 0
                }
                if (p + data > end) break
                val note = (st and 0xF0) == 0x80 || (st and 0xF0) == 0x90
                if (shift != null && note) {
                    val pitch = midi[p].toInt() and 0xFF
                    body.write(shift(pitch).coerceIn(0, 127))
                    body.write(midi[p + 1].toInt() and 0xFF)
                } else {
                    body.write(midi, p, data)
                }
                p += data
            }
        }
        tracks += body.toByteArray()
        p = end
    }

    // A missing tempo event: prepend to the first track at tick 0.
    if (tempoBytes != null && !tempoSeen) {
        val pre = ByteArrayOutputStream()
        pre.write(0x00); pre.write(0xFF); pre.write(0x51); pre.write(0x03); pre.write(tempoBytes)
        tracks[0] = pre.toByteArray() + tracks[0]
    }

    val out = ByteArrayOutputStream()
    out.write(midi, 0, 8 + hlen)
    for (data in tracks) {
        out.write(0x4D); out.write(0x54); out.write(0x72); out.write(0x6B) // MTrk
        val l = data.size
        out.write((l shr 24) and 0xFF); out.write((l shr 16) and 0xFF)
        out.write((l shr 8) and 0xFF); out.write(l and 0xFF)
        out.write(data)
    }
    return out.toByteArray()
}

/** Transpose a tonica-like sample into [key]: its root (the lowest note)
 *  lands on 60 + rootPc (middle octave); in a minor key the major thirds
 *  above the root are lowered a semitone («понижать терцию» — решение А.М.,
 *  as in the former synthesized previews). */
fun transposeSample(midi: ByteArray, key: KeyInfo): ByteArray {
    val notes = runCatching { parseMidiSong(midi).notes }.getOrNull() ?: return midi
    val root = notes.minOfOrNull { it.pitch } ?: return midi
    val delta = 60 + key.rootPc - root
    val minor = key.modeName.contains("minor")
    return rewriteSample(midi, shift = { n ->
        n + delta - if (minor && (n - root).mod(12) == 4) 1 else 0
    })
}

/** Name of the instrument with GM number [gm01] (1..128), or "?" when unknown. */
fun gmName(gm01: Int): String =
    GM_INSTRUMENTS.asSequence().flatMap { it.second.asSequence() }
        .firstOrNull { it.first == gm01 }?.second ?: "?"

/** Replace the first program change (any channel) in the MIDI file with
 *  [program] (1..128, GM numbering); when the file has none, insert one at the
 *  start of the first note-bearing track. Returns a new valid SMF, or the
 *  input unchanged when it is not a valid SMF. */
fun patchProgram(midi: ByteArray, program: Int): ByteArray {
    val prog = (program - 1).coerceIn(0, 127)
    if (midi.size < 14) return midi
    if (midi[0] != 'M'.code.toByte() || midi[1] != 'T'.code.toByte() ||
        midi[2] != 'h'.code.toByte() || midi[3] != 'd'.code.toByte()) return midi
    val hlen = ((midi[4].toInt() and 0xFF) shl 24) or ((midi[5].toInt() and 0xFF) shl 16) or
        ((midi[6].toInt() and 0xFF) shl 8) or (midi[7].toInt() and 0xFF)
    if (hlen < 6 || 8 + hlen > midi.size) return midi
    val ntrks = ((midi[10].toInt() and 0xFF) shl 8) or (midi[11].toInt() and 0xFF)

    // Single cursor over the file (the previous version advanced two cursors
    // in parallel and produced a corrupt output).
    var p = 8 + hlen
    val tracks = ArrayList<ByteArray>()
    var replaced = false
    var insertIdx = -1 // first note-bearing track while no program change found
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
        var hasNotes = false
        var running = 0
        while (p < end) {
            val deltaStart = p
            while (p < end && (midi[p].toInt() and 0x80) != 0) p++ // VLQ continuation bytes
            if (p >= end) break
            p++ // last delta byte (bit7 = 0)
            body.write(midi, deltaStart, p - deltaStart)
            var st = midi[p].toInt() and 0xFF
            if (st and 0x80 != 0) { p++; running = st; body.write(st) }
            else { st = running; if (st == 0) break } // running status (status omitted)
            when (st and 0xF0) {
                0x80, 0x90 -> {
                    if (p + 2 > end) break
                    if (st and 0xF0 == 0x90 && (midi[p + 1].toInt() and 0xFF) > 0) hasNotes = true
                    body.write(midi, p, 2); p += 2
                }
                0xA0, 0xB0, 0xE0 -> {
                    if (p + 2 > end) break
                    body.write(midi, p, 2); p += 2
                }
                0xC0, 0xD0 -> {
                    if (p >= end) break
                    if (st and 0xF0 == 0xC0 && !replaced) { body.write(prog); replaced = true }
                    else body.write(midi[p].toInt())
                    p++
                }
                0xF0 -> {
                    if (st == 0xFF) { // meta event: type, VLQ length, data
                        if (p >= end) break
                        val mt = midi[p].toInt() and 0xFF; p++
                        body.write(mt)
                        var l = 0
                        while (p < end) {
                            val b = midi[p].toInt() and 0xFF; p++
                            body.write(b)
                            l = (l shl 7) or (b and 0x7F)
                            if (l < 0 || b and 0x80 == 0) break // overflow or last length byte
                        }
                        if (l < 0 || p + l > end) break
                        body.write(midi, p, l); p += l
                        if (mt == 0x2F) break
                    } else if (st == 0xF0 || st == 0xF7) { // sysex: VLQ length
                        var l = 0
                        while (p < end) {
                            val b = midi[p].toInt() and 0xFF; p++
                            body.write(b)
                            l = (l shl 7) or (b and 0x7F)
                            if (l < 0 || b and 0x80 == 0) break
                        }
                        if (l < 0 || p + l > end) break
                        body.write(midi, p, l); p += l
                    } else { // 0xF1..0xF6 system messages (fixed data length)
                        val extra = when (st) { 0xF1, 0xF3 -> 1; 0xF2 -> 2; else -> 0 }
                        if (p + extra > end) break
                        body.write(midi, p, extra); p += extra
                    }
                }
                else -> break
            }
        }
        tracks += body.toByteArray()
        if (!replaced && hasNotes && insertIdx < 0) insertIdx = t
        p = end
    }

    val out = ByteArrayOutputStream()
    out.write(midi, 0, 8 + hlen) // header unchanged (incl. track count)
    for ((t, data) in tracks.withIndex()) {
        val payload = if (t == insertIdx) {
            ByteArray(data.size + 3).also { nb ->
                nb[0] = 0x00; nb[1] = 0xC0.toByte(); nb[2] = prog.toByte()
                System.arraycopy(data, 0, nb, 3, data.size)
            }
        } else data
        out.write(0x4D); out.write(0x54); out.write(0x72); out.write(0x6B) // MTrk
        val l = payload.size
        out.write((l shr 24) and 0xFF); out.write((l shr 16) and 0xFF)
        out.write((l shr 8) and 0xFF); out.write(l and 0xFF)
        out.write(payload)
    }
    return out.toByteArray()
}

/** Anacrusis (затакт): shift every time-signature meta event (FF 58)
 *  [eighths] eighth notes later — the bar grid starts at tick
 *  eighths×divisions/2, so measure boundaries move right — and insert an
 *  opening partial signature of [eighths]/8 at tick 0. Notes are not moved
 *  (only the bar boundaries shift; agreed semantics). [eighths] 0 = no-op.
 *  Returns the input unchanged when [eighths] is out of 1..8 or the file
 *  is not a valid SMF with a time signature. */
fun anacrusisMidi(midi: ByteArray, eighths: Int): ByteArray {
    if (eighths < 1 || eighths > 8) return midi
    if (midi.size < 14 || midi[0] != 'M'.code.toByte() || midi[1] != 'T'.code.toByte() ||
        midi[2] != 'h'.code.toByte() || midi[3] != 'd'.code.toByte()) return midi
    val hlen = ((midi[4].toInt() and 0xFF) shl 24) or ((midi[5].toInt() and 0xFF) shl 16) or
        ((midi[6].toInt() and 0xFF) shl 8) or (midi[7].toInt() and 0xFF)
    if (hlen < 6 || 8 + hlen > midi.size) return midi
    val ntrks = ((midi[10].toInt() and 0xFF) shl 8) or (midi[11].toInt() and 0xFF)
    if (ntrks == 0) return midi
    val divisions = ((midi[12].toInt() and 0xFF) shl 8) or (midi[13].toInt() and 0xFF)
    if (divisions <= 0) return midi
    val shift = eighths * divisions / 2

    // Event boundaries of one track: (deltaStart, eventEnd, delta value, FF58?)
    fun vlqLen(b: ByteArray, from: Int): Int {
        var n = 0
        while (from + n < b.size && (b[from + n].toInt() and 0x80) != 0) n++
        return n + 1
    }

    var p = 8 + hlen
    var firstTrack = -1
    val trackBodies = ArrayList<ByteArray>()
    for (t in 0 until ntrks) {
        if (p + 8 > midi.size) return midi
        if (midi[p] != 'M'.code.toByte() || midi[p + 1] != 'T'.code.toByte() ||
            midi[p + 2] != 'r'.code.toByte() || midi[p + 3] != 'k'.code.toByte()) return midi
        val tlen = ((midi[p + 4].toInt() and 0xFF) shl 24) or ((midi[p + 5].toInt() and 0xFF) shl 16) or
            ((midi[p + 6].toInt() and 0xFF) shl 8) or (midi[p + 7].toInt() and 0xFF)
        p += 8
        val end = p + tlen
        if (end > midi.size) return midi
        val events = ArrayList<Triple<Int, Int, Boolean>>() // deltaStart, eventEnd, isFF58
        var q = p
        var running = 0
        while (q < end) {
            val dStart = q
            while (q < end) {
                val b = midi[q].toInt() and 0xFF
                if (b and 0x80 == 0) break
                q++
            }
            if (q >= end) break
            q++
            var st = midi[q].toInt() and 0xFF
            if (st and 0x80 != 0) { q++; running = st } else st = running
            var isFF58 = false
            var eventEnd = q
            if (st == 0xFF) {
                val mt = midi[q].toInt() and 0xFF; q++
                var l = 0
                while (q < end) {
                    val b = midi[q].toInt() and 0xFF; q++
                    l = (l shl 7) or (b and 0x7F)
                    if (b and 0x80 == 0) break
                }
                if (q + l > end) return midi
                q += l
                eventEnd = q
                isFF58 = mt == 0x58
                if (mt == 0x2F) { events.add(Triple(dStart, eventEnd, isFF58)); break }
            } else if (st and 0xF0 == 0xF0) {
                var l = 0
                while (q < end) {
                    val b = midi[q].toInt() and 0xFF; q++
                    l = (l shl 7) or (b and 0x7F)
                    if (b and 0x80 == 0) break
                }
                if (q + l > end) return midi
                q += l
                eventEnd = q
            } else {
                val data = when (st and 0xF0) {
                    0xC0, 0xD0 -> 1
                    0x80, 0x90, 0xA0, 0xB0, 0xE0 -> 2
                    else -> 0
                }
                if (q + data > end) return midi
                q += data
                eventEnd = q
            }
            events.add(Triple(dStart, eventEnd, isFF58))
            if (q >= end) break
        }
        if (events.isEmpty()) return midi
        // Rebuild: shift every FF58 delta by [shift]; the first FF58 gets an
        // opening partial signature inserted before it.
        val body = ByteArrayOutputStream()
        for ((dStart, eEnd, isTs) in events) {
            val dLen = vlqLen(midi, dStart)
            var d = 0
            for (i in dStart until dStart + dLen) d = (d shl 7) or (midi[i].toInt() and 0x7F)
            if (isTs) {
                if (firstTrack < 0) {
                    firstTrack = t
                    // partial signature: N/8, 24 clocks, 8 32nds — the shape
                    // MuseScore renders as an anacrusis measure
                    body.write(0x00); body.write(0xFF); body.write(0x58); body.write(0x04)
                    body.write(eighths); body.write(0x03); body.write(0x18); body.write(0x08)
                }
                writeVlq(body, d + shift)
                body.write(midi, dStart + dLen, eEnd - dStart - dLen)
            } else {
                body.write(midi, dStart, eEnd - dStart)
            }
        }
        trackBodies += body.toByteArray()
        p = end
    }
    if (firstTrack < 0) return midi // no time signature: nothing to shift

    val out = ByteArrayOutputStream()
    out.write(midi, 0, 8 + hlen)
    for ((t, data) in trackBodies.withIndex()) {
        out.write(0x4D); out.write(0x54); out.write(0x72); out.write(0x6B) // MTrk
        val l = data.size
        out.write((l shr 24) and 0xFF); out.write((l shr 16) and 0xFF)
        out.write((l shr 8) and 0xFF); out.write(l and 0xFF)
        out.write(data)
    }
    return out.toByteArray()
}

private fun writeVlq(out: ByteArrayOutputStream, v0: Int) {
    var v = v0
    val stack = ArrayList<Int>()
    stack.add(v and 0x7F)
    v = v ushr 7
    while (v > 0) {
        stack.add((v and 0x7F) or 0x80)
        v = v ushr 7
    }
    for (i in stack.indices.reversed()) out.write(stack[i])
}
