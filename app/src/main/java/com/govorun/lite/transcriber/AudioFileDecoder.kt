package com.govorun.lite.transcriber

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/** Decodes a shared audio URI to the 16 kHz, mono, 16-bit PCM expected by GigaAM. */
object AudioFileDecoder {
    fun decode(context: Context, uri: Uri): ShortArray {
        val extractor = MediaExtractor()
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { extractor.setDataSource(it.fileDescriptor) }
                ?: throw IllegalArgumentException("Unable to open audio file")
            var track = -1
            for (i in 0 until extractor.trackCount) {
                if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    break
                }
            }
            if (track < 0) throw IllegalArgumentException("No audio track found")
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("Unknown audio format")
            val sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val sourceChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            extractor.selectTrack(track)
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            val pcm = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            try {
                while (!outputDone) {
                    if (!inputDone) {
                        val inputIndex = codec.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val input = codec.getInputBuffer(inputIndex)!!
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                        MediaCodec.INFO_TRY_AGAIN_LATER -> if (inputDone) outputDone = true
                        else -> if (outputIndex >= 0) {
                            if (info.size > 0) {
                                val output = codec.getOutputBuffer(outputIndex)!!
                                output.position(info.offset)
                                output.limit(info.offset + info.size)
                                val bytes = ByteArray(info.size)
                                output.get(bytes)
                                pcm.write(bytes)
                            }
                            codec.releaseOutputBuffer(outputIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            } finally {
                codec.stop()
                codec.release()
            }
            return resampleTo16kMono(pcm.toByteArray(), sourceRate, sourceChannels)
        } finally {
            extractor.release()
        }
    }

    private fun resampleTo16kMono(bytes: ByteArray, sourceRate: Int, channels: Int): ShortArray {
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val frames = input.remaining() / channels
        if (frames == 0) return ShortArray(0)
        val mono = FloatArray(frames)
        for (frame in 0 until frames) {
            var sum = 0f
            repeat(channels) { sum += input.get().toFloat() }
            mono[frame] = sum / channels / 32768f
        }
        if (sourceRate == 16_000) return ShortArray(frames) { (mono[it] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
        val outputFrames = (frames.toLong() * 16_000L / sourceRate).toInt()
        return ShortArray(outputFrames) { index ->
            val position = index.toDouble() * sourceRate / 16_000.0
            val left = position.toInt().coerceAtMost(frames - 1)
            val right = min(left + 1, frames - 1)
            val value = mono[left] + (mono[right] - mono[left]) * (position - left).toFloat()
            (value * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
