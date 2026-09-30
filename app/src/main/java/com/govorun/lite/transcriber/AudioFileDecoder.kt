package com.govorun.lite.transcriber

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Streams a media file's first audio track as 16 kHz mono 16-bit PCM. */
object AudioFileDecoder {
    private const val PROGRESS_INTERVAL_US = 250_000L

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
            var lastProgressUs = Long.MIN_VALUE
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
                                val positionUs = info.presentationTimeUs.coerceAtLeast(0L)
                                if (lastProgressUs == Long.MIN_VALUE || positionUs - lastProgressUs >= PROGRESS_INTERVAL_US) {
                                    onProgress(positionUs, durationUs)
                                    lastProgressUs = positionUs
                                }
                            }
                            codec.releaseOutputBuffer(outputIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
                converter.finish()
                onProgress(durationUs, durationUs)
            } finally {
                converter.close()
                codec.stop()
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private class PcmStreamConverter(
        sourceRate: Int,
        channels: Int,
        private val emit: suspend (ShortArray) -> Unit,
    ) : AutoCloseable {
        private val native = NativeResampler(sourceRate, channels)

        suspend fun accept(bytes: ByteBuffer) {
            val output = native.process(bytes, bytes.remaining())
            if (output.isNotEmpty()) emit(output)
        }

        suspend fun finish() {
            val output = native.flush()
            if (output.isNotEmpty()) emit(output)
        }

        override fun close() = native.close()
    }
}
