package com.blacksand.player.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh

/**
 * "Tape mode": makes playback sound like a cassette. Per channel it applies
 * wow and flutter (slow and fast pitch wobble from a modulated delay), a gentle
 * high-end roll-off, soft saturation, and a little hiss.
 * [amount] 0 = off, 1 = light, 2 = worn. Changes blend in smoothly, no clicks.
 * It also handles the backwards sound while rewinding (see [reverseGrainMs]).
 */
@UnstableApi
class TapeProcessor : BaseAudioProcessor() {

    @Volatile var amount = 0

    /** Rewind cue: when > 0, audio is gathered in grains this long and each grain is played backwards. */
    @Volatile var reverseGrainMs = 0
    private var work = FloatArray(0)
    private var block = FloatArray(0)
    private var blockFill = 0

    private var sampleRate = 44_100
    private var channels = 2
    private var mix = 0f // current blend towards the effect, eases to target
    private var phase = 0.0 // time in samples, drives the wobble
    private val delayLen = 4096
    private var delay = Array(MAX_CHANNELS) { FloatArray(delayLen) }
    private var writePos = 0
    private val lowpass = FloatArray(MAX_CHANNELS)
    private var noiseSeed = 22222

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount > MAX_CHANNELS) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        return inputAudioFormat // same format out, so it can sit in the chain permanently
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frameBytes = channels * 2
        val frames = inputBuffer.remaining() / frameBytes
        if (frames == 0) return
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val samples = frames * channels
        if (work.size < samples) work = FloatArray(samples)
        var w = 0

        val target = when (amount) { 1 -> 0.6f; 2 -> 1f; else -> 0f }

        val sr = sampleRate.toDouble()
        val cutoff = 9_000.0 - 3_000.0 * mix // worn tape loses more top end
        val lpA = (1 - exp(-2 * PI * cutoff / sr)).toFloat()
        val baseDelay = 0.012 * sr // 12 ms, room for the wobble either way

        for (f in 0 until frames) {
            mix += (target - mix) * 0.0005f
            if (target == 0f && mix < 0.0001f) mix = 0f
            // Off: just the 12 ms delay line (kept so switching on never jumps), no processing.
            if (mix == 0f) {
                for (ch in 0 until channels) {
                    val line = delay[ch]
                    line[writePos and (delayLen - 1)] = input.short / 32768f
                    work[w++] = line[(writePos - baseDelay.toInt()) and (delayLen - 1)]
                }
                writePos = (writePos + 1) and (delayLen - 1)
                continue
            }
            // Wow (0.5 Hz, slow) + flutter (6.5 Hz, fast), in samples of delay.
            val t = phase / sr
            val wobble = mix * (0.0016 * sr * sin(2 * PI * 0.5 * t) + 0.00012 * sr * sin(2 * PI * 6.5 * t))
            val readPos = writePos - baseDelay - wobble
            val i0 = kotlin.math.floor(readPos).toInt()
            val frac = (readPos - i0).toFloat()

            for (ch in 0 until channels) {
                val x = input.short / 32768f
                val line = delay[ch]
                line[writePos and (delayLen - 1)] = x
                val a = line[i0 and (delayLen - 1)]
                val b = line[(i0 + 1) and (delayLen - 1)]
                var y = a + (b - a) * frac // the wobbling (pitched) signal

                lowpass[ch] += lpA * (y - lowpass[ch])
                y = lowpass[ch]
                y = tanh(y * 1.3f) / 0.86f // soft tape saturation
                y += hiss() * 0.0025f * mix

                // Blend dry ↔ tape so switching is smooth. Dry is delayed too, so both line up.
                val dry = line[(writePos - baseDelay.toInt()) and (delayLen - 1)]
                work[w++] = dry + (y - dry) * mix
            }
            writePos = (writePos + 1) and (delayLen - 1)
            phase += 1.0
            if (phase > sr * 1000) phase = 0.0
        }
        inputBuffer.position(inputBuffer.limit()) // drop any stray partial frame
        emit(samples)
    }

    private fun pcm(v: Float): Short = (v * 32767f).toInt().coerceIn(-32768, 32767).toShort()

    /** Writes the processed samples out: straight through, or grain by grain backwards while rewinding. */
    private fun emit(samples: Int) {
        val grainFrames = reverseGrainMs * sampleRate / 1000
        if (grainFrames == 0) {
            blockFill = 0
            val out = replaceOutputBuffer(samples * 2)
            for (i in 0 until samples) out.putShort(pcm(work[i]))
            out.flip()
            return
        }
        val need = grainFrames * channels
        if (block.size != need) {
            block = FloatArray(need)
            blockFill = 0
        }
        val complete = (blockFill + samples) / need
        val out = replaceOutputBuffer(complete * need * 2)
        var i = 0
        while (i < samples) {
            val n = minOf(need - blockFill, samples - i)
            System.arraycopy(work, i, block, blockFill, n)
            blockFill += n
            i += n
            if (blockFill == need) {
                // Frames in reverse order, channels kept in place within each frame.
                for (fr in grainFrames - 1 downTo 0) {
                    for (ch in 0 until channels) out.putShort(pcm(block[fr * channels + ch]))
                }
                blockFill = 0
            }
        }
        out.flip()
    }

    // Cheap white noise.
    private fun hiss(): Float {
        noiseSeed = noiseSeed * 1103515245 + 12345
        return ((noiseSeed ushr 16) and 0x7FFF) / 16384f - 1f
    }

    override fun onFlush() {
        blockFill = 0
        delay = Array(MAX_CHANNELS) { FloatArray(delayLen) }
        lowpass.fill(0f)
        writePos = 0
    }

    override fun onReset() {
        onFlush()
        mix = 0f
        phase = 0.0
    }

    private companion object {
        const val MAX_CHANNELS = 8
    }
}
