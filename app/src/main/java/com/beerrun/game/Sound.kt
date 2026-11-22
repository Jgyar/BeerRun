package com.beerrun.game

import android.media.AudioAttributes
import android.media.SoundPool
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import android.content.Context

class Sound(context: Context) {
    private val soundPool: SoundPool
    private var idGateGood = 0
    private var idGateBad = 0
    private var idTrap = 0
    private var idPickup = 0
    private var idGulp = 0
    private var idBurp = 0
    private var idWin = 0
    private var idLose = 0
    private var idClick = 0
    private var idAchievement = 0
    private var idShield = 0

    var muted: Boolean = false

    init {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(8)
            .setAudioAttributes(attrs)
            .build()

        val cacheDir = context.cacheDir
        idGateGood = loadSynth(cacheDir, "snd_good.wav", 0.14, 520.0, 880.0, 0.45, false)
        idGateBad = loadSynth(cacheDir, "snd_bad.wav", 0.18, 260.0, 120.0, 0.50, true)
        idTrap = loadSynth(cacheDir, "snd_trap.wav", 0.20, 180.0, 65.0, 0.55, true)
        idPickup = loadSynth(cacheDir, "snd_pick.wav", 0.10, 660.0, 1040.0, 0.35, false)
        idGulp = loadSynth(cacheDir, "snd_gulp.wav", 0.08, 300.0, 540.0, 0.35, false)
        idBurp = loadSynth(cacheDir, "snd_burp.wav", 0.48, 125.0, 68.0, 0.70, true)
        idWin = loadMelody(cacheDir, "snd_win.wav", doubleArrayOf(523.25, 659.25, 783.99, 1046.50), 0.11)
        idLose = loadMelody(cacheDir, "snd_lose.wav", doubleArrayOf(392.0, 349.23, 311.13, 261.63), 0.14)
        idClick = loadSynth(cacheDir, "snd_click.wav", 0.05, 820.0, 620.0, 0.30, false)
        idAchievement = loadMelody(cacheDir, "snd_ach.wav", doubleArrayOf(587.33, 783.99, 987.77, 1174.66), 0.09)
        idShield = loadSynth(cacheDir, "snd_shield.wav", 0.18, 400.0, 960.0, 0.45, false)
    }

    fun playGateGood() = play(idGateGood, 0.85f, 1.0f)
    fun playGateBad() = play(idGateBad, 0.85f, 1.0f)
    fun playTrap() = play(idTrap, 0.90f, 1.0f)
    fun playPickup() = play(idPickup, 0.70f, 1.05f)
    fun playGulp() = play(idGulp, 0.65f, 0.92f + (Math.random() * 0.22).toFloat())
    fun playBurp() = play(idBurp, 1.0f, 0.95f)
    fun playWin() = play(idWin, 0.95f, 1.0f)
    fun playLose() = play(idLose, 0.90f, 1.0f)
    fun playClick() = play(idClick, 0.65f, 1.0f)
    fun playAchievement() = play(idAchievement, 0.95f, 1.0f)
    fun playShieldBlock() = play(idShield, 0.90f, 1.0f)

    private fun play(id: Int, vol: Float, rate: Float) {
        if (!muted && id != 0) {
            soundPool.play(id, vol, vol, 1, 0, rate)
        }
    }

    fun release() {
        soundPool.release()
    }

    private fun loadSynth(
        dir: File,
        name: String,
        durationSec: Double,
        startFreq: Double,
        endFreq: Double,
        volume: Double,
        gritty: Boolean
    ): Int {
        val sampleRate = 22050
        val totalSamples = (sampleRate * durationSec).toInt()
        val pcm = ByteArray(totalSamples * 2)
        var phase = 0.0
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / totalSamples
            val freq = startFreq + (endFreq - startFreq) * t
            phase += 2.0 * PI * freq / sampleRate
            val env = exp(-3.5 * t)
            var wave = sin(phase)
            if (gritty) {
                wave = (wave + 0.45 * sin(phase * 2.0) + 0.25 * sin(phase * 3.0)) / 1.7
            }
            val sample = (wave * env * volume * 32767.0).toInt().coerceIn(-32767, 32767)
            pcm[i * 2] = (sample and 0xFF).toByte()
            pcm[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return writeAndLoad(dir, name, sampleRate, pcm)
    }

    private fun loadMelody(
        dir: File,
        name: String,
        notes: DoubleArray,
        noteDurSec: Double
    ): Int {
        val sampleRate = 22050
        val samplesPerNote = (sampleRate * noteDurSec).toInt()
        val totalSamples = samplesPerNote * notes.size
        val pcm = ByteArray(totalSamples * 2)
        var idx = 0
        for (freq in notes) {
            var phase = 0.0
            for (i in 0 until samplesPerNote) {
                val t = i.toDouble() / samplesPerNote
                phase += 2.0 * PI * freq / sampleRate
                val env = exp(-2.8 * t)
                val wave = sin(phase) * 0.75 + sin(phase * 2.0) * 0.25
                val sample = (wave * env * 0.55 * 32767.0).toInt().coerceIn(-32767, 32767)
                pcm[idx * 2] = (sample and 0xFF).toByte()
                pcm[idx * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
                idx++
            }
        }
        return writeAndLoad(dir, name, sampleRate, pcm)
    }

    private fun writeAndLoad(dir: File, name: String, sampleRate: Int, pcm: ByteArray): Int {
        return try {
            val file = File(dir, name)
            val wav = buildWav(sampleRate, pcm)
            FileOutputStream(file).use { it.write(wav) }
            soundPool.load(file.absolutePath, 1)
        } catch (_: Exception) {
            0
        }
    }

    private fun buildWav(sampleRate: Int, pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(44 + pcm.size)
        val byteRate = sampleRate * 2
        val totalDataLen = pcm.size + 36

        out.write("RIFF".toByteArray())
        writeIntLE(out, totalDataLen)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        writeIntLE(out, 16)
        writeShortLE(out, 1)
        writeShortLE(out, 1)
        writeIntLE(out, sampleRate)
        writeIntLE(out, byteRate)
        writeShortLE(out, 2)
        writeShortLE(out, 16)
        out.write("data".toByteArray())
        writeIntLE(out, pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }

    private fun writeIntLE(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
        out.write((v shr 16) and 0xFF)
        out.write((v shr 24) and 0xFF)
    }

    private fun writeShortLE(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }
}
