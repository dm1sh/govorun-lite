package com.govorun.lite.transcriber

import android.content.Context
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.TenVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/**
 * Silero-VAD segmenter shared by offline-file processing. Input is 16 kHz,
 * mono, 16-bit PCM represented as samples. It deliberately uses the same
 * 512-sample windows and speech limits as the microphone recorder.
 */
class SpeechSegmenter(context: Context) : AutoCloseable {
    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val WINDOW_SIZE = 512
        private const val MIN_TAIL_SAMPLES = SAMPLE_RATE / 4
        private const val MAX_TAIL_SAMPLES = SAMPLE_RATE * 60
        private const val MODEL_ASSET = "silero_vad.onnx"
    }

    private val vad = Vad(null, VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(
            model = ensureModel(context),
            threshold = 0.5f,
            minSilenceDuration = com.govorun.lite.util.Prefs.getPauseLengthSeconds(context),
            minSpeechDuration = 0.25f,
            windowSize = WINDOW_SIZE,
            maxSpeechDuration = 30.0f,
        ),
        tenVadModelConfig = TenVadModelConfig(),
        sampleRate = SAMPLE_RATE,
        numThreads = 1,
        provider = "cpu",
        debug = false,
    ))
    private val pending = ShortArray(WINDOW_SIZE)
    private var pendingCount = 0
    private val tail = ArrayList<Short>(MAX_TAIL_SAMPLES)

    fun acceptPcm(input: ShortArray): List<ShortArray> {
        val result = ArrayList<ShortArray>()
        var offset = 0
        while (offset < input.size) {
            val count = minOf(WINDOW_SIZE - pendingCount, input.size - offset)
            input.copyInto(pending, pendingCount, offset, offset + count)
            pendingCount += count
            offset += count
            if (pendingCount == WINDOW_SIZE) {
                val window = pending.copyOf()
                appendTail(window)
                val samples = FloatArray(WINDOW_SIZE) { window[it] / 32768f }
                vad.acceptWaveform(samples)
                drain(result)
                pendingCount = 0
            }
        }
        return result
    }

    fun flush(): List<ShortArray> {
        val result = ArrayList<ShortArray>()
        if (pendingCount > 0) {
            val window = ShortArray(WINDOW_SIZE)
            pending.copyInto(window, 0, 0, pendingCount)
            appendTail(pending.copyOfRange(0, pendingCount))
            vad.acceptWaveform(FloatArray(WINDOW_SIZE) { window[it] / 32768f })
            pendingCount = 0
        }
        vad.flush()
        drain(result)
        if (result.isEmpty() && tail.size >= MIN_TAIL_SAMPLES) {
            result += tail.toShortArray()
        }
        tail.clear()
        return result
    }

    private fun drain(result: MutableList<ShortArray>) {
        while (!vad.empty()) {
            val segment = vad.front()
            vad.pop()
            val pcm = ShortArray(segment.samples.size) {
                (segment.samples[it] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            }
            if (pcm.isNotEmpty()) result += pcm
            tail.clear()
        }
    }

    private fun appendTail(samples: ShortArray) {
        tail.addAll(samples.toList())
        if (tail.size > MAX_TAIL_SAMPLES) {
            val drop = tail.size - MAX_TAIL_SAMPLES
            repeat(drop) { tail.removeAt(0) }
        }
    }

    override fun close() {
        vad.release()
    }

    private fun ensureModel(context: Context): String {
        val dir = File(context.filesDir, "models/vad").also { it.mkdirs() }
        val file = File(dir, MODEL_ASSET)
        if (!file.exists() || file.length() <= 100_000) {
            context.assets.open(MODEL_ASSET).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return file.absolutePath
    }
}
