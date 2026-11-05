package io.github.m1omg.opuscast

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import io.github.m1omg.opuscast.core.stemOf

/** What the TV and the lock screen show for a song. [cover] is a JPEG for the TV, [art] the same for the phone. */
class TrackInfo(
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val cover: ByteArray?,
    val art: Bitmap?,
)

object Music {
    /** The song's tags and cover (Android's own tag reader). Blocks: not on the main thread. */
    fun read(context: Context, item: Video): TrackInfo {
        val r = MediaMetadataRetriever()
        try {
            if (Docs.isFile(item.ref)) r.setDataSource(item.uri.path ?: item.ref) else r.setDataSource(context, item.uri)
            fun tag(key: Int) = r.extractMetadata(key)?.trim()?.ifEmpty { null }
            val picture = runCatching { r.embeddedPicture }.getOrNull()
            val (cover, art) = picture?.let { coverOf(it) } ?: (null to null)
            return TrackInfo(
                title = tag(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: stemOf(item.name),
                artist = tag(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                album = tag(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                durationMs = tag(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                cover = cover,
                art = art,
            )
        } catch (e: Exception) { // no tags it can read: the file name will do
            return TrackInfo(stemOf(item.name), null, null, null, null, null)
        } finally {
            runCatching { r.release() }
        }
    }

    /** A JPEG cover goes to the TV as it is; PNG and others become a JPEG first (what TVs show for sure). */
    private fun coverOf(picture: ByteArray): Pair<ByteArray, Bitmap?>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(picture, 0, picture.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 512) sample *= 2
        val art = BitmapFactory.decodeByteArray(picture, 0, picture.size, BitmapFactory.Options().apply { inSampleSize = sample })
        val isJpeg = picture.size > 2 && picture[0] == 0xFF.toByte() && picture[1] == 0xD8.toByte()
        if (isJpeg) return picture to art
        val full = BitmapFactory.decodeByteArray(picture, 0, picture.size) ?: return null
        return Photos.jpeg(Photos.fit(full, 1000, 1000)) to art
    }
}
