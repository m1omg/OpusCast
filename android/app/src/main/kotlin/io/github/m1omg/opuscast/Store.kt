package io.github.m1omg.opuscast

import io.github.m1omg.opuscast.core.RecentItem
import io.github.m1omg.opuscast.core.RecentList
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What OpusCast remembers between runs. */
class Settings(
    var tvLocation: String? = null,
    var tvName: String? = null,
    var preview: Boolean = false,
    var intro: Boolean = false,
    val recent: RecentList = RecentList(),
    /** Seconds each photo stays on in a slideshow. */
    var slideSeconds: Int = 5,
    /** Cast the screen in 1080p (else 720p, lighter on the Wi-Fi). */
    var screenHd: Boolean = true,
    /** Show the screen on the TV again by itself when the TV leaves it. */
    var screenBringBack: Boolean = false,
    /** OpusCast's identity among the TV's sources: the same each time, so the TV knows it again. */
    var sourceId: String? = null,
)

/** Settings as JSON in the app's private files (like ~/.config/opuscast/config.json on Linux). */
class Store(dir: File) {
    private val file = File(dir, "config.json")

    fun load(): Settings = try {
        val o = JSONObject(file.readText())
        val tv = o.optJSONObject("tv")
        val items = o.optJSONArray("recent") ?: JSONArray()
        val recent = (0 until items.length()).mapNotNull { i ->
            val r = items.optJSONObject(i) ?: return@mapNotNull null
            val ref = r.optString("ref").ifEmpty { return@mapNotNull null }
            RecentItem(ref, r.optString("name", ref), r.optDouble("pos", 0.0), r.optDouble("dur", 0.0),
                r.optBoolean("watched", false), if (r.isNull("sub")) null else r.optString("sub").ifEmpty { null })
        }
        Settings(tv?.optString("location")?.ifEmpty { null }, tv?.optString("name")?.ifEmpty { null },
            o.optBoolean("preview", false), o.optBoolean("intro", false), RecentList(recent),
            o.optInt("slide_seconds", 5), o.optBoolean("screen_hd", true), o.optBoolean("screen_bring_back", false),
            o.optString("source_id").ifEmpty { null })
    } catch (e: Exception) {
        Settings()
    }

    fun save(s: Settings) {
        val o = JSONObject()
        if (s.tvLocation != null) o.put("tv", JSONObject().put("location", s.tvLocation).put("name", s.tvName ?: ""))
        o.put("preview", s.preview)
        o.put("intro", s.intro)
        o.put("slide_seconds", s.slideSeconds)
        o.put("screen_hd", s.screenHd)
        o.put("screen_bring_back", s.screenBringBack)
        if (s.sourceId != null) o.put("source_id", s.sourceId)
        o.put("recent", JSONArray().apply {
            for (r in s.recent.items) put(JSONObject().put("ref", r.ref).put("name", r.name).put("pos", r.pos)
                .put("dur", r.dur).put("watched", r.watched).put("sub", r.sub ?: JSONObject.NULL))
        })
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(o.toString(1))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
