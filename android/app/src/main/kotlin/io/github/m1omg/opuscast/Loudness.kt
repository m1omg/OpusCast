package io.github.m1omg.opuscast

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import io.github.m1omg.opuscast.core.LoudnessMeter
import io.github.m1omg.opuscast.core.LoudnessSink
import io.github.m1omg.opuscast.core.LoudnessSource
import java.io.IOException
import java.nio.ByteOrder

/**
 * The loudness of a video's or song's sound for [io.github.m1omg.opuscast.core.Leveler]: the phone's own
 * decoder turns the first sound track into PCM and [LoudnessMeter] measures it. Nothing is encoded or sent
 * anywhere; the file the TV plays stays as it is.
 */
class DecodedLoudness(private val context: Context) : LoudnessSource {
    override fun read(file: String, start: Double, sink: LoudnessSink) {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            if (Docs.isFile(file)) ex.setDataSource(if (file.startsWith("file:")) Uri.parse(file).path!! else file)
            else ex.setDataSource(context, Uri.parse(file), null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("no sound track")
            val format = ex.getTrackFormat(track)
            ex.selectTrack(track)
            if (start > 0) ex.seekTo((start * 1e6).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val c = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(format, null, null, 0)
            c.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var float = false
            var meter: LoudnessMeter? = null
            var meterStart = 0.0 // file time of the meter's first sample
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var shorts = ShortArray(0)
            var floats = FloatArray(0)
            while (true) {
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = c.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = c.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    meter = null
                    continue
                }
                if (o < 0) continue
                val t = info.presentationTimeUs / 1e6
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (info.size > 0 && t >= start - 0.05) {
                    if (meter == null) {
                        meterStart = t
                        meter = LoudnessMeter(rate, channels) { mt, lufs -> sink.add(meterStart + mt, lufs) }
                    }
                    val out = c.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                    out.position(info.offset)
                    out.limit(info.offset + info.size)
                    if (float) {
                        val n = info.size / 4
                        if (floats.size < n) floats = FloatArray(n)
                        out.asFloatBuffer().get(floats, 0, n)
                        meter.feed(floats, n / channels)
                    } else {
                        val n = info.size / 2
                        if (shorts.size < n) shorts = ShortArray(n)
                        out.asShortBuffer().get(shorts, 0, n)
                        meter.feed(shorts, n / channels)
                    }
                }
                c.releaseOutputBuffer(o, false)
                if (eos || !sink.keepGoing(t)) return
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }
}
