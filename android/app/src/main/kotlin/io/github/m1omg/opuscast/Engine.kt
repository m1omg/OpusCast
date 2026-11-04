package io.github.m1omg.opuscast

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.LocaleList
import io.github.m1omg.opuscast.core.Cancel
import io.github.m1omg.opuscast.core.Media
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
)

/** Position on the TV, ticking 4 times a second (estimated between the TV's answers). */
data class Clock(val pos: Double, val dur: Double)

private enum class Kind { MOVIE, INTRO }

private class Hold(val target: Double, val since: Double, val limit: Double)

private class NextUp(val video: Video, val startAt: Double, val subtitle: Pair<SubtitleEntry, ByteArray>?, val found: FoundSubs?)

private class Session(
    val kind: Kind, val video: Video, val media: Media, val next: NextUp?, val started: Double, val gen: Int,
    var found: FoundSubs?, var subEntry: SubtitleEntry?, var subLoading: Boolean,
) {
    var seenPlaying = false
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
    private var pendingPlay: Pair<Video, Double?>? = null
    private var eventSids = mapOf<String, String>()
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
        val player = if (page == Page.PLAYER) PlayerUi(
            title = playerTitle,
            status = playerStatus,
            playing = s != null && s.seenPlaying && tvState == "PLAYING",
            isIntro = s?.kind == Kind.INTRO,
            showSubtitles = s?.kind == Kind.MOVIE,
            subLabel = subLabel(),
            subMenu = subMenu(),
            seekable = s != null && tvDur > 0,
            tvName = tv?.name,
        ) else null
        _ui.value = UiState(
            tvName = tv?.name, tvHost = tv?.host, searchingTv = searchingTv, statusLine = statusLine, devices = devices, scanning = scanning,
            recent = settings.recent.items, banner = banner, page = page, player = player, volume = volume,
            previewOn = settings.preview, introOn = settings.intro, introProgress = introProgress,
            sessionActive = s != null,
        )
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
        subscribeEvents(newTv)
        publish()
        pendingPlay?.let { (video, startAt) ->
            pendingPlay = null
            play(video, startAt)
        }
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
    fun open(uri: Uri, persistable: Boolean) {
        if (persistable) {
            runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        thread(isDaemon = true, name = "opuscast-open") {
            val video = try {
                Docs.video(app, uri)
            } catch (e: Exception) {
                main.post { showInfo(str(R.string.cant_read_file, e.message ?: e.javaClass.simpleName), Banner.Kind.ERROR) }
                return@thread
            }
            main.post { play(video, null) }
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
        val t = tv
        if (t == null) {
            pendingPlay = video to startAtArg
            showInfo(str(R.string.waiting_for_tv))
            return
        }
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

    private fun startSession(kind: Kind, video: Video, startAt: Double, next: NextUp? = null,
                             subtitle: Pair<SubtitleEntry, ByteArray>? = null, subsOn: Boolean = false,
                             found: FoundSubs? = null, source: io.github.m1omg.opuscast.core.MediaSource? = null) {
        val t = tv ?: return
        gen += 1
        val entry = subtitle?.first
        val media = server.publish(source ?: DocSource(app, video), subtitle?.second)
        val start = now()
        val s = Session(kind, video, media, next, start, gen, found, entry, entry != null && subsOn)
        if (entry != null) s.paths[entry.id] = media.subPath!!
        session = s
        poller.active = true
        tvState = "TRANSITIONING"
        tvPos = startAt
        tvPosRaw = null
        posSeen = start
        tvDur = 0.0
        hold = if (startAt > 0) Hold(startAt, start, 30.0) else null
        showPlayer(if (kind == Kind.INTRO) str(R.string.skynet_booting) else media.title, str(R.string.starting_on_tv))
        if (settings.preview) previewLoad()
        val g = gen
        if (subsOn) lastCaptionCmd = start
        submit({ playMedia(t, server, media, startAt, subsOn = subsOn) }) { result -> playStarted(g, result) }
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

    private fun playStarted(g: Int, captions: String?) {
        poller.wake()
        val s = session
        if (s == null || s.gen != g || s.subEntry == null) return
        s.subLoading = false
        lastCaptionCmd = now()
        when (captions) {
            "ok" -> { s.subOn = true; s.captionApi = true }
            "unsupported" -> { s.subOn = true; s.captionApi = false } // sent with the video; the TV decides
        }
        updateStatus()
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
        if (s.gen == g && !s.media.requested) {
            showInfo(str(R.string.not_loading), Banner.Kind.WARNING, listOf(BannerAction(str(R.string.try_again)) {
                val cur = session ?: return@BannerAction
                if (cur.kind == Kind.MOVIE) play(cur.video, estPos()) else skipIntro()
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
            sessionFinished()
            return
        }
        if (!s.seenPlaying && n - s.started > 40 && (state == "STOPPED" || state == "NO_MEDIA_PRESENT")) {
            endSession(stopTv = false)
            showInfo(str(R.string.tv_didnt_start), Banner.Kind.ERROR)
            return
        }
        updateStatus()
        syncPreview()
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
        if (session == null) return
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
        if (session == null) return
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
        if (session == null) { // still preparing (subtitles / intro): just go back
            gen += 1
            page = Page.PICK
            publish()
            return
        }
        endSession(stopTv = true)
    }

    /** Called by the notification's service when it goes away with the app (swiped out). */
    fun stopFromNotification() = stop()

    val nowPlaying: String? get() = session?.let { if (it.kind == Kind.INTRO) str(R.string.skynet_booting) else it.media.title }
    val tvNameNow: String? get() = tv?.name
    val isPlaying: Boolean get() = session?.seenPlaying == true && tvState == "PLAYING"
    val hasSession: Boolean get() = session != null

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
