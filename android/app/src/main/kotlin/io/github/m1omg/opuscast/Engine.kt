package io.github.m1omg.opuscast

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.LocaleList
import io.github.m1omg.opuscast.core.Cancel
import io.github.m1omg.opuscast.core.Dlna
import io.github.m1omg.opuscast.core.LiveHub
import io.github.m1omg.opuscast.core.Media
import io.github.m1omg.opuscast.core.MediaKind
import io.github.m1omg.opuscast.core.SinkFormats
import io.github.m1omg.opuscast.core.mimeFor
import io.github.m1omg.opuscast.core.stemOf
import io.github.m1omg.opuscast.core.MediaServer
import io.github.m1omg.opuscast.core.NetDevice
import io.github.m1omg.opuscast.core.RecentItem
import io.github.m1omg.opuscast.core.RecentList
import io.github.m1omg.opuscast.core.SubtitleEntry
import io.github.m1omg.opuscast.core.Tv
import io.github.m1omg.opuscast.core.TvException
import io.github.m1omg.opuscast.core.autoSubtitle
import io.github.m1omg.opuscast.core.captionUris
import io.github.m1omg.opuscast.core.fmtTime
import io.github.m1omg.opuscast.core.findTv
import io.github.m1omg.opuscast.core.parseLastChange
import io.github.m1omg.opuscast.core.playMedia
import io.github.m1omg.opuscast.core.probeHosts
import io.github.m1omg.opuscast.core.scanNetwork
import io.github.m1omg.opuscast.core.showSubtitle
import io.github.m1omg.opuscast.core.FileSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.net.URI
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private fun now(): Double = SystemClock.elapsedRealtimeNanos() / 1e9

enum class Page { PICK, PLAYER }

data class BannerAction(val label: String, val run: () -> Unit)

data class Banner(val text: String, val kind: Kind = Kind.INFO, val actions: List<BannerAction> = emptyList(), val id: Int = 0) {
    enum class Kind { INFO, WARNING, ERROR }
}

data class SubOption(val entry: SubtitleEntry?, val label: String, val selected: Boolean)

data class SubMenu(
    val searching: Boolean,
    val options: List<SubOption>,
    /** (label, why) of subtitles that can't be used. */
    val unusable: List<Pair<String, String>>,
    val noneFound: Boolean,
    val notes: List<String>,
    val folderAccess: FolderAccess,
)

/** What the player page shows: a video (with the intro before it), music, photos or the phone's screen. */
enum class PlayerMode { VIDEO, INTRO, MUSIC, PHOTO, SCREEN }

data class PlayerUi(
    val title: String,
    val status: String,
    val playing: Boolean,
    val isIntro: Boolean,
    val showSubtitles: Boolean,
    val subLabel: String,
    val subMenu: SubMenu?,
    val seekable: Boolean,
    val tvName: String?,
    val mode: PlayerMode = PlayerMode.VIDEO,
    val artist: String? = null,
    val album: String? = null,
    /** The album cover, or the photo on the TV. */
    val art: Bitmap? = null,
    /** Position in the songs or photos picked together (from 0), and how many there are. */
    val index: Int = 0,
    val count: Int = 0,
    val slideshow: Boolean = false,
    val slideSeconds: Int = 5,
    /** Screen casting: with the sound apps play (not silence). */
    val sound: Boolean = false,
)

data class UiState(
    val tvName: String? = null,
    val tvHost: String? = null,
    /** Still looking for the remembered / first TV. */
    val searchingTv: Boolean = true,
    val statusLine: String = "",
    val devices: List<NetDevice> = emptyList(),
    val scanning: Boolean = false,
    val recent: List<RecentItem> = emptyList(),
    val banner: Banner? = null,
    val page: Page = Page.PICK,
    val player: PlayerUi? = null,
    val volume: Int? = null,
    val previewOn: Boolean = false,
    val introOn: Boolean = false,
    val introProgress: Float? = null,
    val sessionActive: Boolean = false,
    /** The chosen renderer is a speaker: music only. */
    val musicOnly: Boolean = false,
    /** Screen casting in 1080p (else 720p). */
    val screenHd: Boolean = true,
)

/** Position on the TV, ticking 4 times a second (estimated between the TV's answers). */
data class Clock(val pos: Double, val dur: Double)

private enum class Kind { MOVIE, INTRO, MUSIC, PHOTO, SCREEN }

private class Hold(val target: Double, val since: Double, val limit: Double)

private class NextUp(val video: Video, val startAt: Double, val subtitle: Pair<SubtitleEntry, ByteArray>?, val found: FoundSubs?)

/** Songs or photos picked together, played one after another. */
private class Playlist(val kind: Kind, val items: List<Video>, var index: Int) {
    /** URL paths of the photos sent so far: the TV may still report the previous one for a moment. */
    val paths = HashSet<String>()
    val current: Video get() = items[index]
}

private class Session(
    val kind: Kind, val video: Video, val media: Media, val next: NextUp?, val started: Double, val gen: Int,
    var found: FoundSubs?, var subEntry: SubtitleEntry?, var subLoading: Boolean,
    val startAt: Double, val subsOn: Boolean,
) {
    /** Go from the last photo to this one without the TV's stop screen between. */
    var stopFirst = true
    var art: Bitmap? = null
    var artist: String? = null
    var album: String? = null
    /** The song's length from its tags (the TV's answer wins when it gives one). */
    var trackDurS = 0.0
    /** Polls in a row that showed the TV playing something that isn't ours. */
    var foreignPolls = 0
    var seenPlaying = false
    /** Starting it on the TV failed, but the TV may still play it: a Samsung asks "Allow this device?"
     *  the first time, holds the phone's commands meanwhile and carries them out once allowed. */
    var startError: Exception? = null
    var startFailedAt = 0.0
    /** When the TV began answering again after the failure (0 = not yet). */
    var answeringSince = 0.0
    var retried = false
    var waitBanner = 0
    var subs: MutableList<SubtitleEntry>? = found?.entries?.toMutableList()
    var subOn = false
    var subTvChanged = false
    var captionApi: Boolean? = null
    /** subtitle id -> URL path on the server */
    val paths = HashMap<String, String>()
    var subCancel: Cancel? = null
}

private class PollResult(val tv: Tv) {
    var state = "UNKNOWN"
    var pos: Int? = null
    var dur: Int? = null
    var uri: String? = null
    var volume: Int? = null
    var error: TvException? = null
}

/**
 * Everything OpusCast does, apart from drawing it: finding the TV, starting videos, following what the
 * TV does, subtitles, resume, the phone preview. Lives as long as the app process; all state is touched
 * on the main thread only (like the GTK main loop in the desktop app), TV commands run one after another
 * on a worker thread.
 */
