package io.github.m1omg.opuscast

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import io.github.m1omg.opuscast.core.mp4Faststart
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fun mode: a Skynet-style boot intro played on the TV before the movie. Drawn from scratch (no image
 * assets), sound synthesized here, encoded with the phone's own H.264/AAC encoders. Rendered once and
 * cached. The picture is the same as in the desktop app (opuscast_intro.py), frame for frame in time:
 * frames are rendered at t = i / FPS, so nothing depends on the phone's display.
 */
object Intro {
    private const val VERSION = 1
    private const val W = 1920
    private const val H = 1080
    private const val FPS = 30
    private const val DURATION = 9.0
    private val lock = Any()

    fun cached(context: Context): File? = File(context.cacheDir, "skynet-intro-v$VERSION.mp4").takeIf { it.isFile && it.length() > 0 }

    /** The intro video, rendering it first if needed (takes a while; [progress] gets 0..1). */
    fun ensure(context: Context, progress: (Float) -> Unit): File = synchronized(lock) {
        cached(context)?.let { return it }
        val out = File(context.cacheDir, "skynet-intro-v$VERSION.mp4")
        val part = File(context.cacheDir, "skynet-intro.part.mp4")
        try {
            encode(part, progress)
            out.writeBytes(mp4Faststart(part.readBytes()))
        } finally {
            part.delete()
        }
        return out
    }

    // ------------------------------------------------------------ encoding

    private class Sample(val data: ByteArray, val ptsUs: Long, val flags: Int)

    private fun encoderFor(mime: String, width: Int = 0, height: Int = 0): String? {
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (!info.isEncoder || info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
            if (width > 0) {
                val caps = info.getCapabilitiesForType(mime)
                if (caps.videoCapabilities?.isSizeSupported(width, height) != true) continue
                if (MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible !in caps.colorFormats) continue
            }
            return info.name
        }
        return null
    }

