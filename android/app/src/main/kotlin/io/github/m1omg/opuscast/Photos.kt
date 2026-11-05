package io.github.m1omg.opuscast

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.m1omg.opuscast.core.BytesSource
import io.github.m1omg.opuscast.core.Dlna
import io.github.m1omg.opuscast.core.MediaKind
import io.github.m1omg.opuscast.core.MediaSource
import io.github.m1omg.opuscast.core.SinkFormats
import io.github.m1omg.opuscast.core.baseMime
import io.github.m1omg.opuscast.core.mimeFor
import io.github.m1omg.opuscast.core.stemOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** A photo made ready for the TV: the file as it is, or a JPEG made from it. [preview] is for the phone's screen. */
class PreparedPhoto(val source: MediaSource, val mime: String, val features: String, val preview: Bitmap?)

/**
 * Photos as TVs take them. A plain JPEG (or a PNG the TV lists) goes as it is. Anything else - HEIC from
 * the camera, WebP, AVIF, a photo that is turned only by its EXIF tag, one larger than TVs open - is
 * decoded here, turned upright, scaled into 3840x2160 and sent as a JPEG.
 */
object Photos {
    private const val BOX_W = 3840
    private const val BOX_H = 2160
    /** JPEG_LRG's limit: what DLNA TVs are made to open. */
    private const val AS_IS_MAX = 4096
    private const val PREVIEW = 1280

    private class Header(val width: Int, val height: Int, val mime: String?, val orientation: Int)

    private fun header(context: Context, item: Video): Header {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { Docs.open(context, item.ref).use { BitmapFactory.decodeStream(it, null, o) } }
        val orientation = runCatching {
            Docs.open(context, item.ref).use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        return Header(o.outWidth, o.outHeight, o.outMimeType, orientation)
    }

    /** Blocks (decoding a big photo takes a moment): not on the main thread. */
    fun prepare(context: Context, item: Video, sink: SinkFormats): PreparedPhoto {
        val h = header(context, item)
        val mime = baseMime(h.mime ?: item.mime ?: mimeFor(item.name))
        val fits = h.width in 1..AS_IS_MAX && h.height in 1..AS_IS_MAX
        val upright = h.orientation == ExifInterface.ORIENTATION_NORMAL || h.orientation == ExifInterface.ORIENTATION_UNDEFINED
        val asIs = fits && when (mime) {
            "image/jpeg" -> upright
            "image/png" -> sink.accepts(mime) != false
            "image/gif", "image/bmp" -> sink.accepts(mime) == true
            else -> false
        }
        if (asIs) {
            val preview = runCatching { decode(context, item, PREVIEW, PREVIEW) }.getOrNull()
            val spelled = sink.spell(mime)
            return PreparedPhoto(DocSource(context, item), spelled, Dlna.image(mime, h.width, h.height, converted = false), preview)
        }
        val full = decode(context, item, BOX_W, BOX_H)
        val bytes = jpeg(full)
        val preview = fit(full, PREVIEW, PREVIEW)
        val stem = stemOf(item.name)
        val spelled = sink.spell("image/jpeg")
        return PreparedPhoto(BytesSource("$stem.jpg", bytes, spelled, MediaKind.IMAGE, stem), spelled,
            Dlna.image("image/jpeg", full.width, full.height, converted = true), preview)
    }

    /** Upright and no larger than [boxW] x [boxH]. */
    fun decode(context: Context, item: Video, boxW: Int, boxH: Int): Bitmap {
        val longest = maxOf(boxW, boxH)
        val raw = if (Build.VERSION.SDK_INT >= 28) decodeP(context, item, longest) else decodeOld(context, item, longest)
        return fit(raw, boxW, boxH)
    }

    /** ImageDecoder reads HEIC (and, from Android 12, AVIF) and turns the picture upright by itself. */
    @RequiresApi(28)
    private fun decodeP(context: Context, item: Video, longest: Int): Bitmap {
        val src = if (Docs.isFile(item.ref)) ImageDecoder.createSource(File(item.uri.path ?: item.ref))
            else ImageDecoder.createSource(context.contentResolver, item.uri)
        return ImageDecoder.decodeBitmap(src) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // drawn on a software canvas below
            val w = info.size.width
            val h = info.size.height
            val scale = longest.toDouble() / maxOf(w, h)
            if (scale < 1.0) d.setTargetSize(maxOf(1, (w * scale).toInt()), maxOf(1, (h * scale).toInt()))
        }
    }

    private fun decodeOld(context: Context, item: Video, longest: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        Docs.open(context, item.ref).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("this phone can't open ${item.name}")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= longest) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = Docs.open(context, item.ref).use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IOException("this phone can't open ${item.name}")
        val orientation = runCatching {
            Docs.open(context, item.ref).use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        return upright(bmp, orientation)
    }

    private fun upright(bmp: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> { m.setRotate(180f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
            else -> return bmp
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    /** Scaled down (never up) to fit the box. */
    fun fit(bmp: Bitmap, boxW: Int, boxH: Int): Bitmap {
        val scale = minOf(1.0, boxW.toDouble() / bmp.width, boxH.toDouble() / bmp.height)
        if (scale >= 1.0) return bmp
        return Bitmap.createScaledBitmap(bmp, maxOf(1, (bmp.width * scale).toInt()), maxOf(1, (bmp.height * scale).toInt()), true)
    }

    /** JPEG at quality 90; see-through parts become black (a JPEG has no transparency). */
    fun jpeg(bmp: Bitmap, quality: Int = 90): ByteArray {
        val opaque = if (bmp.hasAlpha()) {
            Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888).also {
                val c = Canvas(it)
                c.drawColor(Color.BLACK)
                c.drawBitmap(bmp, 0f, 0f, null)
            }
        } else bmp
        val out = ByteArrayOutputStream()
        opaque.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }
}
