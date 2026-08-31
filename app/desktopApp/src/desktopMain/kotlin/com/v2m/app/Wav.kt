package com.v2m.app

import java.io.File
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
