package com.v2m.app

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Read a PCM WAV file into mono float samples (-1..1) plus the sample rate.
 *  Supports 8/16-bit PCM, any channel count (downmixed to mono). */
fun readWavMono(file: File): Pair<FloatArray, Int> {
    val bytes = file.readBytes()
    if (bytes.size < 44) throw IllegalArgumentException("файл слишком мал для WAV")
    fun fourcc(off: Int) = bytes.copyOfRange(off, off + 4).toString(Charsets.US_ASCII)
    if (fourcc(0) != "RIFF") throw IllegalArgumentException("не RIFF (WAV)")
    if (fourcc(8) != "WAVE") throw IllegalArgumentException("не WAVE")
    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val channels = bb.getShort(22).toInt() and 0xFFFF
    val sampleRate = bb.getInt(24)
    val bitsPerSample = bb.getShort(34).toInt() and 0xFFFF
    if (channels < 1 || bitsPerSample !in setOf(8, 16)) {
        throw IllegalArgumentException("поддерживается PCM 8/16 бит, каналы 1+ (у файла: $bitsPerSample бит, $channels кан.)")
    }

    var off = 12
    var dataOff = -1
    var dataLen = 0
    while (off + 8 <= bytes.size) {
        val id = bb.getInt(off)
        val len = bb.getInt(off + 4)
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

/** Длительность WAV в секундах — читается только заголовок (для таймера
 *  покоя при загруженном внешнем файле, замечание «б» приёмки #44);
 *  null — не WAV или нет data-чанка. */
fun wavDurationSec(file: File): Int? {
    return try {
        RandomAccessFile(file, "r").use { raf ->
            val bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            fun readTag(): String {
                raf.readFully(bb.array(), 0, 4)
                return String(bb.array(), 0, 4, Charsets.US_ASCII)
            }
            fun readInt(): Int {
                raf.readFully(bb.array(), 0, 4)
                return bb.getInt(0)
            }
            raf.readFully(bb.array(), 0, 12)
            if (String(bb.array(), 0, 4, Charsets.US_ASCII) != "RIFF" ||
                String(bb.array(), 8, 4, Charsets.US_ASCII) != "WAVE"
            ) return@use null
            var byteRate = 0 // fmt ещё не встречен — данные до него не считать
            while (raf.filePointer + 8 <= raf.length()) {
                val id = readTag()
                val len = readInt()
                if (id == "fmt ") {
                    raf.seek(raf.filePointer + 12) // off+20: byteRate (после audioFormat, channels, sampleRate)
                    byteRate = readInt()
                } else if (id == "data") {
                    return@use if (byteRate > 0) len / byteRate else null
                }
                raf.seek(raf.filePointer + len + (len and 1)) // паддинг до чётной границы
            }
            null
        }
    } catch (e: Exception) {
        null
    }
}

/** Записать моно PCM как RIFF/WAVE 16 бит (Р14: сохранение записи с микрофона). */
fun writeWavMono(file: File, pcm: FloatArray, sr: Int) {
    val data = ByteArray(pcm.size * 2)
    var i = 0
    for (v in pcm) {
        val s = (v.coerceIn(-1f, 1f) * 32767f).toInt()
        data[i++] = (s and 0xFF).toByte()
        data[i++] = ((s shr 8) and 0xFF).toByte()
    }
    val out = ByteArrayOutputStream(44 + data.size)
    fun u16(v: Int) {
        out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
    }
    fun u32(v: Int) {
        u16(v and 0xFFFF); u16(v shr 16)
    }
    fun tag(s: String) { for (c in s) out.write(c.code) }
    tag("RIFF"); u32(36 + data.size); tag("WAVE")
    tag("fmt "); u32(16); u16(1); u16(1); u32(sr); u32(sr * 2); u16(2); u16(16)
    tag("data"); u32(data.size); out.write(data)
    file.writeBytes(out.toByteArray())
}
