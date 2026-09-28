package com.v2m.app

/** Разбор WAV из памяти: источник — байты (этап 4б плана Android: общий код
 *  не работает с файлами напрямую — на Android выбранный файл приходит из
 *  SAF содержимым, а не путём). Файловые версии — локальные хелперы самотеста
 *  (`desktopApp/Main.kt`), этап 4в. */
fun readWavMono(bytes: ByteArray): Pair<FloatArray, Int> {
    if (bytes.size < 44) throw IllegalArgumentException("файл слишком мал для WAV")
    fun fourcc(off: Int) = String(bytes, off, 4, Charsets.US_ASCII)
    if (fourcc(0) != "RIFF") throw IllegalArgumentException("не RIFF (WAV)")
    if (fourcc(8) != "WAVE") throw IllegalArgumentException("не WAVE")
    val channels = int16LE(bytes, 22) and 0xFFFF
    val sampleRate = int32LE(bytes, 24)
    val bitsPerSample = int16LE(bytes, 34) and 0xFFFF
    if (channels < 1 || bitsPerSample !in setOf(8, 16)) {
        throw IllegalArgumentException("поддерживается PCM 8/16 бит, каналы 1+ (у файла: $bitsPerSample бит, $channels кан.)")
    }

    var off = 12
    var dataOff = -1
    var dataLen = 0
    while (off + 8 <= bytes.size) {
        val len = int32LE(bytes, off + 4)
        if (fourcc(off) == "data") {
            dataOff = off + 8
            dataLen = len
            break
        }
        off += 8 + len + (len and 1)
    }
    if (dataOff < 0) throw IllegalArgumentException("нет data-чанка")

    val bytesPerSample = bitsPerSample / 8
    val totalFrames = dataLen / (bytesPerSample * channels)
    val floats = FloatArray(totalFrames)
    var s = dataOff
    for (f in 0 until totalFrames) {
        var sum = 0.0
        for (c in 0 until channels) {
            if (bitsPerSample == 16) {
                val lo = bytes[s].toInt() and 0xFF
                val hi = bytes[s + 1].toInt() shl 8
                sum += (lo or hi) / 32768.0
                s += 2
            } else {
                sum += ((bytes[s].toInt() and 0xFF) - 128) / 128.0
                s += 1
            }
        }
        floats[f] = (sum / channels).toFloat()
    }
    return floats to sampleRate
}

/** Длительность WAV по байтам: читаются только заголовки чанков (данные не
 *  просматриваются). */
fun wavDurationSec(bytes: ByteArray): Int? {
    if (bytes.size < 12) return null
    fun tag(off: Int) = String(bytes, off, 4, Charsets.US_ASCII)
    if (tag(0) != "RIFF" || tag(8) != "WAVE") return null
    var off = 12
    var byteRate = 0 // fmt ещё не встречен — данные до него не считать
    while (off + 8 <= bytes.size) {
        val id = tag(off)
        val len = int32LE(bytes, off + 4)
        if (id == "fmt " && off + 20 <= bytes.size) {
            byteRate = int32LE(bytes, off + 16) // byteRate в fmt-данных: audioFormat, channels, sampleRate, byteRate
        } else if (id == "data") {
            return if (byteRate > 0) len / byteRate else null
        }
        off += 8 + len + (len and 1) // паддинг до чётной границы
    }
    return null
}

/** Моно PCM (−1..1) как RIFF/WAVE 16 бит — в память (этап 4б: запись идёт в
 *  выбранное место через контракт [SaveTarget], а не по пути). */
fun encodeWavMono(pcm: FloatArray, sr: Int): ByteArray {
    val data = ByteArray(pcm.size * 2)
    var i = 0
    for (v in pcm) {
        val s = (v.coerceIn(-1f, 1f) * 32767f).toInt()
        data[i++] = (s and 0xFF).toByte()
        data[i++] = ((s shr 8) and 0xFF).toByte()
    }
    val out = ByteBuilder(44 + data.size)
    fun tag(s: String) = out.appendAscii(s)
    tag("RIFF"); out.appendI32LE(36 + data.size); tag("WAVE")
    tag("fmt "); out.appendI32LE(16); out.appendI16LE(1); out.appendI16LE(1)
    out.appendI32LE(sr); out.appendI32LE(sr * 2); out.appendI16LE(2); out.appendI16LE(16)
    tag("data"); out.appendI32LE(data.size); out.append(data)
    return out.toByteArray()
}
