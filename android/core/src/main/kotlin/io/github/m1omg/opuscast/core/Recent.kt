package io.github.m1omg.opuscast.core

/** A video played before: where it stopped and which subtitle was on. */
data class RecentItem(
    /** How the app opens it again (a path, or a document URI on Android). */
    val ref: String,
    val name: String,
    val pos: Double = 0.0,
    val dur: Double = 0.0,
    val watched: Boolean = false,
    /** SubtitleEntry.id that was on, or null for off. */
    val sub: String? = null,
)

/** The "Recent" list: newest first, at most [limit] videos. */
class RecentList(items: List<RecentItem> = emptyList(), private val limit: Int = 20) {
    var items: List<RecentItem> = items.take(limit)
        private set

    operator fun get(ref: String): RecentItem? = items.firstOrNull { it.ref == ref }

    /** Update (or add) a video and move it to the top. Returns the items that fell off the end. */
    fun remember(ref: String, name: String? = null, pos: Double? = null, dur: Double? = null, watched: Boolean? = null,
                 sub: String? = KEEP): List<RecentItem> {
        val old = get(ref)
        var item = old ?: RecentItem(ref, name ?: ref)
        if (name != null) item = item.copy(name = name)
        if (pos != null) {
            item = item.copy(pos = Math.round(pos * 10) / 10.0)
            if (pos > 5) item = item.copy(watched = false)
        }
        if (dur != null && dur > 0) item = item.copy(dur = dur)
        if (watched != null) item = item.copy(watched = watched)
        if (sub !== KEEP) item = item.copy(sub = sub)
        val rest = items.filter { it.ref != ref }
        items = listOf(item) + rest.take(limit - 1)
        return rest.drop(limit - 1)
    }

    fun forget(ref: String) {
        items = items.filter { it.ref != ref }
    }

    /** Where to start: the saved spot, unless it was watched to the end or barely started. */
    fun resumeAt(ref: String): Double {
        val item = get(ref) ?: return 0.0
        return if (!item.watched && item.pos > 5) item.pos else 0.0
    }

    companion object {
        /** Pass as `sub` to leave the remembered subtitle alone. */
        val KEEP: String = String(charArrayOf('\u0000'))

        /** Stopped this close to the end counts as watched. */
        fun isWatched(pos: Double, dur: Double) = dur > 0 && (pos >= dur - 90 || pos >= dur * 0.95)
    }
}
