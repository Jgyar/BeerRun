package com.beerrun.game

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.io.FileOutputStream
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Процедурный синтезатор звуков: генерирует WAV-сэмплы в памяти и воспроизводит через SoundPool.
 */
class Sound(context: Context) {
    private val soundPool: SoundPool
    private var idGood = 0
    private var idBad = 0
    private var idTrap = 0
    private var idGulp = 0
    private var idBurp = 0
    private var idWin = 0
    private var idLose = 0
    private var idClick = 0
    private var loaded = false

    init {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(6)
            .setAudioAttributes(attrs)
            .build()

        try {
            val cacheDir = context.cacheDir
            idGood = loadWave(cacheDir, "sfx_good.wav", generateArpeggio(floatArrayOf(523f, 659f, 784f), 0.065f))
            idBad = loadWave(cacheDir, "sfx_bad.wav", generateSweep(300f, 120f, 0.24f, noiseMix = 0.2f))
            idTrap = loadWave(cacheDir, "sfx_trap.wav", generateSweep(420f, 90f, 0.14f, noiseMix = 0.55f))
            idGulp = loadWave(cacheDir, "sfx_gulp.wav", generateGulp())
            idBurp = loadWave(cacheDir, "sfx_burp.wav", generateBurp())
            idWin = loadWave(cacheDir, "sfx_win.wav", generateArpeggio(floatArrayOf(523f, 659f, 784f, 1046f), 0.11f))
            idLose = loadWave(cacheDir, "sfx_lose.wav", generateArpeggio(floatArrayOf(392f, 349f, 311f, 261f), 0.14f))
            idClick = loadWave(cacheDir, "sfx_click.wav", generateSweep(700f, 950f, 0.05f, noiseMix = 0f))
            loaded = true
        } catch (_: Exception) {
            loaded = false
        }
    }

    fun playGateGood() = play(idGood, 0.85f)
    fun playGateBad() = play(idBad, 0.85f)
    fun playTrap() = play(idTrap, 0.7f)
    fun playGulp() = play(idGulp, 0.65f, rate = 0.92f + (Math.random().toFloat() * 0.18f))
    fun playBurp() = play(idBurp, 1.0f)
    fun playWin() = play(idWin, 0.95f)
    fun playLose() = play(idLose, 0.9f)
    fun playClick() = play(idClick, 0.7f)

    private fun play(soundId: Int, volume: Float, rate: Float = 1.0f) {
        if (loaded && soundId != 0) {
            soundPool.play(soundId, volume, volume, 1, 0, rate)
        }
    }

    fun release() {
        soundPool.release()
    }

    private fun loadWave(dir: File, name: String, pcm: ShortArray): Int {
        val file = File(dir, name)
        FileOutputStream(file).use { out ->
            writeWavHeader(out, pcm.size * 2, SAMPLE_RATE)
            val buffer = ByteArray(pcm.size * 2)
            for (i in pcm.indices) {
                val sample = pcm[i].toInt()
                buffer[i * 2] = (sample and 0xFF).toByte()
                buffer[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
            }
            out.write(buffer)
        }
        return soundPool.load(file.absolutePath, 1)
    }

    private fun generateSweep(startFreq: Float, endFreq: Float, duration: Float, noiseMix: Float): ShortArray {
        val count = (SAMPLE_RATE * duration).toInt()
        val out = ShortArray(count)
        val rng = Random(42)
        var phase = 0.0
        for (i in 0 until count) {
            val t = i.toFloat() / count
            val env = (1f - t) * (1f - t)
            val freq = startFreq + (endFreq - startFreq) * t
            phase += 2.0 * PI * freq / SAMPLE_RATE
            val tone = sin(phase).toFloat()
            val noise = rng.nextFloat() * 2f - 1f
            val signal = (tone * (1f - noiseMix) + noise * noiseMix) * env * 0.75f
            out[i] = (signal.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun generateArpeggio(notes: FloatArray, noteDur: Float): ShortArray {
        val samplesPerNote = (SAMPLE_RATE * noteDur).toInt()
        val out = ShortArray(samplesPerNote * notes.size)
        var idx = 0
        for (freq in notes) {
            var phase = 0.0
            for (i in 0 until samplesPerNote) {
                val t = i.toFloat() / samplesPerNote
                val env = exp(-3.5f * t)
                phase += 2.0 * PI * freq / SAMPLE_RATE
                val signal = (sin(phase) + 0.35 * sin(phase * 2.0)).toFloat() * env * 0.6f
                out[idx++] = (signal.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out
    }

    private fun generateGulp(): ShortArray {
        val count = (SAMPLE_RATE * 0.11f).toInt()
        val out = ShortArray(count)
        var phase = 0.0
        for (i in 0 until count) {
            val t = i.toFloat() / count
            val env = sin(PI * t).toFloat()
            val freq = 180f + 420f * (t * t)
            phase += 2.0 * PI * freq / SAMPLE_RATE
            val signal = sin(phase).toFloat() * env * 0.75f
            out[i] = (signal.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun generateBurp(): ShortArray {
        val count = (SAMPLE_RATE * 0.55f).toInt()
        val out = ShortArray(count)
        val rng = Random(7)
        var phase = 0.0
        var modPhase = 0.0
        for (i in 0 until count) {
            val t = i.toFloat() / count
            val env = sin(PI * t).toFloat() * (1f - 0.3f * t)
            val baseFreq = 95f - 28f * t
            modPhase += 2.0 * PI * 32.0 / SAMPLE_RATE
            phase += 2.0 * PI * (baseFreq + 18.0 * sin(modPhase)) / SAMPLE_RATE
            val growl = sin(phase).toFloat() + 0.5f * sin(phase * 2.0).toFloat()
            val rumble = (rng.nextFloat() * 2f - 1f) * 0.25f
            val signal = (growl * 0.65f + rumble) * env * 0.85f
            out[i] = (signal.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun writeWavHeader(out: FileOutputStream, dataSize: Int, sampleRate: Int) {
        val totalSize = dataSize + 36
        val byteRate = sampleRate * 2
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        writeIntLE(header, 4, totalSize)
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        writeIntLE(header, 16, 16)
        writeShortLE(header, 20, 1)
        writeShortLE(header, 22, 1)
        writeIntLE(header, 24, sampleRate)
        writeIntLE(header, 28, byteRate)
        writeShortLE(header, 32, 2)
        writeShortLE(header, 34, 16)
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        writeIntLE(header, 40, dataSize)
        out.write(header)
    }

    private fun writeIntLE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeShortLE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    companion object {
        private const val SAMPLE_RATE = 22050
    }
}
