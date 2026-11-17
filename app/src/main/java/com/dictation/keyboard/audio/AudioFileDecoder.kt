package com.dictation.keyboard.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.IOException
import java.nio.ByteOrder

/**
 * Decodes any audio file Android can play (WAV, M4A, MP3, OGG, ...) to 16 kHz mono floats, for
 * the "test with a recording" tool. Uses the phone's own decoders; nothing leaves the device.
 */
object AudioFileDecoder {

    fun decodeToMono16k(context: Context, uri: Uri): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IOException("No audio track in that file")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME)!!

        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = AudioFormat.ENCODING_PCM_16BIT

        val codec = MediaCodec.createDecoderByType(mime)
        val out = FloatBuilder()
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.order(ByteOrder.LITTLE_ENDIAN)
                        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            val fb = buf.asFloatBuffer()
                            while (fb.remaining() >= channels) {
                                var s = 0f
                                repeat(channels) { s += fb.get() }
                                out.add(s / channels)
                            }
                        } else {
                            val sb = buf.asShortBuffer()
                            while (sb.remaining() >= channels) {
                                var s = 0f
                                repeat(channels) { s += sb.get() / 32768f }
                                out.add(s / channels)
                            }
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }
        return resample(out.toArray(), sampleRate, 16_000)
    }

    /** Linear resampling; plenty for speech recognition. */
    fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || input.isEmpty()) return input
        val outLen = (input.size.toLong() * to / from).toInt()
        val ratio = from.toDouble() / to
        return FloatArray(outLen) { i ->
            val pos = i * ratio
            val j = pos.toInt()
            val frac = (pos - j).toFloat()
            val a = input[j.coerceAtMost(input.size - 1)]
            val b = input[(j + 1).coerceAtMost(input.size - 1)]
            a + (b - a) * frac
        }
    }

    private class FloatBuilder {
        private var data = FloatArray(1 shl 16)
        private var size = 0
        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }
        fun toArray(): FloatArray = data.copyOf(size)
    }
}