class Engine(private val app: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val store = Store(app.filesDir)
    val settings: Settings = store.load()
    private val server = MediaServer()
    @Volatile private var lan = LanInfo(null, 24, null)
    private var tv: Tv? = null
    private var session: Session? = null
    private var gen = 0
    private var tvState = "UNKNOWN"
    private var tvPos = 0.0
    private var tvPosRaw: Int? = null
    private var posSeen = 0.0
    private var tvDur = 0.0
    private var hold: Hold? = null
    private var volume: Int? = null
    private var volTouched = 0.0
    private var volumeTask: Runnable? = null
    private var lastSave = 0.0
    private var pollErrors = 0
    private var devices = listOf<NetDevice>()
    private var scanning = false
    private var scannedAt = 0.0
    /** What to start once a TV is found. */
    private var pendingPlay: (() -> Unit)? = null
    /** The chosen TV's format list (unknown until it answers). */
    private var tvSink: SinkFormats = SinkFormats.UNKNOWN
    private var playlist: Playlist? = null
    private var slideshow = false
    private var slideTask: Runnable? = null
    /** Photos converted ahead of time, by document. */
    private val photoCache = LinkedHashMap<String, PreparedPhoto>()
    private val photoWork = HashSet<String>()
    private var screen: ScreenCast? = null
    /** What the player page shows until the session (re)starts: the last photo stays while the next one is made. */
    private var playerArt: Bitmap? = null
    private var playerMode = PlayerMode.VIDEO
    private var eventSids = mapOf<String, String>()
    private var eventsPending = false // a SUBSCRIBE is on its way
    private var lastCaptionEvent = 0.0
    private var lastCaptionCmd = 0.0
    private var renewTask: Runnable? = null
    private var banner: Banner? = null
    private var bannerId = 0
    private var page = Page.PICK
    private var playerTitle = ""
    private var playerStatus = ""
    private var statusLine = ""
    private var introProgress: Float? = null
    private var introRendering = false
    private var searchingTv = true
    private var uiVisible = true
    private var scrubbing = false
    private var lastScrub = 0.0
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "opuscast-tv").apply { isDaemon = true } }
    private val poller = Poller()
    val preview = Preview(app) { onPreviewError(it) }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui
    private val _clock = MutableStateFlow(Clock(0.0, 0.0))
    val clock: StateFlow<Clock> = _clock

    init {
        server.addressFor = { host -> lan.addressFor(host) }
        server.onEvent = { service, sid, body -> main.post { onEvent(service, sid, body) } }
        statusLine = str(R.string.looking_for_tv)
        poller.start()
        main.postDelayed(::tick, 250)
        findTv()
        publish()
    }

    private fun str(id: Int, vararg args: Any): String = app.getString(id, *args)

    // ------------------------------------------------------------ state for the UI

    private fun publish() {
        val s = session
        val pl = playlist
        val mode = when (s?.kind) {
            Kind.INTRO -> PlayerMode.INTRO
            Kind.MUSIC -> PlayerMode.MUSIC
            Kind.PHOTO -> PlayerMode.PHOTO
            Kind.SCREEN -> PlayerMode.SCREEN
            Kind.MOVIE -> PlayerMode.VIDEO
            null -> playerMode // getting the next one ready
        }
        val player = if (page == Page.PLAYER) PlayerUi(
            title = playerTitle,
            status = playerStatus,
            playing = s != null && s.seenPlaying && tvState == "PLAYING",
            isIntro = s?.kind == Kind.INTRO,
            showSubtitles = s?.kind == Kind.MOVIE,
            subLabel = subLabel(),
            subMenu = subMenu(),
            seekable = s != null && (s.kind == Kind.MOVIE || s.kind == Kind.INTRO || s.kind == Kind.MUSIC) && tvDur > 0,
            tvName = tv?.name,
            mode = mode,
            artist = s?.artist,
            album = s?.album,
            art = s?.art ?: playerArt,
            index = pl?.index ?: 0,
            count = pl?.items?.size ?: 0,
            slideshow = slideshow,
            slideSeconds = settings.slideSeconds,
            sound = screen?.capturingSound == true,
        ) else null
        _ui.value = UiState(
            tvName = tv?.name, tvHost = tv?.host, searchingTv = searchingTv, statusLine = statusLine, devices = devices, scanning = scanning,
            recent = settings.recent.items, banner = banner, page = page, player = player, volume = volume,
            previewOn = settings.preview, introOn = settings.intro, introProgress = introProgress,
            sessionActive = s != null || (pl != null && page == Page.PLAYER), musicOnly = musicOnly, screenHd = settings.screenHd,
        )
    }

    /** A speaker that said it plays no video: music only. */
    private val musicOnly: Boolean get() = tvSink.known && tvSink.playsVideo == false

    private fun musicOnlyInfo() {
        showInfo(str(R.string.music_only_tv, tv?.name ?: ""), Banner.Kind.WARNING,
            listOf(BannerAction(str(R.string.choose_tv)) { openDevices?.invoke() }))
    }

    private fun setStatusLine(text: String) {
        statusLine = text
        publish()
    }

    fun showInfo(text: String, kind: Banner.Kind = Banner.Kind.INFO, actions: List<BannerAction> = emptyList()) {
        banner = Banner(text, kind, actions, ++bannerId)
        publish()
    }

    fun clearInfo() {
        banner = null
        publish()
    }

    /** A banner button was pressed (the banner goes away first, like a GTK InfoBar response). */
    fun bannerAction(action: BannerAction) {
        banner = null
        publish()
        action.run()
    }

    fun errorText(e: Throwable): String = when (e) {
        is TvException -> when (e.reason) {
            TvException.Reason.CANT_PLAY_MEDIA -> str(R.string.err_cant_play_media)
            TvException.Reason.UNSUPPORTED -> str(R.string.err_unsupported, e.detail)
            TvException.Reason.REFUSED -> str(R.string.err_refused, e.detail)
            TvException.Reason.UNREACHABLE -> str(R.string.err_unreachable, e.detail)
            TvException.Reason.NO_PORT -> str(R.string.err_no_port, e.detail)
            TvException.Reason.SUBTITLE_UNREADABLE -> str(R.string.err_sub_unreadable, e.detail)
            TvException.Reason.SUBTITLE_BAD -> str(R.string.err_sub_bad, e.detail)
            TvException.Reason.FILE_UNREADABLE -> str(R.string.err_file, e.detail)
        }
        else -> e.message ?: e.javaClass.simpleName
    }

    /** The app window became visible or hidden (polling the TV while nobody looks is wasted battery). */
    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        poller.idle = !visible
        if (visible) poller.wake()
    }

    // ------------------------------------------------------------ background helpers

    private fun <T> submit(fn: () -> T, done: ((T) -> Unit)? = null) {
        worker.execute {
            val r = try {
                fn()
            } catch (e: Exception) {
                main.post { onWorkerError(e) }
                return@execute
            }
            if (done != null) main.post { done(r) }
        }
    }

    private inner class Poller : Thread("opuscast-poll") {
        @Volatile var tv: Tv? = null
        @Volatile var active = false
        @Volatile var idle = false
        /** now() until which only the state is asked (see run); 0 after a seek. */
        @Volatile var stateOnlyUntil = 0.0
        private val wakeQ = LinkedBlockingQueue<Unit>()

        init {
            isDaemon = true
        }

        fun wake() {
            wakeQ.offer(Unit)
        }

        override fun run() {
            var lastVolume = 0.0
            var tailDone = false
            while (true) {
                val t = tv
                if (t == null || (idle && !active)) {
                    wakeQ.poll(1, TimeUnit.SECONDS)
                    wakeQ.clear()
                    continue
                }
                val r = PollResult(t)
                try {
                    r.state = t.state()
                    // Samsung TVs reportedly stop answering for a while when asked for the position
                    // right at the end of a video, so for the last few seconds only the state is asked
                    if (now() >= stateOnlyUntil) {
                        val p = t.position()
                        r.pos = p.pos
                        r.dur = p.duration
                        r.uri = p.uri
                        val pos = p.pos
                        val dur = p.duration
                        if (active && r.state == "PLAYING" && pos != null && dur != null && dur > 10 && pos >= dur - 3) {
                            if (!tailDone) {
                                tailDone = true
                                stateOnlyUntil = now() + 6
                            }
                        } else {
                            tailDone = false
                        }
                    }
                    if (now() - lastVolume > 4) {
                        lastVolume = now()
                        try {
                            r.volume = t.getVolume()
                        } catch (e: TvException) {
                            // not every TV says
                        }
                    }
                } catch (e: TvException) {
                    r.error = e
                }
                main.post { onPoll(r) }
                wakeQ.poll(if (r.error != null) 2000 else if (active) 500 else 5000, TimeUnit.MILLISECONDS)
                wakeQ.clear()
            }
        }
    }

    // ------------------------------------------------------------ TV selection & events

    fun findTv() {
        searchingTv = true
        setStatusLine(str(R.string.looking_for_tv))
        val location = settings.tvLocation
        thread(isDaemon = true, name = "opuscast-find") {
            val info = Lan.info(app)
            lan = info
            val found = try {
                Lan.withMulticast(app) { findTv(location = location, bindAddress = info.address) }
            } catch (e: Exception) {
                null
            }
            main.post { foundTv(found, info) }
        }
    }

    private fun foundTv(found: Tv?, info: LanInfo) {
        searchingTv = false
        if (found != null) {
            selectTv(found)
        } else if (tv == null) {
            statusLine = str(R.string.no_tv_found)
            showInfo(if (info.address == null) str(R.string.no_wifi) else str(R.string.couldnt_find_tv), Banner.Kind.WARNING,
                listOf(BannerAction(str(R.string.search_again)) { findTv() },
                    BannerAction(str(R.string.show_devices)) { openDevices?.invoke() }))
        }
    }

    /** Set by the UI: opens the device list (for the "Show devices" banner button). */
    var openDevices: (() -> Unit)? = null

    fun selectTv(newTv: Tv) {
        val old = tv
        if (old != null && newTv.location != old.location && session != null) stop()
        tv = newTv
        if (old != null && old.location != newTv.location) unsubscribeEvents(old)
        poller.tv = newTv
        poller.wake()
        pollErrors = 0
        statusLine = str(R.string.connected_to, newTv.name)
        settings.tvLocation = newTv.location
        settings.tvName = newTv.name
        save()
        banner = null
        if (old == null || old.location != newTv.location) tvSink = SinkFormats.UNKNOWN
        subscribeEvents(newTv)
        submit({ newTv.sink() }) { formats -> if (tv === newTv) { tvSink = formats; publish() } }
        publish()
        pendingPlay?.let {
            pendingPlay = null
            it()
        }
    }

    /** No TV yet: start [what] once one is found. */
    private fun waitForTv(what: () -> Unit): Boolean {
        if (tv != null) return false
        pendingPlay = what
        showInfo(str(R.string.waiting_for_tv))
        return true
    }

    /** "Enter the TV's address": look for a TV at that IP (also the Samsung default port). */
    fun connectTo(host: String, done: (Boolean) -> Unit) {
        thread(isDaemon = true, name = "opuscast-connect") {
            val info = Lan.info(app)
            lan = info
            val found = try {
                Lan.withMulticast(app) { findTv(host = host.trim(), bindAddress = info.address) }
            } catch (e: Exception) {
                null
            }
            main.post {
                if (found != null) selectTv(found)
                done(found != null)
            }
        }
    }

    private fun subscribeEvents(t: Tv) {
        eventsPending = true
        submit({
            val sids = HashMap<String, String>()
            var timeout = 1800
            for (service in listOf("AVTransport", "RenderingControl")) {
                try {
                    val (sid, granted) = t.subscribe(service, server.eventUrl(t.host, service))
                    sids[service] = sid
                    timeout = minOf(timeout, granted)
                } catch (e: TvException) {
                    // this TV doesn't send events: polling still works
                }
            }
            sids to timeout
        }) { (sids, timeout) -> eventsReady(t, sids, timeout) }
    }

    private fun eventsReady(t: Tv, sids: Map<String, String>, timeout: Int) {
        eventsPending = false
        if (t === tv) {
            eventSids = sids
            if (sids.isNotEmpty()) scheduleRenew(timeout)
        } else {
            submit({ sids.forEach { (s, sid) -> t.unsubscribe(s, sid) } })
        }
    }

    /** Renew halfway through what the TV granted (not on a fixed timer: some TVs grant only minutes). */
    private fun scheduleRenew(timeoutSeconds: Int) {
        renewTask?.let { main.removeCallbacks(it) }
        val task = Runnable { renewEvents() }
        renewTask = task
        main.postDelayed(task, maxOf(15, minOf(timeoutSeconds, 1800) / 2) * 1000L)
    }

    private fun unsubscribeEvents(t: Tv) {
        val sids = eventSids
        eventSids = emptyMap()
        renewTask?.let { main.removeCallbacks(it) }
        submit({ sids.forEach { (s, sid) -> t.unsubscribe(s, sid) } })
    }

    private fun renewEvents() {
        val t = tv ?: return
        val sids = eventSids
        if (sids.isEmpty()) return
        submit({
            var timeout = 1800
            for ((service, sid) in sids) {
                try {
                    timeout = minOf(timeout, t.renew(service, sid).second)
                } catch (e: TvException) {
                    return@submit null
                }
            }
            timeout
        }) { timeout -> if (t === tv) { if (timeout != null) scheduleRenew(timeout) else subscribeEvents(t) } }
    }

    private fun onEvent(service: String, sid: String?, body: String) {
        if (sid == null || sid !in eventSids.values) return // left over from an earlier run
        if (service == "AVTransport") {
            poller.wake() // react to the TV remote right away
            return
        }
        val changes = parseLastChange(body) ?: return
        val t = now()
        changes["Volume"]?.toIntOrNull()?.let { if (t - volTouched > 3) { volume = it; publish() } }
        val s = session
        if ("X_EnabledCaptions" in changes) {
            lastCaptionEvent = t
            if (s != null && s.kind == Kind.MOVIE) {
                val media = s.media
                val shown = captionUris(changes["X_EnabledCaptions"]).mapNotNull { runCatching { URI(it).rawPath }.getOrNull() }.toSet()
                s.subOn = shown.any { it in media.subs.keys && it != media.blankPath }
                val byPath = s.paths.entries.associate { (id, p) -> p to id }
                val shownId = shown.firstNotNullOfOrNull { byPath[it] }
                if (shownId != null) s.subs?.firstOrNull { it.id == shownId }?.let { s.subEntry = it }
                s.subTvChanged = false
                s.captionApi = true
                publish()
            }
        } else if (changes.isEmpty()) {
            // the TV says "something changed" but not what: that's what its own subtitle menu sends
            main.postDelayed({ maybeChangedOnTv(t) }, 1500)
        }
    }

    private fun maybeChangedOnTv(stamp: Double) {
        val s = session ?: return
        val lastCmd = maxOf(lastCaptionCmd, if (s.media.captionSentNanos > 0) s.media.captionSentNanos / 1e9 else 0.0)
        if (s.kind == Kind.MOVIE && s.seenPlaying && lastCaptionEvent < stamp - 0.5 && now() - lastCmd > 4) {
            s.subTvChanged = true
            publish()
        }
    }

    fun scan(force: Boolean = false) {
        if (scanning || (!force && devices.isNotEmpty() && now() - scannedAt < 60)) return
        scanning = true
        publish()
        thread(isDaemon = true, name = "opuscast-scan") {
            val found = try {
                val info = Lan.info(app)
                lan = info
                val others = info.ip?.let { probeHosts(it, info.prefix) } ?: emptySet()
                Lan.withMulticast(app) {
                    scanNetwork(info.ip, info.gateway, others, bindAddress = info.address, thisDeviceName = "${Build.MANUFACTURER} ${Build.MODEL}")
                }
            } catch (e: Exception) {
                emptyList()
            }
            main.post {
                scanning = false
                devices = found
                scannedAt = now()
                publish()
            }
        }
    }

    // ------------------------------------------------------------ recent list

    private fun remember(ref: String, name: String? = null, pos: Double? = null, dur: Double? = null,
                         watched: Boolean? = null, sub: String? = RecentList.KEEP) {
        val dropped = settings.recent.remember(ref, name, pos, dur, watched, sub)
        dropped.forEach { releaseUri(it.ref) }
        save()
    }

    fun forget(ref: String) {
        settings.recent.forget(ref)
        releaseUri(ref)
        save()
        publish()
    }

    private fun releaseUri(ref: String) {
        if (Docs.isFile(ref)) return
        runCatching {
            app.contentResolver.releasePersistableUriPermission(Uri.parse(ref), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun save() {
        runCatching { store.save(settings) }
    }

    // ------------------------------------------------------------ playback

    /** A video picked with the file picker, shared, or opened from a file manager. */
    fun open(uri: Uri, persistable: Boolean) = open(listOf(uri), persistable)

    /** Things picked or shared together: the first video plays; else the songs play one after another;
     *  else the photos are shown, one after another. */
    fun open(uris: List<Uri>, persistable: Boolean) {
        if (uris.isEmpty()) return
        thread(isDaemon = true, name = "opuscast-open") {
            val items = ArrayList<Video>()
            var error: Exception? = null
            for (uri in uris) {
                try {
                    items += Docs.video(app, uri)
                } catch (e: Exception) {
                    error = error ?: e
                }
            }
            main.post {
                val videos = items.filter { it.kind == MediaKind.VIDEO }
                val songs = items.filter { it.kind == MediaKind.AUDIO }
                val photos = items.filter { it.kind == MediaKind.IMAGE }
                when {
                    videos.isNotEmpty() -> {
                        // only videos are remembered (for Recent): permission to read the rest lasts while the app runs
                        if (persistable) runCatching {
                            app.contentResolver.takePersistableUriPermission(videos[0].uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        play(videos[0], null)
                    }
                    songs.isNotEmpty() -> playMusic(songs, 0)
                    photos.isNotEmpty() -> showPhotos(photos, 0)
                    error != null -> showInfo(str(R.string.cant_read_file, error.message ?: error.javaClass.simpleName), Banner.Kind.ERROR)
                    else -> showInfo(str(R.string.not_media), Banner.Kind.WARNING)
                }
            }
        }
    }

    /** A video from the Recent list. */
    fun playRecent(item: RecentItem, fromStart: Boolean = false) {
        thread(isDaemon = true, name = "opuscast-open") {
            val video = try {
                Docs.video(app, Uri.parse(item.ref))
            } catch (e: Exception) {
                main.post { showInfo(str(R.string.cant_open_anymore), Banner.Kind.WARNING) }
                return@thread
            }
            main.post { play(video.copy(name = video.name.ifEmpty { item.name }), if (fromStart) 0.0 else null) }
        }
    }

    fun play(video: Video, startAtArg: Double?) {
        if (waitForTv { play(video, startAtArg) }) return
        if (musicOnly) {
            musicOnlyInfo()
            return
        }
        leavePlaylist()
        val item = settings.recent[video.ref]
        val startAt = startAtArg ?: settings.recent.resumeAt(video.ref)
        val subId = item?.sub
        if (session != null) endSession(stopTv = false)
        banner = null
        remember(video.ref, video.name, pos = startAt)
        gen += 1
        val g = gen
        if (subId == null) {
            begin(g, video, startAt, null, null)
            return
        }
        showPlayer(video.name.substringBeforeLast('.'), str(R.string.preparing_subtitles))
        thread(isDaemon = true, name = "opuscast-subs") { // the subtitle used last time, so it's on from the start
            val found = runCatching { Subs.find(app, video) }.getOrNull()
            val entries = found?.entries?.toMutableList() ?: mutableListOf()
            var entry = entries.firstOrNull { it.id == subId && it.ok }
            if (entry == null && subId.startsWith("file:") && Docs.canOpen(app, subId.substring(5))) {
                entry = Subs.fileEntry(app, subId.substring(5))
                entries += entry
            }
            var data: ByteArray? = null
            if (entry != null) {
                data = try {
                    Subs.prepare(app, entry, video)
                } catch (e: Exception) {
                    entry = null
                    null
                }
            }
            val withAdded = found?.let { FoundSubs(entries, it.access, it.folderUri) } ?: FoundSubs(entries, FolderAccess.NONE, null)
            main.post { begin(g, video, startAt, if (entry != null && data != null) entry to data else null, withAdded) }
        }
    }

    private fun begin(g: Int, video: Video, startAt: Double, subtitle: Pair<SubtitleEntry, ByteArray>?, found: FoundSubs?) {
        if (g != gen) return
        if (settings.intro) {
            startIntro(video, startAt, subtitle, found)
            return
        }
        startSession(Kind.MOVIE, video, startAt, subtitle = subtitle, subsOn = subtitle != null, found = found)
    }

    private fun startIntro(video: Video, startAt: Double, subtitle: Pair<SubtitleEntry, ByteArray>?, found: FoundSubs?) {
        gen += 1
        val g = gen
        showPlayer(str(R.string.skynet_booting), str(R.string.preparing_intro))
        renderIntro { result ->
            if (g != gen) return@renderIntro
            result.fold(
                onSuccess = { clip ->
                    val v = Video(Uri.fromFile(clip).toString(), clip.name, clip.length())
                    startSession(Kind.INTRO, v, 0.0, next = NextUp(video, startAt, subtitle, found), source = FileSource(clip, 9000))
                },
                onFailure = { e ->
                    showInfo(str(R.string.intro_failed, errorText(e)), Banner.Kind.WARNING)
                    startSession(Kind.MOVIE, video, startAt, subtitle = subtitle, subsOn = subtitle != null, found = found)
                },
            )
        }
    }

    private val introWaiters = ArrayList<(Result<File>) -> Unit>()

    /** The rendered intro (made once, then cached), with progress shown in the options bar. */
    private fun renderIntro(done: ((Result<File>) -> Unit)?) {
        Intro.cached(app)?.let { file ->
            done?.invoke(Result.success(file))
            return
        }
        if (done != null) introWaiters += done
        if (introRendering) return
        introRendering = true
        introProgress = 0f
        publish()
        thread(isDaemon = true, name = "opuscast-intro") {
            var lastShown = -1
            val result = runCatching {
                Intro.ensure(app) { p ->
                    val pct = (p * 100).toInt()
                    if (pct != lastShown) {
                        lastShown = pct
                        main.post { introProgress = p; publish() }
                    }
                }
            }
            main.post {
                introRendering = false
                introProgress = null
                val waiters = introWaiters.toList()
                introWaiters.clear()
                publish()
                waiters.forEach { it(result) }
                result.exceptionOrNull()?.let { if (waiters.isEmpty()) showInfo(str(R.string.intro_cant, errorText(it)), Banner.Kind.WARNING) }
            }
        }
    }

    // ------------------------------------------------------------ music

    private fun leavePlaylist() {
        playlist = null
        slideshow = false
        cancelSlide()
        photoCache.clear()
        photoWork.clear()
        playerArt = null
    }

    /** Songs one after another, from [index]. */
    fun playMusic(items: List<Video>, index: Int) {
        if (waitForTv { playMusic(items, index) }) return
        if (session != null) endSession(stopTv = false)
        leavePlaylist()
        banner = null
        playlist = Playlist(Kind.MUSIC, items, index.coerceIn(0, items.size - 1))
        startTrack()
    }

    /** The playlist's current song: read its tags, then hand it to the TV. */
    private fun startTrack() {
        val pl = playlist ?: return
        val t = tv ?: return
        val item = pl.current
        session = null // the one before (if any) is done; the TV goes straight on to this one
        gen += 1
        val g = gen
        playerMode = PlayerMode.MUSIC
        playerArt = null
        showPlayer(stemOf(item.name), str(R.string.starting_on_tv))
        thread(isDaemon = true, name = "opuscast-track") {
            val info = Music.read(app, item)
            val formats = t.sink()
            main.post { if (g == gen && playlist === pl) trackReady(item, info, formats) }
        }
    }

    private fun trackReady(item: Video, info: TrackInfo, formats: SinkFormats) {
        val mime = formats.spell(mimeFor(item.name, item.mime))
        val media = server.publish(DocSource(app, item), mime = mime, features = Dlna.audio(mime))
        media.tagTitle = info.title
        media.artist = info.artist
        media.album = info.album
        media.knownDurationMs = info.durationMs
        info.cover?.let { media.coverPath = server.addExtra(media, ".jpg", "image/jpeg", it) }
        startSession(Kind.MUSIC, item, 0.0, media = media) { s ->
            s.artist = info.artist
            s.album = info.album
            s.art = info.art
            s.trackDurS = (info.durationMs ?: 0L) / 1000.0
        }
    }

    /** The next song (on its own at the end of a song, or the Next button). */
    fun nextTrack() {
        val pl = playlist ?: return
        if (pl.kind != Kind.MUSIC || pl.index + 1 >= pl.items.size) return
        pl.index += 1
        startTrack()
    }

    /** Back to the start of this song, or (in its first seconds) to the one before. */
    fun previousTrack() {
        val pl = playlist ?: return
        if (pl.kind != Kind.MUSIC) return
        if (pl.index == 0 || (session != null && estPos() > 5)) {
            seekTo(0.0)
            return
        }
        pl.index -= 1
        startTrack()
    }

    /** It stopped at its end (not the TV's Stop button): within a few seconds of the length, or after the TV
     *  said it was in the last seconds. */
    private fun endedNaturally(s: Session): Boolean {
        val dur = if (tvDur > 0) tvDur else s.trackDurS
        return dur > 0 && (estPos() >= dur - 5 || poller.stateOnlyUntil > 0)
    }

    // ------------------------------------------------------------ photos

    /** Photos one after another, from [index]: on the TV, with Next / Previous and a slideshow. */
    fun showPhotos(items: List<Video>, index: Int) {
        if (waitForTv { showPhotos(items, index) }) return
        if (musicOnly) {
            musicOnlyInfo()
            return
        }
        if (session != null) endSession(stopTv = false)
        leavePlaylist()
        banner = null
        playlist = Playlist(Kind.PHOTO, items, index.coerceIn(0, items.size - 1))
        showPhoto(first = true)
    }

    private fun showPhoto(first: Boolean = false) {
        val pl = playlist ?: return
        val t = tv ?: return
        val item = pl.current
        cancelSlide()
        val wasShowing = session?.kind == Kind.PHOTO
        session = null
        gen += 1
        val g = gen
        playerMode = PlayerMode.PHOTO
        showPlayer(stemOf(item.name), str(R.string.preparing_photo))
        val ready = photoCache[item.ref]
        if (ready != null) {
            photoReady(g, item, ready, first || !wasShowing)
            return
        }
        thread(isDaemon = true, name = "opuscast-photo") {
            val r = runCatching { Photos.prepare(app, item, t.sink()) }
            main.post {
                if (g != gen || playlist !== pl) return@post
                r.fold(onSuccess = { photoReady(g, item, it, first || !wasShowing) }, onFailure = { photoFailed(item, it) })
            }
        }
    }

    private fun photoReady(g: Int, item: Video, p: PreparedPhoto, stopFirst: Boolean) {
        val pl = playlist ?: return
        if (g != gen) return
        photoCache[item.ref] = p
        val media = server.publish(p.source, mime = p.mime, features = p.features)
        pl.paths += media.urlPath
        playerArt = p.preview
        startSession(Kind.PHOTO, item, 0.0, media = media) { s ->
            s.art = p.preview
            s.stopFirst = stopFirst
        }
        if (slideshow) scheduleSlide()
        prefetch(pl.index + 1)
    }

    private fun photoFailed(item: Video, e: Throwable) {
        playerStatus = str(R.string.photo_failed, errorText(e))
        showInfo(str(R.string.photo_failed_named, item.name, errorText(e)), Banner.Kind.WARNING)
        if (slideshow) scheduleSlide() // a slideshow carries on with the next one
    }

    /** Get the next photo ready while this one is on (converting a large one takes a moment). */
    private fun prefetch(index: Int) {
        val pl = playlist ?: return
        val t = tv ?: return
        if (pl.items.size < 2) return
        val item = pl.items[index.mod(pl.items.size)]
        if (item.ref in photoCache || item.ref in photoWork) return
        photoWork += item.ref
        thread(isDaemon = true, name = "opuscast-photo") {
            val r = runCatching { Photos.prepare(app, item, t.sink()) }.getOrNull()
            main.post {
                photoWork -= item.ref
                if (r != null && playlist === pl) {
                    photoCache[item.ref] = r
                    while (photoCache.size > 4) photoCache.remove(photoCache.keys.first())
                }
            }
        }
    }

    fun nextPhoto() = stepPhoto(1)
    fun previousPhoto() = stepPhoto(-1)

    private fun stepPhoto(delta: Int) {
        val pl = playlist ?: return
        if (pl.kind != Kind.PHOTO || pl.items.size < 2) return
        pl.index = (pl.index + delta).mod(pl.items.size)
        showPhoto()
    }

    fun toggleSlideshow() {
        val pl = playlist ?: return
        if (pl.kind != Kind.PHOTO) return
        slideshow = !slideshow && pl.items.size > 1
        if (slideshow) scheduleSlide() else cancelSlide()
        publish()
    }

    fun setSlideSeconds(seconds: Int) {
        settings.slideSeconds = seconds.coerceIn(2, 600)
        save()
        if (slideshow) scheduleSlide()
        publish()
    }

    /** The next photo after the chosen time (a timer, so it doesn't depend on how often the screen redraws). */
    private fun scheduleSlide() {
        cancelSlide()
        val task = Runnable {
            slideTask = null
            if (slideshow) nextPhoto()
        }
        slideTask = task
        main.postDelayed(task, settings.slideSeconds * 1000L)
    }

    private fun cancelSlide() {
        slideTask?.let { main.removeCallbacks(it) }
        slideTask = null
    }

    // ------------------------------------------------------------ the phone's screen

    /** Before Android asks the user: is there a TV that can show it? */
    fun canCastScreen(): Boolean {
        if (tv == null) {
            showInfo(str(R.string.choose_tv_first), Banner.Kind.WARNING, listOf(BannerAction(str(R.string.choose_tv)) { openDevices?.invoke() }))
            return false
        }
        if (musicOnly) {
            musicOnlyInfo()
            return false
        }
        return true
    }

    fun setScreenHd(on: Boolean) {
        settings.screenHd = on
        save()
        publish()
    }

    /** The user said no to sharing the screen (or Android couldn't start it). */
    fun screenRefused(detail: String? = null) {
        showInfo(if (detail == null) str(R.string.screen_refused) else str(R.string.screen_failed, detail), Banner.Kind.WARNING)
    }

    /** The user allowed it: capture, encode, and hand the stream to the TV. */
    fun screenGranted(projection: MediaProjection) {
        val t = tv
        if (t == null) {
            runCatching { projection.stop() }
            return
        }
        if (session != null) endSession(stopTv = false)
        leavePlaylist()
        banner = null
        val hub = LiveHub()
        val sound = Build.VERSION.SDK_INT >= 29 &&
            app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        var cast: ScreenCast? = null
        val created = ScreenCast(app, projection, hub, if (settings.screenHd) 1080 else 720, sound) { why ->
            if (screen === cast) {
                endSession(stopTv = true)
                if (why != null) showInfo(str(R.string.screen_failed, why), Banner.Kind.WARNING)
            }
        }
        cast = created
        try {
            created.start()
        } catch (e: Exception) {
            thread(isDaemon = true) { created.stop() }
            screenRefused(e.message ?: e.javaClass.simpleName)
            return
        }
        hub.onJoin = { created.requestKeyframe() }
        screen = created
        val media = server.publishLive(hub, str(R.string.screen_title), mime = tvSink.spell("video/mpeg"))
        playerMode = PlayerMode.SCREEN
        playerArt = null
        startSession(Kind.SCREEN, Video("screen", str(R.string.screen_title), -1), 0.0, media = media)
        if (!sound && Build.VERSION.SDK_INT >= 29) showInfo(str(R.string.screen_no_sound))
        else if (Build.VERSION.SDK_INT < 29) showInfo(str(R.string.screen_no_sound_old))
    }

    val screenActive: Boolean get() = screen != null

    private fun stopScreen() {
        val cast = screen ?: return
        screen = null
        thread(isDaemon = true, name = "opuscast-screen-stop") { cast.stop() }
    }

    // ------------------------------------------------------------ sessions

    private fun startSession(kind: Kind, video: Video, startAt: Double, next: NextUp? = null,
                             subtitle: Pair<SubtitleEntry, ByteArray>? = null, subsOn: Boolean = false,
                             found: FoundSubs? = null, source: io.github.m1omg.opuscast.core.MediaSource? = null,
                             media: Media? = null, setup: ((Session) -> Unit)? = null) {
        val t = tv ?: return
        gen += 1
        val entry = subtitle?.first
        val published = media ?: server.publish(source ?: DocSource(app, video), subtitle?.second)
        val start = now()
        val s = Session(kind, video, published, next, start, gen, found, entry, entry != null && subsOn, startAt, subsOn)
        if (entry != null) s.paths[entry.id] = published.subPath!!
        setup?.invoke(s)
        session = s
        poller.active = true
        tvState = "TRANSITIONING"
        tvPos = startAt
        tvPosRaw = null
        posSeen = start
        tvDur = 0.0
        hold = if (startAt > 0) Hold(startAt, start, 30.0) else null
        poller.stateOnlyUntil = 0.0
        playerMode = when (kind) {
            Kind.INTRO -> PlayerMode.INTRO
            Kind.MUSIC -> PlayerMode.MUSIC
            Kind.PHOTO -> PlayerMode.PHOTO
            Kind.SCREEN -> PlayerMode.SCREEN
            Kind.MOVIE -> PlayerMode.VIDEO
        }
        if (kind == Kind.MOVIE || kind == Kind.INTRO) playerArt = null
        showPlayer(if (kind == Kind.INTRO) str(R.string.skynet_booting) else published.title,
            str(if (kind == Kind.PHOTO) R.string.sending_photo else R.string.starting_on_tv))
        if (settings.preview && (kind == Kind.MOVIE || kind == Kind.INTRO)) previewLoad() else preview.unload()
        val g = gen
        if (subsOn) lastCaptionCmd = start
        if (eventSids.isEmpty() && !eventsPending) subscribeEvents(t) // e.g. the TV didn't answer until it was allowed
        startOnTv(s)
        main.postDelayed({ checkFetched(g) }, 15_000)
        if (kind == Kind.MOVIE && found == null) {
            thread(isDaemon = true, name = "opuscast-subs") {
                val f = runCatching { Subs.find(app, video) }.getOrDefault(FoundSubs(emptyList(), FolderAccess.NONE, null))
                main.post { subsFound(g, f) }
            }
        }
        if (kind == Kind.MOVIE && startAt > 5) {
            showInfo(str(R.string.carrying_on, fmtTime(startAt)), Banner.Kind.INFO,
                listOf(BannerAction(str(R.string.start_from_beginning)) { seekTo(0.0) }))
        }
        PlaybackService.start(app)
        updateStatus()
    }

    /** Hand the session's video to the TV (stop, set, play, resume point, subtitle). */
    private fun startOnTv(s: Session) {
        val t = tv ?: return
        val g = s.gen
        worker.execute {
            val r = runCatching { playMedia(t, server, s.media, s.startAt, subsOn = s.subsOn, stopFirst = s.stopFirst) }
            main.post { playStarted(g, r) }
        }
    }

    private fun playStarted(g: Int, result: Result<String?>) {
        poller.wake()
        val s = session
        if (s == null || s.gen != g) return
        val error = result.exceptionOrNull()
        if (error != null) {
            startFailed(s, error as? Exception ?: RuntimeException(error))
            return
        }
        s.startError = null // (a retry worked)
        val captions = result.getOrNull()
        if (s.subEntry == null) return
        s.subLoading = false
        lastCaptionCmd = now()
        when (captions) {
            "ok" -> { s.subOn = true; s.captionApi = true }
            "unsupported" -> { s.subOn = true; s.captionApi = false } // sent with the video; the TV decides
        }
        updateStatus()
    }

    /** The start commands failed. Keep the session: the TV may be waiting for "Allow" and play it then. */
    private fun startFailed(s: Session, e: Exception) {
        s.subLoading = false
        if (s.seenPlaying) { // it plays anyway
            showInfo(str(R.string.tv_didnt_accept, errorText(e)), Banner.Kind.WARNING)
            updateStatus()
            return
        }
        s.startError = e
        s.startFailedAt = now()
        s.answeringSince = 0.0
        showInfo(str(R.string.tv_waiting_allow, errorText(e)), Banner.Kind.WARNING, listOf(
            BannerAction(str(R.string.try_again)) { retryStart() },
            BannerAction(str(R.string.stop)) { stop() },
        ))
        s.waitBanner = bannerId
        val g = s.gen
        val stamp = s.startFailedAt
        main.postDelayed({ giveUp(g, stamp) }, 120_000)
        updateStatus()
    }

    /** Send the start commands again, in the same session (same address for the TV). */
    private fun retryStart() {
        val s = session ?: return
        if (s.seenPlaying) return
        s.retried = true
        if (banner?.id == s.waitBanner) banner = null
        s.subLoading = s.subEntry != null && s.subsOn
        tv?.let { if (eventSids.isEmpty() && !eventsPending) subscribeEvents(it) }
        startOnTv(s)
        updateStatus()
    }

    private fun giveUp(g: Int, stamp: Double) {
        val s = session ?: return
        val e = s.startError ?: return
        if (s.gen != g || s.seenPlaying || s.startFailedAt != stamp) return
        endSession(stopTv = false)
        showInfo(str(R.string.couldnt_start, errorText(e)), Banner.Kind.ERROR)
    }

    /** The TV plays it after all (allowed on the TV): carry on as if the start had worked. */
    private fun recovered(s: Session) {
        s.startError = null
        if (banner?.id == s.waitBanner) banner = null
        val t = tv ?: return
        if (s.startAt > 1) { // the resume point was never sent
            hold = Hold(s.startAt, now(), 30.0)
            submit({ t.seek(s.startAt) }) { poller.wake() }
        }
        val entry = s.subEntry
        if (s.kind == Kind.MOVIE && entry != null && s.subsOn && !s.subOn) {
            lastCaptionCmd = now()
            submit({ showSubtitle(t, server, s.media, s.media.subPath) }) { r ->
                if (s === session) {
                    s.captionApi = r == "ok"
                    s.subOn = true // announced with the video either way
                    updateStatus()
                }
            }
        }
    }

    private fun subsFound(g: Int, found: FoundSubs) {
        val s = session ?: return
        if (s.gen != g) return
        s.found = found
        val have = s.subs
        if (have == null) {
            s.subs = found.entries.toMutableList()
        } else { // keep files the user picked meanwhile
            s.subs = (found.entries + have.filter { e -> found.entries.none { it.id == e.id } }).toMutableList()
        }
        publish()
    }

    /** After the user allowed a folder or all files: look for subtitle files again. */
    fun refreshSubtitles() {
        val s = session ?: return
        if (s.kind != Kind.MOVIE) return
        val g = s.gen
        thread(isDaemon = true, name = "opuscast-subs") {
            val f = runCatching { Subs.find(app, s.video) }.getOrDefault(FoundSubs(emptyList(), FolderAccess.NONE, null))
            main.post { subsFound(g, f) }
        }
    }

    /** The folder picker returned [tree] for the playing video's folder. */
    fun folderGranted(tree: Uri) {
        val s = session ?: return
        runCatching { app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        thread(isDaemon = true) {
            val ok = Subs.treeCovers(app, tree, s.video)
            main.post {
                if (!ok) showInfo(str(R.string.wrong_folder), Banner.Kind.WARNING)
                refreshSubtitles()
            }
        }
    }

    /** Where the folder picker should open for the playing video. */
    fun folderUri(): Uri? = session?.found?.folderUri

    private fun showPlayer(title: String, status: String) {
        playerTitle = title
        playerStatus = status
        page = Page.PLAYER
        _clock.value = Clock(0.0, 0.0)
        publish()
    }

    private fun checkFetched(g: Int) {
        val s = session ?: return
        if (s.gen == g && !s.media.requested && s.startError == null) {
            showInfo(str(R.string.not_loading), Banner.Kind.WARNING, listOf(BannerAction(str(R.string.try_again)) {
                val cur = session ?: return@BannerAction
                when (cur.kind) {
                    Kind.MOVIE -> play(cur.video, estPos())
                    Kind.INTRO -> skipIntro()
                    Kind.MUSIC -> startTrack()
                    Kind.PHOTO -> showPhoto(first = true)
                    Kind.SCREEN -> startOnTv(cur)
                }
            }))
        }
    }

    private fun estPos(): Double {
        hold?.let { return it.target }
        var p = tvPos
        if (tvState == "PLAYING") p += minOf(now() - posSeen, 1.0)
        return if (tvDur > 0) minOf(p, tvDur) else p
    }

    private fun onPoll(r: PollResult) {
        val t = tv ?: return
        if (r.tv !== t) return
        val err = r.error
        if (err != null) {
            pollErrors += 1
            session?.let { it.answeringSince = 0.0 }
            if (pollErrors == 3) setStatusLine(str(R.string.cant_reach, t.name))
            return
        }
        if (pollErrors >= 3) statusLine = str(R.string.connected_to, t.name)
        pollErrors = 0
        if (r.volume != null && now() - volTouched > 3) volume = r.volume
        val s = session
        if (s == null) {
            publish()
            return
        }
        val state = r.state
        val pos = r.pos
        val ours = r.uri.isNullOrEmpty() || s.media.urlPath in r.uri!!
        val n = now()
        val wasSeen = s.seenPlaying
        if (s.kind == Kind.PHOTO) {
            followPhoto(s, r, wasSeen)
            return
        }
        if (ours) {
            if (state == "PLAYING") s.seenPlaying = true
            if (s.seenPlaying && state in setOf("PLAYING", "PAUSED_PLAYBACK", "TRANSITIONING")) {
                if (pos != null) {
                    if (pos != tvPosRaw) {
                        tvPosRaw = pos
                        posSeen = n
                    }
                    tvPos = pos.toDouble()
                }
                r.dur?.let { if (it > 0) tvDur = it.toDouble() }
                tvState = state
            }
        }
        hold?.let { h ->
            val arrived = ours && pos != null && s.seenPlaying && pos >= h.target - 2 && pos <= h.target + 8
            if (arrived || n - h.since > h.limit) hold = null
        }
        if (s.seenPlaying && (state == "STOPPED" || state == "NO_MEDIA_PRESENT" || !ours)) {
            val pl = playlist
            if (s.kind == Kind.MUSIC && ours && pl != null && endedNaturally(s)) {
                if (pl.index + 1 < pl.items.size) nextTrack() else endSession(stopTv = false)
                return
            }
            sessionFinished()
            return
        }
        if (s.kind == Kind.MUSIC && ours && state == "PLAYING" && pos != null) {
            val dur = r.dur?.takeIf { it > 0 }?.toDouble() ?: s.trackDurS
            if (dur > 0 && pos >= dur - 5 && poller.stateOnlyUntil == 0.0) poller.stateOnlyUntil = n + 6 // the TV says: nearly done
        }
        if (!wasSeen && s.seenPlaying && s.startError != null) recovered(s)
        if (!s.seenPlaying && s.startError != null) {
            // the TV answers again (allowed?): if it doesn't start the video by itself, send it once more
            if (s.answeringSince == 0.0) s.answeringSince = n
            if (!s.retried && n - s.answeringSince >= 4) retryStart()
        }
        if (!s.seenPlaying && s.startError == null && n - s.started > 40 && (state == "STOPPED" || state == "NO_MEDIA_PRESENT")) {
            val kind = s.kind
            endSession(stopTv = false)
            showInfo(str(when (kind) {
                Kind.SCREEN -> R.string.tv_didnt_start_screen
                Kind.MUSIC -> R.string.tv_didnt_start_music
                else -> R.string.tv_didnt_start
            }), Banner.Kind.ERROR)
            return
        }
        updateStatus()
        syncPreview()
    }

    /** A photo: some TVs report STOPPED (or nothing) while they show it, so only Stop, or the TV showing
     *  something that isn't ours twice in a row, ends it. It counts as shown once the TV fetched it. */
    private fun followPhoto(s: Session, r: PollResult, wasSeen: Boolean) {
        if (s.media.requested || r.state == "PLAYING") s.seenPlaying = true
        val uri = r.uri
        val known = playlist?.paths ?: emptySet<String>()
        val foreign = !uri.isNullOrEmpty() && s.media.urlPath !in uri && known.none { it in uri }
        if (s.seenPlaying && foreign) {
            s.foreignPolls += 1
            if (s.foreignPolls >= 2) {
                endSession(stopTv = false)
                return
            }
        } else {
            s.foreignPolls = 0
        }
        if (!wasSeen && s.seenPlaying && s.startError != null) recovered(s)
        if (!s.seenPlaying && s.startError != null) {
            if (s.answeringSince == 0.0) s.answeringSince = now()
            if (!s.retried && now() - s.answeringSince >= 4) retryStart()
        }
        updateStatus()
    }

    private fun updateStatus() {
        val s = session ?: return
        val st = if (s.seenPlaying) tvState else "STARTING"
        var text = when (st) {
            "PLAYING" -> str(R.string.playing)
            "PAUSED_PLAYBACK" -> str(R.string.paused)
            "TRANSITIONING" -> str(R.string.loading)
            else -> str(R.string.starting_on_tv)
        }
        if (s.kind == Kind.INTRO && st == "PLAYING") text = str(R.string.skynet_online)
        if (s.kind == Kind.PHOTO) text = str(if (s.seenPlaying) R.string.showing else R.string.sending_photo)
        if (s.kind == Kind.SCREEN && st == "PLAYING") text = str(R.string.live)
        if (!s.seenPlaying && s.startError != null) text = str(R.string.waiting_tv)
        if (s.subLoading) text = str(R.string.loading_subtitles)
        val name = tv?.name
        playerStatus = if (name != null) str(R.string.status_on, text, name) else text
        publish()
    }

    private fun tick() {
        main.postDelayed(::tick, 250)
        val s = session ?: return
        if (!scrubbing) _clock.value = Clock(estPos(), tvDur)
        val n = now()
        if (s.kind == Kind.MOVIE && s.seenPlaying && n - lastSave > 15) {
            lastSave = n
            remember(s.video.ref, pos = estPos(), dur = tvDur)
        }
    }

    private fun sessionFinished() {
        val s = session ?: return
        val next = s.next
        if (s.kind == Kind.INTRO && next != null) {
            session = null
            startSession(Kind.MOVIE, next.video, next.startAt, subtitle = next.subtitle, subsOn = next.subtitle != null, found = next.found)
        } else {
            endSession(stopTv = false)
        }
    }

    private fun endSession(stopTv: Boolean) {
        val s = session ?: return
        session = null
        poller.active = false
        gen += 1
        s.subCancel?.cancelled = true
        if (s.kind == Kind.SCREEN) stopScreen()
        if (s.kind == Kind.MUSIC || s.kind == Kind.PHOTO) leavePlaylist()
        if (s.kind == Kind.MOVIE && s.seenPlaying) {
            val pos = estPos()
            val watched = RecentList.isWatched(pos, tvDur)
            remember(s.video.ref, pos = if (watched) 0.0 else pos, dur = tvDur, watched = watched)
        }
        val t = tv
        if (stopTv && t != null) submit({ t.stop() })
        preview.unload()
        hold = null
        page = Page.PICK
        publish()
    }

    fun togglePlay() {
        val t = tv ?: return
        val s = session
        if (playlist?.kind == Kind.PHOTO) { // a photo doesn't play: Play / Pause is the slideshow's
            toggleSlideshow()
            return
        }
        if (s == null || s.kind == Kind.SCREEN) return
        if (tvState == "PLAYING") {
            tvPos = estPos()
            tvState = "PAUSED_PLAYBACK"
            submit({ t.pause() }) { poller.wake() }
        } else {
            tvState = "PLAYING"
            posSeen = now()
            submit({ t.play() }) { poller.wake() }
        }
        updateStatus()
        syncPreview()
    }

    fun seekTo(targetArg: Double) {
        val t = tv ?: return
        val s = session ?: return
        if (s.kind == Kind.PHOTO || s.kind == Kind.SCREEN) return
        val dur = tvDur
        val target = maxOf(0.0, if (dur > 0) minOf(targetArg, dur - 2) else targetArg)
        hold = Hold(target, now(), 8.0)
        poller.stateOnlyUntil = 0.0
        submit({ t.seek(target) }) { poller.wake() }
        if (preview.ref != null) preview.seek(target)
        _clock.value = Clock(target, tvDur)
        publish()
    }

    fun skip(delta: Double) = seekTo(estPos() + delta)

    /** The seek bar is being dragged: show where on the phone preview (cheap keyframe seeks). */
    fun scrub(value: Double) {
        scrubbing = true
        _clock.value = Clock(value, tvDur)
        val n = now()
        if (preview.ref != null && n - lastScrub > 0.12) {
            lastScrub = n
            preview.setPlaying(false)
            preview.seek(value, accurate = false)
        }
    }

    fun scrubEnd(value: Double) {
        scrubbing = false
        seekTo(value)
    }

    fun skipIntro() {
        if (session?.kind == Kind.INTRO) sessionFinished()
    }

    fun stop() {
        if (session == null) { // still preparing (subtitles / intro / the next song or photo): just go back
            gen += 1
            if (playlist != null) { // the last song or photo is still on the TV
                leavePlaylist()
                tv?.let { t -> submit({ t.stop() }) }
            }
            page = Page.PICK
            publish()
            return
        }
        endSession(stopTv = true)
    }

    /** Next / Previous (media buttons, lock screen, notification): song, photo, or 30 seconds in a video. */
    fun next() {
        when (playlist?.kind) {
            Kind.MUSIC -> nextTrack()
            Kind.PHOTO -> nextPhoto()
            else -> if (session?.kind == Kind.MOVIE) skip(30.0)
        }
    }

    fun previous() {
        when (playlist?.kind) {
            Kind.MUSIC -> previousTrack()
            Kind.PHOTO -> previousPhoto()
            else -> if (session?.kind == Kind.MOVIE) skip(-30.0)
        }
    }

    /** Called by the notification's service when it goes away with the app (swiped out). */
    fun stopFromNotification() = stop()

    val nowPlaying: String? get() = session?.let { if (it.kind == Kind.INTRO) str(R.string.skynet_booting) else it.media.title }
    val tvNameNow: String? get() = tv?.name
    val isPlaying: Boolean get() = session?.seenPlaying == true && tvState == "PLAYING"
    val hasSession: Boolean get() = session != null || playlist != null

    // ------------------------------------------------------------ subtitles

    private fun preferredLanguages(): List<String> {
        val device = LocaleList.getDefault().let { l -> (0 until l.size()).map { l[it].language } }
        return (device + listOf("sk", "cs", "en")).distinct()
    }

    fun languageName(code: String): String {
        val l = Locale.getDefault()
        val name = Locale.forLanguageTag(code).getDisplayLanguage(l)
        return if (name.isEmpty() || name == code) code else name.replaceFirstChar { it.titlecase(l) }
    }

    private fun shorten(text: String, n: Int = 60) = if (text.length <= n) text else text.take(n - 1) + "…"

    fun label(e: SubtitleEntry): String {
        if (e.kind == SubtitleEntry.Kind.FILE) {
            val name = e.fileName ?: ""
            return if (e.lang.isNotEmpty()) "${languageName(e.lang)} · $name" else name
        }
        val tracks = session?.subs?.filter { it.kind == SubtitleEntry.Kind.TRACK } ?: emptyList()
        val n = tracks.indexOfFirst { it.id == e.id }.let { if (it < 0) e.track ?: 0 else it + 1 }
        val lang = if (e.lang.isNotEmpty()) languageName(e.lang) else ""
        val parts = mutableListOf(lang.ifEmpty { str(R.string.track_n, n) }, str(R.string.built_in))
        if (e.forced) parts += str(R.string.forced)
        if (e.title.isNotEmpty() && e.title.length <= 40 && !e.title.equals(lang, ignoreCase = true)) parts += e.title
        return parts.joinToString(" · ")
    }

    private fun subName(e: SubtitleEntry) = if (e.lang.isNotEmpty()) languageName(e.lang) else shorten(label(e), 24)

    private fun subLabel(): String {
        val s = session ?: return str(R.string.subtitles)
        val entry = s.subEntry
        val subs = s.subs
        return when {
            s.subLoading -> str(R.string.subs_loading)
            s.subTvChanged -> str(R.string.subs_set_on_tv)
            s.subOn && entry != null -> str(R.string.subs_name, subName(entry))
            subs == null -> str(R.string.subtitles)
            subs.none { it.ok } -> str(R.string.subs_none)
            else -> str(R.string.subs_off)
        }
    }

    private fun subMenu(): SubMenu? {
        val s = session ?: return null
        if (s.kind != Kind.MOVIE) return null
        val subs = s.subs
        val notes = ArrayList<String>()
        if (s.subTvChanged) notes += str(R.string.note_tv_changed)
        if (s.captionApi == false) notes += str(R.string.note_restart) else notes += str(R.string.note_tv_setting)
        if (subs == null) return SubMenu(true, emptyList(), emptyList(), false, notes, s.found?.access ?: FolderAccess.NONE)
        val current = if (s.subTvChanged) null else s.subEntry?.takeIf { s.subOn }?.id ?: "off"
        val options = listOf(SubOption(null, str(R.string.off), current == "off")) +
            subs.filter { it.ok }.map { SubOption(it, shorten(label(it)), it.id == current) }
        val unusable = subs.filter { !it.ok }.map {
            shorten(label(it), 40) to str(if (it.why == SubtitleEntry.Why.PICTURE) R.string.picture_based else R.string.unsupported_sub)
        }
        return SubMenu(false, options, unusable, subs.isEmpty(), notes, s.found?.access ?: FolderAccess.NONE)
    }

    fun pickSubtitle(entry: SubtitleEntry?) {
        val s = session ?: return
        if (s.kind != Kind.MOVIE) return
        s.subTvChanged = false
        remember(s.video.ref, sub = entry?.id)
        if (s.captionApi == false) { // this TV only takes subtitles with the video: restart with them
            val cur = s.subEntry
            when {
                entry == null && cur == null -> { s.subOn = false; publish() }
                entry != null && cur != null && cur.id == entry.id -> { s.subOn = true; publish() }
                else -> restartWith(entry)
            }
            return
        }
        if (entry == null || entry.id in s.paths) {
            showSub(s, entry)
            return
        }
        s.subLoading = true // extract / convert it first (cached after the first time)
        updateStatus()
        val cancel = Cancel()
        s.subCancel = cancel
        thread(isDaemon = true, name = "opuscast-subs") {
            val data = try {
                Subs.prepare(app, entry, s.video, cancel) { p -> main.post { subProgress(s, p) } }
            } catch (e: Exception) {
                main.post { subFailed(s.gen, e) }
                return@thread
            }
            main.post { subPrepared(s, entry, data) }
        }
    }

    private fun subProgress(s: Session, p: Float) {
        if (s === session && s.subLoading && p < 1f) {
            playerStatus = str(R.string.reading_subtitles, (p * 100).toInt())
            publish()
        }
    }

    private fun subPrepared(s: Session, entry: SubtitleEntry, data: ByteArray) {
        if (s !== session) return
        s.paths[entry.id] = server.addSubtitle(s.media, data)
        showSub(s, entry)
    }

    /** Switch the TV to this subtitle (null: the empty file, i.e. off) without restarting the video. */
    private fun showSub(s: Session, entry: SubtitleEntry?) {
        val t = tv ?: return
        s.subLoading = true
        updateStatus()
        lastCaptionCmd = now()
        val path = entry?.let { s.paths[it.id] }
        submit({ showSubtitle(t, server, s.media, path) }) { r -> subShown(s, entry, r) }
    }

    private fun subShown(s: Session, entry: SubtitleEntry?, result: String) {
        if (s !== session) return
        s.subLoading = false
        if (result != "ok") { // not a Samsung after all: the TV needs the subtitle announced with the video
            s.captionApi = false
            restartWith(entry)
            return
        }
        s.captionApi = true
        s.subOn = entry != null
        if (entry != null) s.subEntry = entry
        updateStatus()
    }

    /** Start the video again from here with a different subtitle (the TV needs it announced up front). */
    private fun restartWith(entry: SubtitleEntry?) {
        val s = session ?: return
        val g = s.gen
        s.subLoading = true
        updateStatus()
        thread(isDaemon = true, name = "opuscast-subs") {
            val data = try {
                entry?.let { Subs.prepare(app, it, s.video) }
            } catch (e: Exception) {
                main.post { subFailed(g, e) }
                return@thread
            }
            main.post {
                val cur = session
                if (cur != null && cur.gen == g) {
                    val keep = cur.subs?.let { FoundSubs(it, cur.found?.access ?: FolderAccess.NONE, cur.found?.folderUri) }
                    startSession(Kind.MOVIE, cur.video, estPos(), subtitle = if (entry != null && data != null) entry to data else null,
                        subsOn = entry != null, found = keep)
                }
            }
        }
    }

    private fun subFailed(g: Int, e: Exception) {
        val s = session ?: return
        if (s.gen != g) return
        s.subLoading = false
        updateStatus()
        if (e !is io.github.m1omg.opuscast.core.CancelledException) {
            showInfo(str(R.string.couldnt_use_subs, errorText(e)), Banner.Kind.WARNING)
        }
    }

    /** A subtitle file picked with the system file picker. */
    fun useSubtitleFile(uri: Uri) {
        val s = session ?: return
        runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        thread(isDaemon = true) {
            val entry = Subs.fileEntry(app, uri.toString())
            main.post {
                if (s !== session) return@post
                val subs = s.subs
                if (subs != null && subs.none { it.id == entry.id }) subs += entry
                else if (subs == null) s.subs = mutableListOf(entry)
                pickSubtitle(entry)
            }
        }
    }

    /** S key: off -> last/first subtitle -> off. */
    fun cycleSubtitles() {
        val s = session ?: return
        val subs = s.subs ?: return
        if (s.kind != Kind.MOVIE || subs.isEmpty()) return
        if (s.subOn && !s.subTvChanged) {
            pickSubtitle(null)
            return
        }
        val entry = s.subEntry ?: autoSubtitle(subs, preferredLanguages())
        if (entry != null) pickSubtitle(entry)
    }

    // ------------------------------------------------------------ volume

    fun setVolume(value: Int) {
        val t = tv ?: return
        volTouched = now()
        volume = value.coerceIn(0, 100)
        publish()
        volumeTask?.let { main.removeCallbacks(it) }
        val task = Runnable {
            volumeTask = null
            val v = volume ?: return@Runnable
            submit({ t.setVolume(v) })
        }
        volumeTask = task
        main.postDelayed(task, 150)
    }

    fun volumeStep(delta: Int) {
        setVolume((volume ?: 10) + delta)
    }

    // ------------------------------------------------------------ preview

    fun setPreview(on: Boolean) {
        settings.preview = on
        save()
        if (on && session != null) previewLoad() else if (!on) preview.unload()
        publish()
    }

    private fun previewLoad() {
        val s = session ?: return
        val g = s.gen
        main.post {
            val cur = session
            if (cur != null && cur.gen == g && settings.preview) {
                preview.load(cur.video.ref, estPos())
                syncPreview()
            }
        }
    }

    private fun syncPreview() {
        val s = session ?: return
        if (preview.ref != s.video.ref || scrubbing) return
        val want = tvState == "PLAYING" && s.seenPlaying && hold == null
        preview.setPlaying(want)
        val here = preview.position() ?: return
        val target = estPos()
        if (Math.abs(here - target) > (if (want) 1.5 else 0.3)) preview.seek(target)
    }

    private fun onPreviewError(message: String) {
        settings.preview = false
        save()
        showInfo(str(R.string.preview_failed, message), Banner.Kind.WARNING)
    }

    // ------------------------------------------------------------ misc

    fun setIntro(on: Boolean) {
        settings.intro = on
        save()
        publish()
        if (on) renderIntro(null) // render it now so the first play starts right away
    }

    private fun onWorkerError(e: Exception) {
        val s = session
        if (s != null && s.subLoading) s.subLoading = false
        if (s != null && !s.seenPlaying) {
            endSession(stopTv = false)
            showInfo(str(R.string.couldnt_start, errorText(e)), Banner.Kind.ERROR)
        } else {
            showInfo(str(R.string.tv_didnt_accept, errorText(e)), Banner.Kind.WARNING)
        }
        updateStatus()
    }
}
