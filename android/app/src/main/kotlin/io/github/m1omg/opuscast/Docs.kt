package io.github.m1omg.opuscast

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import io.github.m1omg.opuscast.core.MediaSource
import io.github.m1omg.opuscast.core.RandomAccessSource
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

/** A video the user picked: a content:// document (or, rarely, a file:// path). */
data class Video(val ref: String, val name: String, val size: Long) {
    val uri: Uri get() = Uri.parse(ref)
}

/** Reading the user's files through Android's content providers. */
object Docs {
    fun isFile(ref: String) = ref.startsWith("file:") || ref.startsWith("/")
    private fun fileOf(ref: String) = File(if (ref.startsWith("file:")) Uri.parse(ref).path!! else ref)

    /** Name and size of a picked video; throws IOException if it can't be opened. */
    fun video(context: Context, uri: Uri): Video {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val f = File(uri.path ?: throw IOException("no path"))
            if (!f.canRead()) throw IOException(f.path)
            return Video(uri.toString(), f.name, f.length())
        }
        var name: String? = null
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0)
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size < 0) {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { size = it.statSize }
        }
        if (size < 0) throw IOException("unknown size")
        return Video(uri.toString(), name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "video", size)
    }

    fun displayName(context: Context, ref: String): String? {
        if (isFile(ref)) return fileOf(ref).name
        return runCatching {
            context.contentResolver.query(Uri.parse(ref), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    fun lastModified(context: Context, ref: String): Long {
        if (isFile(ref)) return fileOf(ref).lastModified()
        return runCatching {
            context.contentResolver.query(Uri.parse(ref), arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
            }
        }.getOrNull() ?: 0L
    }

    fun open(context: Context, ref: String): InputStream =
        if (isFile(ref)) FileInputStream(fileOf(ref))
        else context.contentResolver.openInputStream(Uri.parse(ref)) ?: throw IOException("can't open")

    fun readBytes(context: Context, ref: String, limit: Int = 32 shl 20): ByteArray = open(context, ref).use { s ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(65536)
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) throw IOException("file too big")
        }
        out.toByteArray()
    }

    fun canOpen(context: Context, ref: String): Boolean = runCatching { open(context, ref).close(); true }.getOrDefault(false)

    fun durationMs(context: Context, ref: String): Long? = try {
        val r = MediaMetadataRetriever()
        try {
            if (isFile(ref)) r.setDataSource(fileOf(ref).path) else r.setDataSource(context, Uri.parse(ref))
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            r.release()
        }
    } catch (e: Exception) {
        null
    }

    fun pfd(context: Context, ref: String): ParcelFileDescriptor =
        if (isFile(ref)) ParcelFileDescriptor.open(fileOf(ref), ParcelFileDescriptor.MODE_READ_ONLY)
        else context.contentResolver.openFileDescriptor(Uri.parse(ref), "r") ?: throw IOException("can't open")

    /** Positional reads for the subtitle parsers; null when the provider only gives a stream. */
    fun randomAccess(context: Context, ref: String): PfdRandomAccess? = try {
        val p = pfd(context, ref)
        val ra = PfdRandomAccess(p)
        if (ra.size > 0 && runCatching { ra.channel.position(0) }.isSuccess) ra else { ra.close(); null }
    } catch (e: Exception) {
        null
    }
}

class PfdRandomAccess(private val pfd: ParcelFileDescriptor) : RandomAccessSource, AutoCloseable {
    private val stream = FileInputStream(pfd.fileDescriptor)
    internal val channel = stream.channel
    override val size: Long = runCatching { channel.size() }.getOrDefault(pfd.statSize)
    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int = channel.read(ByteBuffer.wrap(buf, off, len), pos)
    override fun close() {
        runCatching { stream.close() }
        runCatching { pfd.close() }
    }
}

/** What the TV is served: the picked document, opened afresh for each range the TV asks for. */
class DocSource(private val context: Context, private val video: Video) : MediaSource {
    override val name = video.name
    override val size = video.size
    override val durationMs: Long? get() = Docs.durationMs(context, video.ref)

    override fun openAt(offset: Long): InputStream {
        val p = Docs.pfd(context, video.ref)
        val s = ParcelFileDescriptor.AutoCloseInputStream(p)
        try {
            s.channel.position(offset)
            if (s.channel.position() != offset) throw IOException("not seekable")
        } catch (e: IOException) { // a pipe: read up to the spot
            s.close()
            val again = Docs.open(context, video.ref)
            var left = offset
            val buf = ByteArray(65536)
            while (left > 0) {
                val n = again.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) {
                    again.close()
                    throw IOException("file ended early")
                }
                left -= n
            }
            return again
        }
        return s
    }
}
