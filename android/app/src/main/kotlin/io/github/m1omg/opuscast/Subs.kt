package io.github.m1omg.opuscast

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import io.github.m1omg.opuscast.core.Cancel
import io.github.m1omg.opuscast.core.SubtitleEntry
import io.github.m1omg.opuscast.core.TvException
import io.github.m1omg.opuscast.core.convertSubtitle
import io.github.m1omg.opuscast.core.embeddedEntries
import io.github.m1omg.opuscast.core.extractEmbeddedSubtitle
import io.github.m1omg.opuscast.core.findSubtitleFiles
import io.github.m1omg.opuscast.core.langFromFilename
import io.github.m1omg.opuscast.core.stemOf
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Can OpusCast see the other files in a video's folder? */
enum class FolderAccess {
    /** Yes: subtitle files next to the video are listed. */
    GRANTED,
    /** After the user allows that folder (system folder picker). */
    NEED_FOLDER,
    /** Only with "all files" access: Android won't hand out this folder (Download, storage root). */
    NEED_ALL_FILES,
    /** The video doesn't come from a folder on this phone (cloud, another app). */
    NONE,
}

class FoundSubs(val entries: List<SubtitleEntry>, val access: FolderAccess, val folderUri: Uri?)

/** Where a video lives on the phone's storage: volume ("primary" or an SD card id) and folder. */
private data class Place(val volume: String, val dir: String) {
    val docId get() = "$volume:$dir"
    val path: File get() = if (volume == "primary") File(Environment.getExternalStorageDirectory(), dir)
        else File("/storage/$volume", dir)
}

private const val EXTERNAL = "com.android.externalstorage.documents"
private const val DOWNLOADS = "com.android.providers.downloads.documents"

object Subs {
    private fun placeFromPath(path: String): Place? {
        val p = File(path).absoluteFile.parent ?: return null
        val primary = Environment.getExternalStorageDirectory().absolutePath
        for (root in listOf(primary, "/sdcard", "/storage/self/primary")) {
            if (p == root) return Place("primary", "")
            if (p.startsWith("$root/")) return Place("primary", p.substring(root.length + 1))
        }
        val m = Regex("""^/storage/([0-9A-Fa-f]{4}-[0-9A-Fa-f]{4})(?:/(.*))?$""").find(p) ?: return null
        return Place(m.groupValues[1].uppercase(), m.groupValues[2])
    }

