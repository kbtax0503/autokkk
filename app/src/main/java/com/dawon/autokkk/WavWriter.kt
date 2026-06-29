package com.dawon.autokkk

import java.io.File
import java.io.RandomAccessFile

/** 16-bit PCM mono WAV writer. 헤더는 placeholder로 쓰고, 주기적으로/종료 시 실제 크기로 갱신. */
class WavWriter(file: File, private val sampleRate: Int) {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0

    init {
        raf.setLength(0)
        raf.write(ByteArray(44)) // placeholder header
    }

    fun write(samples: ShortArray, n: Int) {
        val b = ByteArray(n * 2)
        var j = 0
        for (i in 0 until n) {
            val s = samples[i].toInt()
            b[j++] = (s and 0xFF).toByte()
            b[j++] = ((s shr 8) and 0xFF).toByte()
        }
        raf.write(b)
        dataBytes += b.size
    }

    /** 헤더를 현재 크기로 갱신 + 디스크 동기화. 녹음 도중 폰/앱이 죽어도 그때까지가 재생 가능. */
    fun flushHeader() {
        try {
            val end = raf.filePointer
            writeHeader()
            raf.seek(end)
            raf.fd.sync()
        } catch (_: Exception) {}
    }

    fun close() {
        try { writeHeader() } finally { raf.close() }
    }

    private fun writeHeader() {
        raf.seek(0)
        val byteRate = sampleRate * 2
        raf.write("RIFF".toByteArray())
        raf.write(intLE(36 + dataBytes))
        raf.write("WAVE".toByteArray())
        raf.write("fmt ".toByteArray())
        raf.write(intLE(16))      // PCM fmt chunk size
        raf.write(shortLE(1))     // audio format PCM
        raf.write(shortLE(1))     // channels = mono
        raf.write(intLE(sampleRate))
        raf.write(intLE(byteRate))
        raf.write(shortLE(2))     // block align
        raf.write(shortLE(16))    // bits per sample
        raf.write("data".toByteArray())
        raf.write(intLE(dataBytes))
    }

    private fun intLE(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
    )

    private fun shortLE(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
}
