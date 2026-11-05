package io.github.m1omg.opuscast

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresApi
import io.github.m1omg.opuscast.core.LiveHub
import io.github.m1omg.opuscast.core.TsMuxer
import io.github.m1omg.opuscast.core.adtsHeader
import java.io.IOException
import kotlin.concurrent.thread

/**
 * The phone's screen as a live stream for the TV: Android mirrors the screen into an H.264 encoder,
 * the sound apps play (Android 10+, with the microphone permission, which covers recording it) goes
 * through an AAC encoder, and both are muxed into MPEG-TS for the [hub].
 *
 * The picture is a landscape 16:9 frame: Android letterboxes the phone's screen into it, so turning the
 * phone just works and the TV never has to handle a change of picture size. Without the sound, silence
 * is encoded instead: some TVs won't play a stream without an audio track.
 */
class ScreenCast(
    private val context: Context,
    private val projection: MediaProjection,
    private val hub: LiveHub,
    /** 1080 or 720. */
    private val lines: Int,
    private val wantSound: Boolean,
    /** Android ended the capture (the "stop sharing" button), or an encoder died. On the main thread. */
    private val onStopped: (String?) -> Unit,
) {
    private val mux = TsMuxer { b, o, l -> hub.write(b, o, l) }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var stopped = false
    private var video: MediaCodec? = null
    private var display: VirtualDisplay? = null
    private var aac: MediaCodec? = null
    private var record: AudioRecord? = null
    private val threads = ArrayList<Thread>()
    @Volatile private var lastKeyRequest = 0L
    /** The sound apps play is captured (not silence). */
    var capturingSound = false
        private set
    var width = 0
        private set
    var height = 0
        private set

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (running) main.post { onStopped(null) }
        }
    }

    /** Throws if the screen can't be captured or encoded; call [stop] then. */
    fun start() {
        running = true
        projection.registerCallback(callback, main) // Android 14: before the virtual display
        startVideo()
        startAudio()
    }

    /** A TV joined: give it a keyframe soon instead of in up to a second (not more often than twice a second). */
    fun requestKeyframe() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastKeyRequest < 500) return
        lastKeyRequest = now
        runCatching { video?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    /** Ends everything (also the projection, so Android's "sharing" indicator goes). Blocks briefly: not on the main thread. */
    fun stop() {
        synchronized(this) {
            if (stopped) return
            stopped = true
        }
        running = false
        runCatching { projection.unregisterCallback(callback) }
        runCatching { display?.release() }
        runCatching { record?.stop() }
        threads.forEach { runCatching { it.join(1000) } }
        runCatching { record?.release() }
        runCatching { video?.stop() }
        runCatching { video?.release() }
        runCatching { aac?.stop() }
        runCatching { aac?.release() }
        runCatching { projection.stop() }
        hub.close()
    }

    private fun failed(what: String) {
        if (running) main.post { onStopped(what) }
    }

    private fun startVideo() {
        val sizes = if (lines >= 1080) listOf(1920 to 1080, 1280 to 720) else listOf(1280 to 720)
        val profiles = listOf(MediaCodecInfo.CodecProfileLevel.AVCProfileHigh, MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
            MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline, null)
        var codec: MediaCodec? = null
        var last: Exception? = null
        loop@ for ((w, h) in sizes) for (profile in profiles) {
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, if (h >= 1080) 6_000_000 else 3_500_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                // a still screen gives no new frames: repeat the last one so the TV keeps getting a stream
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
                if (profile != null) {
                    setInteger(MediaFormat.KEY_PROFILE, profile)
                    setInteger(MediaFormat.KEY_LEVEL, if (h >= 1080) MediaCodecInfo.CodecProfileLevel.AVCLevel41
                        else MediaCodecInfo.CodecProfileLevel.AVCLevel31)
                }
            }
            try {
                c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec = c
                width = w
                height = h
                break@loop
            } catch (e: Exception) {
                last = e
                runCatching { c.release() }
            }
        }
        val enc = codec ?: throw IOException("no H.264 encoder takes this screen: ${last?.message}")
        video = enc
        val surface = enc.createInputSurface()
        enc.start()
        display = projection.createVirtualDisplay("OpusCast", width, height, context.resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
        threads += thread(isDaemon = true, name = "opuscast-screen") { drainVideo(enc) }
    }

    private fun drainVideo(enc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var buf = ByteArray(512 * 1024)
        try {
            while (running) {
                val i = enc.dequeueOutputBuffer(info, 100_000)
                if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { // some encoders put SPS/PPS only here
                    val f = enc.outputFormat
                    val sps = f.getByteBuffer("csd-0")
                    val pps = f.getByteBuffer("csd-1")
                    if (sps != null && pps != null) {
                        val cfg = ByteArray(sps.remaining() + pps.remaining())
                        sps.get(cfg, 0, sps.remaining())
                        pps.get(cfg, cfg.size - pps.remaining(), pps.remaining())
                        mux.setVideoConfig(cfg)
                    }
                    continue
                }
                if (i < 0) continue
                val out = enc.getOutputBuffer(i)
                if (out != null && info.size > 0) {
                    if (buf.size < info.size) buf = ByteArray(info.size * 2)
                    out.position(info.offset)
                    out.limit(info.offset + info.size)
                    out.get(buf, 0, info.size)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) mux.setVideoConfig(buf.copyOf(info.size))
                    else mux.writeVideo(buf, 0, info.size, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                }
                enc.releaseOutputBuffer(i, false)
            }
        } catch (e: Exception) {
            failed("video: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun startAudio() {
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        aac = enc
        val rec = if (wantSound && Build.VERSION.SDK_INT >= 29) captureSound() else null
        record = rec
        capturingSound = rec != null
        threads += thread(isDaemon = true, name = "opuscast-sound-in") { if (rec != null) feedCaptured(enc, rec) else feedSilence(enc) }
        threads += thread(isDaemon = true, name = "opuscast-sound") { drainAudio(enc) }
    }

    /** What apps play (media, games, unknown) - not calls or notifications. Null when Android won't. */
    @RequiresApi(29)
    @SuppressLint("MissingPermission") // checked first; a refusal ends up in the catch and means silence
    private fun captureSound(): AudioRecord? {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(maxOf(min * 2, 32 * 1024))
                .setAudioPlaybackCaptureConfig(config).build()
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                rec.release()
                null
            } else rec
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun queue(enc: MediaCodec, pcm: ByteArray, len: Int, ptsUs: Long): Boolean {
        val i = enc.dequeueInputBuffer(20_000)
        if (i < 0) return false // the encoder is behind: drop this bit rather than fall behind the picture
        val b = enc.getInputBuffer(i) ?: return false
        b.clear()
        val n = minOf(len, b.remaining())
        b.put(pcm, 0, n)
        enc.queueInputBuffer(i, 0, n, ptsUs, 0)
        return true
    }

    /** Timestamps come from the recorder's own clock (AudioTimestamp), on the same monotonic clock as the picture's. */
    private fun feedCaptured(enc: MediaCodec, rec: AudioRecord) {
        val pcm = ByteArray(FRAME * 4)
        val ts = AudioTimestamp()
        var read = 0L // frames read so far
        val startUs = System.nanoTime() / 1000
        var anchorUs = startUs
        var anchorFrame = 0L
        var lastAnchor = 0L
        try {
            while (running) {
                val n = rec.read(pcm, 0, pcm.size)
                if (n < 0) break
                if (n == 0) continue
                val now = SystemClock.elapsedRealtime()
                if (now - lastAnchor > 5000 && rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                    lastAnchor = now
                    anchorUs = ts.nanoTime / 1000
                    anchorFrame = ts.framePosition
                }
                val pts = anchorUs + (read - anchorFrame) * 1_000_000L / RATE
                read += n / 4
                queue(enc, pcm, n, pts)
            }
        } catch (e: Exception) {
            failed("sound: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Silence at the pace of the clock (not of a loop), so the stream always has its audio track. */
    private fun feedSilence(enc: MediaCodec) {
        val pcm = ByteArray(FRAME * 4)
        val startNs = System.nanoTime()
        var sent = 0L
        try {
            while (running) {
                val due = (System.nanoTime() - startNs) / 1000 * RATE / 1_000_000L
                while (running && sent + FRAME <= due) {
                    if (!queue(enc, pcm, pcm.size, startNs / 1000 + sent * 1_000_000L / RATE)) break
                    sent += FRAME
                }
                Thread.sleep(10)
            }
        } catch (e: InterruptedException) {
            // stopping
        } catch (e: Exception) {
            failed("sound: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun drainAudio(enc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        val buf = ByteArray(8192)
        try {
            while (running) {
                val i = enc.dequeueOutputBuffer(info, 100_000)
                if (i < 0) continue
                val out = enc.getOutputBuffer(i)
                if (out != null && info.size in 1..buf.size && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    out.position(info.offset)
                    out.limit(info.offset + info.size)
                    out.get(buf, 0, info.size)
                    val frame = adtsHeader(info.size, RATE, 2) + buf.copyOf(info.size)
                    mux.writeAudio(frame, 0, frame.size, info.presentationTimeUs)
                }
                enc.releaseOutputBuffer(i, false)
            }
        } catch (e: Exception) {
            failed("sound: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val RATE = 48_000
        /** Samples per channel in one AAC frame. */
        const val FRAME = 1024
    }
}