    private fun placeOf(context: Context, uri: Uri): Place? {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return uri.path?.let { placeFromPath(it) }
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        if (uri.authority == EXTERNAL && docId != null && ':' in docId) {
            val volume = docId.substringBefore(':')
            val rel = docId.substringAfter(':')
            return Place(if (volume.equals("primary", true)) "primary" else volume.uppercase(), rel.substringBeforeLast('/', ""))
        }
        if (uri.authority == DOWNLOADS && docId != null && docId.startsWith("raw:")) return placeFromPath(docId.substring(4))
        val cr = context.contentResolver
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                cr.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.VOLUME_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) {
                        val rel = c.getString(0).trim('/')
                        val vol = c.getString(1) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
                        return Place(if (vol == MediaStore.VOLUME_EXTERNAL_PRIMARY || vol == "external") "primary" else vol.uppercase(), rel)
                    }
                }
            }
        }
        @Suppress("DEPRECATION")
        runCatching {
            cr.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return placeFromPath(c.getString(0))
            }
        }
        return null
    }

    fun hasAllFiles(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** A folder the user allowed earlier (system folder picker) that holds [place], as (tree, its folder id). */
    private fun treeFor(context: Context, place: Place): Uri? {
        for (perm in context.contentResolver.persistedUriPermissions) {
            if (!perm.isReadPermission) continue
            val tree = perm.uri
            if (tree.authority != EXTERNAL || !runCatching { DocumentsContract.isTreeUri(tree) }.getOrDefault(false)) continue
            val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: continue
            val vol = id.substringBefore(':')
            val dir = id.substringAfter(':', "").trim('/')
            val sameVolume = if (place.volume == "primary") vol.equals("primary", true) else vol.equals(place.volume, true)
            if (sameVolume && (dir.isEmpty() || place.dir == dir || place.dir.startsWith("$dir/"))) return tree
        }
        return null
    }

    /** Does [tree] (just picked by the user) cover [video]'s folder? */
    fun treeCovers(context: Context, tree: Uri, video: Video): Boolean {
        val place = placeOf(context, video.uri) ?: return false
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return false
        val vol = id.substringBefore(':')
        val dir = id.substringAfter(':', "").trim('/')
        val sameVolume = if (place.volume == "primary") vol.equals("primary", true) else vol.equals(place.volume, true)
        return tree.authority == EXTERNAL && sameVolume && (dir.isEmpty() || place.dir == dir || place.dir.startsWith("$dir/"))
    }

    private class Listing(val names: List<String>, val refs: Map<String, String>, val dirs: Map<String, Listing>)

    private fun listTree(context: Context, tree: Uri, docId: String, depth: Int): Listing {
        val names = ArrayList<String>()
        val refs = HashMap<String, String>()
        val dirs = HashMap<String, Listing>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (depth == 0 && name.lowercase() in io.github.m1omg.opuscast.core.SUB_DIRS) dirs[name] = listTree(context, tree, id, 1)
                } else {
                    names += name
                    refs[name] = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()
                }
            }
        }
        return Listing(names, refs, dirs)
    }

    private fun listFiles(dir: File, depth: Int): Listing {
        val names = ArrayList<String>()
        val refs = HashMap<String, String>()
        val dirs = HashMap<String, Listing>()
        for (f in dir.listFiles() ?: emptyArray()) {
            if (f.isDirectory) {
                if (depth == 0 && f.name.lowercase() in io.github.m1omg.opuscast.core.SUB_DIRS) dirs[f.name] = listFiles(f, 1)
            } else {
                names += f.name
                refs[f.name] = Uri.fromFile(f).toString()
            }
        }
        return Listing(names, refs, dirs)
    }

    /** The subtitles for a video: files next to it (if OpusCast may look there) and tracks inside it. */
    fun find(context: Context, video: Video): FoundSubs {
        val place = placeOf(context, video.uri)
        var access = FolderAccess.NONE
        var listing: Listing? = null
        if (place != null) {
            val tree = treeFor(context, place)
            listing = try {
                when {
                    hasAllFiles(context) && place.path.isDirectory -> listFiles(place.path, 0)
                    tree != null -> listTree(context, tree, place.docId, 0)
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
            access = when {
                listing != null -> FolderAccess.GRANTED
                Build.VERSION.SDK_INT >= 30 && (place.dir.isEmpty() || place.dir.equals("Download", true) ||
                    place.dir.startsWith("Android/", true)) -> FolderAccess.NEED_ALL_FILES
                else -> FolderAccess.NEED_FOLDER
            }
        }
        val entries = ArrayList<SubtitleEntry>()
        if (listing != null) {
            val found = findSubtitleFiles(video.name, listing.names, listing.dirs.mapValues { it.value.names })
            for (f in found) {
                val ref = (if (f.dir == null) listing.refs[f.name] else listing.dirs[f.dir]?.refs?.get(f.name)) ?: continue
                entries += SubtitleEntry(id = "file:$ref", kind = SubtitleEntry.Kind.FILE, ref = ref, fileName = f.name,
                    lang = f.lang, ok = !f.pictureBased, why = if (f.pictureBased) SubtitleEntry.Why.PICTURE else null)
            }
        }
        Docs.randomAccess(context, video.ref)?.use { entries += embeddedEntries(it) }
        val folderUri = place?.let { DocumentsContract.buildDocumentUri(EXTERNAL, it.docId) }
        return FoundSubs(entries, access, folderUri)
    }

    /** A subtitle file the user picked themselves. */
    fun fileEntry(context: Context, ref: String): SubtitleEntry {
        val name = Docs.displayName(context, ref) ?: Uri.parse(ref).lastPathSegment?.substringAfterLast('/') ?: "subtitles.srt"
        return SubtitleEntry(id = "file:$ref", kind = SubtitleEntry.Kind.FILE, ref = ref, fileName = name,
            lang = langFromFilename(stemOf(name)))
    }

    /** The subtitle as UTF-8 SRT for the TV. Built-in tracks are read once and cached. Throws TvException. */
    fun prepare(context: Context, entry: SubtitleEntry, video: Video, cancel: Cancel? = null,
                progress: ((Float) -> Unit)? = null): ByteArray {
        if (entry.kind == SubtitleEntry.Kind.FILE) {
            val raw = try {
                Docs.readBytes(context, entry.ref!!)
            } catch (e: Exception) {
                throw TvException(TvException.Reason.SUBTITLE_UNREADABLE, e.message ?: e.javaClass.simpleName, e)
            }
            return convertSubtitle(entry.fileName ?: "subtitles.srt", raw)
        }
        val key = MessageDigest.getInstance("SHA-1")
            .digest("${video.ref}|${video.size}|${Docs.lastModified(context, video.ref)}|${entry.track}".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val cached = File(File(context.cacheDir, "subs"), "$key.srt")
        if (cached.isFile) runCatching { return cached.readBytes() }
        val ra = Docs.randomAccess(context, video.ref)
            ?: throw TvException(TvException.Reason.SUBTITLE_UNREADABLE, "the video can't be read from any point")
        val data = try {
            ra.use { extractEmbeddedSubtitle(it, entry.track!!, cancel, progress) }
        } catch (e: IOException) {
            throw TvException(TvException.Reason.SUBTITLE_UNREADABLE, e.message ?: e.javaClass.simpleName, e)
        }
        runCatching {
            cached.parentFile?.mkdirs()
            val tmp = File(cached.parentFile, cached.name + ".tmp")
            tmp.writeBytes(data)
            tmp.renameTo(cached)
        }
        return data
    }
}
