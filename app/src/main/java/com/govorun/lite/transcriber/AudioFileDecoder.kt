package com.govorun.lite.transcriber

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/** Streams a media file's first audio track as 16 kHz mono 16-bit PCM. */
object AudioFileDecoder {
    suspend fun decodeStreaming(
        context: Context,
        uri: Uri,
        onPcm: suspend (ShortArray) -> Unit,
        onProgress: suspend (Long, Long) -> Unit,
    ) {
        val extractor = MediaExtractor()
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use {
                extractor.setDataSource(it.fileDescriptor)
            } ?: throw IllegalArgumentException("Unable to open media file")

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
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else 0L

            extractor.selectTrack(track)
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            val converter = PcmStreamConverter(sourceRate, sourceChannels, onPcm)
            var inputDone = false
            var outputDone = false
            try {
                while (!outputDone) {
                    if (!inputDone) {
                        val inputIndex = codec.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val input = codec.getInputBuffer(inputIndex)!!
                            input.clear()
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
                                converter.accept(output.slice().order(ByteOrder.LITTLE_ENDIAN))
                                onProgress(info.presentationTimeUs.coerceAtLeast(0L), durationUs)
                            }
                            codec.releaseOutputBuffer(outputIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
                converter.finish()
                onProgress(durationUs, durationUs)
            } finally {
                codec.stop()
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private class PcmStreamConverter(
        private val sourceRate: Int,
        private val channels: Int,
        private val emit: (ShortArray) -> Unit,
    ) {
        private val output = ArrayList<Short>(4096)
        private var frameIndex = 0L
        private var previous = 0f
        private var hasPrevious = false
        private var nextOutputPosition = 0.0

        fun accept(bytes: ByteBuffer) {
            val samples = bytes.asShortBuffer()
            val frames = samples.remaining() / channels
            repeat(frames) {
                var sum = 0f
                repeat(channels) { sum += samples.get().toFloat() }
                val current = sum / channels
                if (!hasPrevious) {
                    previous = current
                    hasPrevious = true
                } else {
                    while (nextOutputPosition <= frameIndex) {
                        val fraction = (nextOutputPosition - (frameIndex - 1)).toFloat()
                        val value = previous + (current - previous) * fraction
                        output.add((value * 1f).toInt().coerceIn(-32768, 32767).toShort())
                        nextOutputPosition += sourceRate.toDouble() / 16_000.0
                        if (output.size >= 4096) emitOutput()
                    }
                    previous = current
                }
                frameIndex++
            }
        }

        fun finish() {
            if (hasPrevious) {
                while (nextOutputPosition < frameIndex) {
                    output.add(previous.toInt().coerceIn(-32768, 32767).toShort())
                    nextOutputPosition += sourceRate.toDouble() / 16_000.0
                }
            }
            emitOutput()
        }

        private fun emitOutput() {
            if (output.isEmpty()) return
            emit(output.toShortArray())
            output.clear()
        }
    }
}