    private fun drain(codec: MediaCodec, samples: MutableList<Sample>, untilEos: Boolean): MediaFormat? {
        var format: MediaFormat? = null
        val info = MediaCodec.BufferInfo()
        while (true) {
            val i = codec.dequeueOutputBuffer(info, if (untilEos) 10_000 else 0)
            when {
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEos) return format
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> format = codec.outputFormat
                i >= 0 -> {
                    val buf = codec.getOutputBuffer(i)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val data = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.get(data)
                        samples += Sample(data, info.presentationTimeUs, info.flags)
                    }
                    codec.releaseOutputBuffer(i, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return format
                }
            }
        }
    }

    private fun encode(target: File, progress: (Float) -> Unit) {
        // audio first: small, and the muxer needs both formats before it starts
        val pcm = audio(48_000)
        val audioSamples = ArrayList<Sample>()
        var audioFormat: MediaFormat? = null
        val aName = encoderFor(MediaFormat.MIMETYPE_AUDIO_AAC) ?: throw IOException("no AAC encoder")
        val aac = MediaCodec.createByCodecName(aName)
        try {
            val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48_000, 1)
            f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            f.setInteger(MediaFormat.KEY_BIT_RATE, 160_000)
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            aac.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            aac.start()
            var offset = 0
            var eos = false
            while (!eos) {
                val i = aac.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val buf = aac.getInputBuffer(i)!!.order(ByteOrder.LITTLE_ENDIAN)
                    buf.clear()
                    val n = min(buf.remaining() / 2, pcm.size - offset)
                    for (k in 0 until n) buf.putShort(pcm[offset + k])
                    val pts = offset * 1_000_000L / 48_000
                    offset += n
                    eos = offset >= pcm.size
                    aac.queueInputBuffer(i, 0, n * 2, pts, if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                }
                drain(aac, audioSamples, false)?.let { audioFormat = it }
            }
            drain(aac, audioSamples, true)?.let { audioFormat = it }
        } finally {
            runCatching { aac.stop() }
            aac.release()
        }

        // the picture: 1080p if the encoder can, else 720p
        var (vw, vh) = W to H
        var vName = encoderFor(MediaFormat.MIMETYPE_VIDEO_AVC, vw, vh)
        if (vName == null) {
            vw = 1280
            vh = 720
            vName = encoderFor(MediaFormat.MIMETYPE_VIDEO_AVC, vw, vh) ?: throw IOException("no H.264 encoder")
        }
        val videoSamples = ArrayList<Sample>()
        var videoFormat: MediaFormat? = null
        val avc = MediaCodec.createByCodecName(vName)
        val renderer = Renderer()
        val scaled = if (vw != W) Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888) else null
        val pixels = IntArray(vw * vh)
        try {
            val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, vw, vh)
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            f.setInteger(MediaFormat.KEY_BIT_RATE, if (vw == W) 10_000_000 else 6_000_000)
            f.setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            avc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            avc.start()
            val frames = (DURATION * FPS).toInt()
            var frame = 0
            while (frame <= frames) {
                val i = avc.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    if (frame == frames) {
                        avc.queueInputBuffer(i, 0, 0, frame * 1_000_000L / FPS, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    } else {
                        var bmp = renderer.frame(frame.toDouble() / FPS)
                        if (scaled != null) {
                            Canvas(scaled).drawBitmap(bmp, null, Rect(0, 0, vw, vh), Paint(Paint.FILTER_BITMAP_FLAG))
                            bmp = scaled
                        }
                        bmp.getPixels(pixels, 0, vw, 0, 0, vw, vh)
                        val capacity = avc.getInputBuffer(i)!!.capacity()
                        val image = avc.getInputImage(i) ?: throw IOException("the encoder takes no pictures")
                        writeYuv(pixels, vw, vh, image)
                        avc.queueInputBuffer(i, 0, capacity, frame * 1_000_000L / FPS, 0)
                        progress((frame + 1).toFloat() / frames)
                    }
                    frame++
                }
                drain(avc, videoSamples, false)?.let { videoFormat = it }
            }
            drain(avc, videoSamples, true)?.let { videoFormat = it }
        } finally {
            runCatching { avc.stop() }
            avc.release()
            renderer.recycle()
            scaled?.recycle()
        }

        val vf = videoFormat ?: throw IOException("the video encoder gave nothing")
        val af = audioFormat ?: throw IOException("the audio encoder gave nothing")
        val muxer = MediaMuxer(target.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val vt = muxer.addTrack(vf)
            val at = muxer.addTrack(af)
            muxer.start()
            val info = MediaCodec.BufferInfo()
            // interleaved by time, the way a TV likes to read it (each track stays in the encoder's order)
            var v = 0
            var a = 0
            while (v < videoSamples.size || a < audioSamples.size) {
                val takeVideo = a >= audioSamples.size || (v < videoSamples.size && videoSamples[v].ptsUs <= audioSamples[a].ptsUs)
                val s = if (takeVideo) videoSamples[v++] else audioSamples[a++]
                info.set(0, s.data.size, s.ptsUs, s.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                muxer.writeSampleData(if (takeVideo) vt else at, ByteBuffer.wrap(s.data), info)
            }
            muxer.stop()
        } finally {
            muxer.release()
        }
    }

    /** ARGB pixels -> the encoder's YUV 4:2:0 picture (BT.601, limited range), whatever its layout. */
    private fun writeYuv(px: IntArray, w: Int, h: Int, image: android.media.Image) {
        val (yp, up, vp) = image.planes
        val yBuf = yp.buffer
        val yRow = yp.rowStride
        val yPix = yp.pixelStride
        val row = ByteArray(w)
        for (y in 0 until h) {
            val o = y * w
            for (x in 0 until w) {
                val c = px[o + x]
                val r = c shr 16 and 0xFF
                val g = c shr 8 and 0xFF
                val b = c and 0xFF
                row[x] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
            }
            if (yPix == 1) {
                yBuf.position(y * yRow)
                yBuf.put(row, 0, w)
            } else {
                for (x in 0 until w) yBuf.put(y * yRow + x * yPix, row[x])
            }
        }
        val uBuf = up.buffer
        val vBuf = vp.buffer
        for (y in 0 until h / 2) {
            for (x in 0 until w / 2) {
                var r = 0
                var g = 0
                var b = 0
                for (dy in 0..1) for (dx in 0..1) {
                    val c = px[(2 * y + dy) * w + 2 * x + dx]
                    r += c shr 16 and 0xFF
                    g += c shr 8 and 0xFF
                    b += c and 0xFF
                }
                r = r shr 2
                g = g shr 2
                b = b shr 2
                uBuf.put(y * up.rowStride + x * up.pixelStride, (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte())
                vBuf.put(y * vp.rowStride + x * vp.pixelStride, (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte())
            }
        }
    }

    // ------------------------------------------------------------ picture

    private const val SUBTITLE = "NEURAL NET-BASED ARTIFICIAL INTELLIGENCE"
    private const val FOOTER = "• CYBERDYNE SYSTEMS CORPORATION •"
    private val RED = floatArrayOf(0.89f, 0.06f, 0.09f)

    // timeline, seconds
    private const val STRIPES_START = 0.6
    private const val STRIPES_END = 2.4
    private const val BOOM = 2.45
    private const val LOGO_START = 2.6
    private const val TYPE_START = 3.6
    private const val TYPE_END = 5.2
    private const val FOOTER_START = 5.6
    private const val FOOTER_END = 6.4
    private const val GLITCH_START = 8.0
    private const val GLITCH_END = 8.6

    // triangle geometry: apex at (CX, TOP), 45-degree sides, height SIZE
    private const val CX = 960f
    private const val TOP = 100f
    private const val SIZE = 540f
    private const val BASE = TOP + SIZE
    private const val STRIPE_PERIOD = 20f
    private const val STRIPE_RED = 13f
    private const val N_STRIPES = (SIZE / STRIPE_PERIOD).toInt()
    // Y cut: diamond depth, arm gap, stem width (as fractions of SIZE)
    private const val Y_DEPTH = 0.65f
    private const val Y_GAP = 0.085f
    private const val Y_STEM = 0.09f
    // flicker pattern for the SKYNET word: (start offset, on?)
    private val FLICKER = listOf(0.0 to true, 0.05 to false, 0.12 to true, 0.16 to false, 0.30 to true, 0.34 to false,
        0.38 to true, 0.52 to false, 0.56 to true)

    private fun rgba(c: FloatArray, a: Float) =
        Color.argb((a * 255).toInt().coerceIn(0, 255), (c[0] * 255).toInt(), (c[1] * 255).toInt(), (c[2] * 255).toInt())

    private fun rgba(r: Float, g: Float, b: Float, a: Float) = rgba(floatArrayOf(r, g, b), a)

    private fun pt(u: Float, v: Float) = (CX + u * SIZE) to (TOP + v * SIZE)

    /** The three red pieces of the triangle, split by the Y. */
    private fun piecesPath(): Path {
        val d = Y_DEPTH
        val c = Y_DEPTH + Y_GAP * sqrt(2f)
        val w = Y_STEM
        val top = listOf(0f to 0f, -d / 2 to d / 2, 0f to d, d / 2 to d / 2)
        val left = listOf(-c / 2 to c / 2, -w / 2 to c - w / 2, -w / 2 to 1f, -1f to 1f)
        val right = left.map { (x, y) -> -x to y }
        val path = Path()
        for (poly in listOf(top, left, right)) {
            poly.forEachIndexed { i, (u, v) ->
                val (x, y) = pt(u, v)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
        }
        return path
    }

    private fun trianglePath() = Path().apply {
        val (ax, ay) = pt(0f, 0f)
        val (lx, ly) = pt(-1f, 1f)
        val (rx, ry) = pt(1f, 1f)
        moveTo(ax, ay)
        lineTo(lx, ly)
        lineTo(rx, ry)
        close()
    }

    private fun Path.arc(cx: Float, cy: Float, r: Float, fromDeg: Float, sweepDeg: Float) =
        arcTo(RectF(cx - r, cy - r, cx + r, cy + r), fromDeg, sweepDeg, false)

    /** SKYNET in a wide geometric face, as stroked centrelines, centred on x. */
    private fun drawWord(canvas: Canvas, x: Float, y: Float, h: Float, t: Float) {
        val a = t / 2
        val hh = h / 2 - a
        val r = hh * 0.45f
        val S: (Float) -> Path = { w -> Path().apply {
            moveTo(w, a); lineTo(a + r, a)
            arc(a + r, a + r, r, -90f, -90f)
            lineTo(a, h / 2 - r)
            arc(a + r, h / 2 - r, r, 180f, -90f)
            lineTo(w - a - r, h / 2)
            arc(w - a - r, h / 2 + r, r, -90f, 90f)
            lineTo(w - a, h - a - r)
            arc(w - a - r, h - a - r, r, 0f, 90f)
            lineTo(0f, h - a)
        } }
        val K: (Float) -> Path = { w -> Path().apply {
            moveTo(a, 0f); lineTo(a, h)
            val px = a
            val py = h * 0.64f
            val qx = w - a + t
            val qy = -t
            moveTo(px, py); lineTo(qx, qy)
            val mx = px + (qx - px) * 0.36f
            val my = py + (qy - py) * 0.36f
            moveTo(mx, my); lineTo(w - a + t * 0.7f, h + t)
        } }
        val Y: (Float) -> Path = { w -> Path().apply {
            moveTo(a - t, -t); lineTo(w / 2, h * 0.52f)
            moveTo(w - a + t, -t); lineTo(w / 2, h * 0.52f)
            moveTo(w / 2, h * 0.45f); lineTo(w / 2, h)
        } }
        val N: (Float) -> Path = { w -> Path().apply {
            moveTo(a, h); lineTo(a, a * 0.2f)
            lineTo(w - a, h - a * 0.2f); lineTo(w - a, 0f)
        } }
        val E: (Float) -> Path = { w -> Path().apply {
            moveTo(w, a); lineTo(a + r, a)
            arc(a + r, a + r, r, -90f, -90f)
            lineTo(a, h - a - r)
            arc(a + r, h - a - r, r, 180f, -90f)
            lineTo(w, h - a)
            moveTo(a, h / 2); lineTo(w * 0.86f, h / 2)
        } }
        val T: (Float) -> Path = { w -> Path().apply {
            moveTo(0f, a); lineTo(w, a)
            moveTo(w / 2, 0f); lineTo(w / 2, h)
        } }
        val letters = listOf(S to 152f, K to 150f, Y to 158f, N to 158f, E to 142f, T to 150f)
        val gap = 30f
        val total = letters.sumOf { it.second.toDouble() }.toFloat() + gap * (letters.size - 1)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = t
            strokeCap = Paint.Cap.BUTT
            strokeJoin = Paint.Join.MITER
            color = Color.WHITE
        }
        canvas.save()
        canvas.translate(x - total / 2, y)
        for ((draw, w) in letters) {
            canvas.save()
            canvas.clipRect(0f, 0f, w, h)
            canvas.drawPath(draw(w), paint)
            canvas.restore()
            canvas.translate(w + gap, 0f)
        }
        canvas.restore()
    }

    private class Renderer {
        private val surface = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        private val canvas = Canvas(surface)
        private val snap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        private val pieces = piecesPath()
        private val logo = layer { c ->
            c.clipPath(pieces)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, TOP, 0f, BASE, rgba(1.0f, 0.16f, 0.14f, 1f), rgba(0.80f, 0.02f, 0.06f, 1f), Shader.TileMode.CLAMP)
            }
            for (k in 0 until N_STRIPES) {
                val y = BASE - (k + 1) * STRIPE_PERIOD + (STRIPE_PERIOD - STRIPE_RED)
                c.drawRect(CX - SIZE, y, CX + SIZE, y + STRIPE_RED, p)
            }
        }
        private val glow = layer { c ->
            val outside = Path().apply {
                fillType = Path.FillType.EVEN_ODD
                addRect(0f, 0f, W.toFloat(), H.toFloat(), Path.Direction.CW)
                addPath(trianglePath())
            }
            c.clipPath(outside) // glow only outside the triangle, so the Y gaps stay black
            for ((width, alpha) in listOf(110f to 0.035f, 70f to 0.045f, 36f to 0.06f)) {
                c.drawPath(trianglePath(), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = width
                    strokeJoin = Paint.Join.ROUND
                    color = rgba(RED, alpha)
                })
            }
        }
        private val word = layer { c -> drawWord(c, CX, 672f, 118f, 30f) }
        private val footer = layer { c ->
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.SANS_SERIF
                textSize = 28f
                color = rgba(RED, 1f)
            }
            val spacing = 5f
            val widths = FOOTER.map { p.measureText(it.toString()) + spacing }
            var x = CX - (widths.sum() - spacing) / 2
            FOOTER.forEachIndexed { i, ch ->
                c.drawText(ch.toString(), x, 985f, p)
                x += widths[i]
            }
        }
        private val overlay = layer { c ->
            val lines = Paint().apply { color = Color.argb((0.22f * 255).toInt(), 0, 0, 0) }
            var y = 0f
            while (y < H) {
                c.drawRect(0f, y, W.toFloat(), y + 1, lines)
                y += 3
            }
            val inner = H * 0.35f
            val outer = H * 1.05f
            c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
                shader = RadialGradient(CX, H / 2f, outer, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(153, 0, 0, 0)),
                    floatArrayOf(0f, inner / outer, 1f), Shader.TileMode.CLAMP)
            })
        }
        private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = 46f
            color = Color.WHITE
            textScaleX = min(1f, 990f / measureText(SUBTITLE))
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

        private fun layer(draw: (Canvas) -> Unit): Bitmap {
            val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            draw(Canvas(b))
            return b
        }

        private fun paintBitmap(b: Bitmap, alpha: Float = 1f, dx: Float = 0f) {
            bitmapPaint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
            canvas.drawBitmap(b, dx, 0f, bitmapPaint)
        }

        private fun fillRect(l: Float, t: Float, w: Float, h: Float, color: Int) {
            fill.color = color
            canvas.drawRect(l, t, l + w, t + h, fill)
        }

        fun frame(t: Double): Bitmap {
            canvas.drawColor(Color.BLACK)
            if (t < GLITCH_END) {
                content(t)
                if (t >= GLITCH_START) glitch(t)
                paintBitmap(overlay)
            }
            return surface
        }

        private fun content(t: Double) {
            // boom: shake + flash
            val shake = if (t >= BOOM) max(0.0, 1 - (t - BOOM) / 0.3).toFloat() else 0f
            val rnd = Random((t * FPS).toLong())
            val dx = (rnd.nextDouble() * 2 - 1).toFloat() * 14 * shake
            val dy = (rnd.nextDouble() * 2 - 1).toFloat() * 10 * shake
            canvas.save()
            canvas.translate(dx, dy)

            // stripes revealed bottom-up, each flashing as it appears
            val step = (STRIPES_END - STRIPES_START) / N_STRIPES
            val shown = if (t >= STRIPES_START) min(N_STRIPES, ((t - STRIPES_START) / step).toInt() + 1) else 0
            if (shown > 0) {
                val front = BASE - shown * STRIPE_PERIOD
                paintBitmap(glow, shown.toFloat() / N_STRIPES)
                canvas.save()
                canvas.clipRect(0f, front, W.toFloat(), H.toFloat())
                paintBitmap(logo)
                canvas.restore()
                canvas.save()
                canvas.clipPath(pieces)
                for (k in 0 until shown) {
                    val age = t - (STRIPES_START + k * step)
                    if (age < 0.2) {
                        val y = BASE - (k + 1) * STRIPE_PERIOD + (STRIPE_PERIOD - STRIPE_RED)
                        fillRect(0f, y, W.toFloat(), STRIPE_RED, rgba(1f, 0.85f, 0.8f, (0.85 * (1 - age / 0.2)).toFloat()))
                    }
                }
                if (shake > 0) canvas.drawColor(rgba(1f, 0.9f, 0.9f, 0.45f * shake))
                canvas.restore()
                if (t < STRIPES_END + 0.1) { // scanning line at the reveal front
                    val half = (BASE - front) + 40
                    fillRect(CX - half, front - 2, 2 * half, 3f, rgba(1f, 0.4f, 0.35f, 0.9f))
                }
            }

            // SKYNET flickers on
            if (t >= LOGO_START) {
                var on = false
                for ((start, state) in FLICKER) if (t - LOGO_START >= start) on = state
                if (on) paintBitmap(word)
            }
            canvas.restore()

            // subtitle typed out, then cursor drops to the next line and blinks
            if (t >= TYPE_START - 0.4) {
                val full = subPaint.measureText(SUBTITLE)
                val x0 = CX - full / 2
                val n = if (t >= TYPE_START)
                    max(0, min(SUBTITLE.length, ((t - TYPE_START) / (TYPE_END - TYPE_START) * SUBTITLE.length).toInt() + 1)) else 0
                canvas.drawText(SUBTITLE, 0, n, x0, 850f, subPaint)
                val cx: Float
                val cy: Float
                val blink: Boolean
                if (t < TYPE_END + 0.15) {
                    cx = x0 + subPaint.measureText(SUBTITLE, 0, n) + 6
                    cy = 815f
                    blink = if (n < SUBTITLE.length && t >= TYPE_START) true else (t * 4).toInt() % 2 == 0
                } else {
                    cx = CX - SIZE
                    cy = 900f
                    blink = ((t - TYPE_END) * 2).toInt() % 2 == 0
                }
                if (blink) fillRect(cx, cy, 6f, 36f, Color.WHITE)
            }

            if (t >= FOOTER_START) paintBitmap(footer, min(1.0, (t - FOOTER_START) / (FOOTER_END - FOOTER_START)).toFloat())
        }

        private fun glitch(t: Double) {
            val rnd = Random(1000 + (t * FPS).toLong())
            val progress = (t - GLITCH_START) / (GLITCH_END - GLITCH_START)
            Canvas(snap).drawBitmap(surface, 0f, 0f, null)
            val colors = listOf(floatArrayOf(0f, 1f, 1f), floatArrayOf(1f, 0f, 0.2f), floatArrayOf(1f, 1f, 1f))
            repeat((6 + 14 * progress).toInt()) {
                val y = rnd.nextInt(H).toFloat()
                val h = (8 + rnd.nextInt(82)).toFloat()
                val shift = (if (rnd.nextBoolean()) -1 else 1) * (20 + rnd.nextDouble() * 240).toFloat() * (0.4f + progress.toFloat())
                canvas.save()
                canvas.clipRect(0f, y, W.toFloat(), y + h)
                canvas.drawColor(Color.BLACK)
                paintBitmap(snap, 1f, shift)
                if (rnd.nextDouble() < 0.35) canvas.drawColor(rgba(colors[rnd.nextInt(3)], 0.25f))
                canvas.restore()
            }
            if (progress > 0.6 || rnd.nextDouble() < 0.15) {
                canvas.drawColor(Color.argb((min(1.0, max(0.5, (progress - 0.6) / 0.4)) * 255).toInt(), 0, 0, 0))
            }
        }

        fun recycle() {
            for (b in listOf(surface, snap, logo, glow, word, footer, overlay)) b.recycle()
        }
    }

    // ------------------------------------------------------------ sound

    private fun audio(rate: Int): ShortArray {
        val n = (DURATION * rate).toInt()
        val buf = DoubleArray(n)
        val rnd = Random(7)
        fun uniform() = rnd.nextDouble() * 2 - 1
        fun add(start: Double, length: Double, fn: (Double) -> Double) {
            val i0 = (start * rate).toInt()
            for (i in max(0, i0) until min(n, i0 + (length * rate).toInt())) buf[i] += fn((i - i0).toDouble() / rate)
        }

        // low drone with a slow wobble
        add(0.0, DURATION) { x ->
            val env = min(1.0, x / 1.2) * (if (x < GLITCH_START) 1.0 else max(0.0, 1 - (x - GLITCH_START) / 0.8))
            val saw = 2 * ((82.41 * x) % 1.0) - 1
            val wob = 0.75 + 0.25 * sin(2 * PI * 0.35 * x)
            env * wob * (0.16 * sin(2 * PI * 55 * x) + 0.08 * sin(2 * PI * 110.4 * x) + 0.035 * saw)
        }
        // rising blip per stripe
        val step = (STRIPES_END - STRIPES_START) / N_STRIPES
        for (k in 0 until N_STRIPES) {
            val f = 420.0 + 26 * k
            add(STRIPES_START + k * step, 0.05) { x -> 0.07 * sin(2 * PI * f * x) * exp(-x / 0.015) }
        }
        // boom
        add(BOOM, 1.6) { x ->
            0.55 * sin(2 * PI * (42 + 40 * exp(-6 * x)) * x) * exp(-2.6 * x) + 0.22 * uniform() * exp(-x / 0.03)
        }
        // electric buzz while SKYNET flickers
        add(LOGO_START, 1.2) { x ->
            var on = false
            for ((start, state) in FLICKER) if (x >= start) on = state
            val sq = if ((120 * x) % 1.0 < 0.5) 1.0 else -1.0
            if (on) 0.05 * sq * exp(-max(0.0, x - 0.56) * 3) else 0.0
        }
        // typing ticks
        val per = (TYPE_END - TYPE_START) / SUBTITLE.length
        SUBTITLE.forEachIndexed { i, ch ->
            if (ch != ' ') add(TYPE_START + i * per, 0.03) { x ->
                0.25 * uniform() * exp(-x / 0.002) + 0.1 * sin(2 * PI * 2300 * x) * exp(-x / 0.004)
            }
        }
        // soft chime for the footer
        add(FOOTER_START, 2.0) { x ->
            0.09 * (sin(2 * PI * 880 * x) + 0.6 * sin(2 * PI * 1318.5 * x)) * exp(-x * 1.8) * min(1.0, x / 0.01)
        }
        // glitch crackle
        for (j in 0 until 10) add(GLITCH_START + j * 0.06, 0.04) { x -> 0.2 * uniform() * exp(-x / 0.012) }

        val peak = max(1e-9, buf.maxOf { Math.abs(it) })
        val gain = min(1.0, 0.92 / peak)
        return ShortArray(n) { (max(-1.0, min(1.0, buf[it] * gain)) * 32767).toInt().toShort() }
    }
}
