package io.github.m1omg.opuscast.core

import kotlin.concurrent.thread

private fun sleepMs(ms: Long) = Thread.sleep(ms)
private fun now() = System.nanoTime()
private fun secs(s: Double) = (s * 1e9).toLong()

/** Switch the TV to one of the playing video's subtitle files right away (Samsung). path=null shows
 *  the empty file, i.e. subtitles off. Returns "ok", or "unsupported" when the TV only takes
 *  subtitles together with the video (then restart playback with the subtitle announced). */
fun showSubtitle(tv: Tv, server: MediaServer, media: Media, path: String?): String {
    if (!tv.canSwitchSubtitles()) return "unsupported"
    synchronized(media.captionLock) {
        media.captionSeq += 1
        sendCaption(tv, server, media, path)
    }
    return "ok"
}

private fun sendCaption(tv: Tv, server: MediaServer, media: Media, path: String?) {
    tv.showCaption(server.url(media, tv.host), server.urlFor(tv.host, path ?: media.blankPath), media.title)
    media.captionSentNanos = now()
}

/** Send the start-up subtitle again once the video really plays.
 *
 *  Switched on while the video is still starting or seeking to the resume point, a Samsung player can
 *  list the subtitle as on without drawing it. The same command a little later shows it. Gives up
 *  when the subtitle is changed from the phone, the video stops or another one plays. */
internal fun reshowWhenPlaying(tv: Tv, server: MediaServer, media: Media, seq: Int, startAt: Double, seenAtStart: Boolean) {
    var seen = seenAtStart
    val floor = maxOf(0.0, startAt - 15) // seeks land on a keyframe, which can be a little earlier
    var last: Int? = null
    var steady = 0
    val deadline = now() + secs(300.0)
    while (true) {
        sleepMs(1000)
        if (media.captionSeq != seq || server.current !== media || now() > deadline) return
        val state: String
        val p: Tv.Position
        try {
            state = tv.state()
            p = tv.position()
        } catch (e: TvException) {
            last = null
            continue
        }
        if (p.uri != null && p.uri.isNotEmpty() && media.urlPath !in p.uri) return // another video is on
        if (state != "PLAYING") {
            if (seen && (state == "STOPPED" || state == "NO_MEDIA_PRESENT")) return
            last = null
            continue
        }
        seen = true
        val pos = p.pos
        if (pos != null && last != null && pos > last) {
            steady += 1
            if (pos >= floor || steady >= 15) break // past the resume point (or it never got there)
        }
        last = pos
    }
    for (pause in longArrayOf(1000, 4000)) {
        sleepMs(pause)
        synchronized(media.captionLock) {
            if (media.captionSeq != seq || server.current !== media) return
            try {
                sendCaption(tv, server, media, media.subPath)
            } catch (e: TvException) {
                return
            }
        }
    }
}

/** What [tv] is told about [media]: the DIDL-Lite item with this server's URLs. */
fun itemInfo(tv: Tv, server: MediaServer, media: Media): ItemInfo = ItemInfo(
    title = media.title,
    mime = media.mime,
    features = media.features,
    size = if (media.live != null) null else media.source.size,
    kind = media.kind,
    subUrl = if (media.kind == MediaKind.VIDEO) server.subUrl(media, tv.host) else null,
    durationMs = media.knownDurationMs,
    artist = media.artist,
    album = media.album,
    coverUrl = media.coverPath?.let { server.urlFor(tv.host, it) },
    coverJpeg = media.coverPath?.let { media.extras[it]?.first } == "image/jpeg",
)

/** Play, right after SetAVTransportURI. Some TVs are still busy taking the new item and answer 701
 *  ("transition not available"): ask again a few times. Some start by themselves meanwhile, which counts. */
internal fun playAfterSet(tv: Tv, pauseMs: Long = 700, retries: Int = 4) {
    var left = retries
    while (true) {
        try {
            tv.play()
            return
        } catch (e: TvException) {
            if (e.upnpCode != 701 || left <= 0) throw e
            val state = try {
                tv.state()
            } catch (x: TvException) {
                null
            }
            if (state == "PLAYING" || state == "TRANSITIONING") return
            left -= 1
            sleepMs(pauseMs)
        }
    }
}

/** Hand published media to the TV and start it, optionally resuming at [startAt] seconds and switching
 *  its subtitle on. Returns the showSubtitle() result, or null without subtitles. Blocks for a while.
 *  [stopFirst] = false goes from one photo to the next without the TV's stop screen in between (a TV
 *  that won't take a new item while it shows one is stopped after all). */
fun playMedia(tv: Tv, server: MediaServer, media: Media, startAt: Double = 0.0, waitSeconds: Double = 20.0,
              subsOn: Boolean = false, stopFirst: Boolean = true): String? {
    val url = server.url(media, tv.host)
    val sub = if (media.kind == MediaKind.VIDEO) server.subUrl(media, tv.host) else null
    val item = itemInfo(tv, server, media)
    if (stopFirst) {
        try {
            tv.stop()
        } catch (e: TvException) {
            // nothing was playing
        }
        tv.setMedia(url, item)
    } else {
        try {
            tv.setMedia(url, item)
        } catch (e: TvException) {
            if (e.reason != TvException.Reason.REFUSED) throw e
            try {
                tv.stop()
            } catch (x: TvException) {
                // it wasn't playing either
            }
            tv.setMedia(url, item)
        }
    }
    playAfterSet(tv)
    val seekable = media.kind == MediaKind.VIDEO || media.kind == MediaKind.AUDIO
    var playing = false
    if ((startAt > 1 && seekable) || (sub != null && subsOn)) {
        val deadline = now() + secs(waitSeconds)
        while (now() < deadline) {
            try {
                if (tv.state() == "PLAYING") {
                    playing = true
                    if (startAt > 1 && seekable) tv.seek(startAt)
                    break
                }
            } catch (e: TvException) {
                // not ready yet
            }
            sleepMs(500)
        }
    }
    if (sub != null && subsOn) {
        if (!tv.canSwitchSubtitles()) return "unsupported" // announced with the video; the TV's own settings decide
        val deadline = now() + secs(5.0) // let the TV register the announced file first
        while (now() < deadline) {
            try {
                if (tv.captionState().first.any { it["uri"] == sub }) break
            } catch (e: TvException) {
                break
            }
            sleepMs(500)
        }
        val result = showSubtitle(tv, server, media, media.subPath)
        val seq = media.captionSeq
        thread(isDaemon = true, name = "opuscast-reshow") { reshowWhenPlaying(tv, server, media, seq, startAt, playing) }
        return result
    }
    return null
}

fun startPlayback(tv: Tv, server: MediaServer, source: MediaSource, startAt: Double = 0.0,
                  subtitle: ByteArray? = null, subsOn: Boolean = false): Media {
    val media = server.publish(source, subtitle)
    media.captions = playMedia(tv, server, media, startAt, subsOn = subsOn)
    return media
}
