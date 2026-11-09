package io.github.m1omg.opuscast

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.projection.MediaProjectionManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Runs while a video plays on the TV: the TV reads the file from this phone, so the phone must keep
 * serving it with the screen off. Shows the playback notification, offers the lock-screen / Bluetooth
 * media controls, and lets the phone's volume buttons set the TV's volume.
 */
class PlaybackService : Service() {
    companion object {
        private const val CHANNEL = "playback"
        private const val NOTIFICATION = 1
        private const val ACTION_TOGGLE = "io.github.m1omg.opuscast.TOGGLE"
        private const val ACTION_BACK = "io.github.m1omg.opuscast.BACK"
        private const val ACTION_FORWARD = "io.github.m1omg.opuscast.FORWARD"
        private const val ACTION_STOP = "io.github.m1omg.opuscast.STOP"
        private const val ACTION_NEXT = "io.github.m1omg.opuscast.NEXT"
        private const val ACTION_PREVIOUS = "io.github.m1omg.opuscast.PREVIOUS"
        private const val ACTION_SCREEN = "io.github.m1omg.opuscast.SCREEN"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        /** Long enough for any film; renewed whenever a video starts, released when playback ends. */
        private const val WAKE_MS = 6 * 60 * 60 * 1000L

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
            } catch (e: Exception) {
                // not allowed from the background (Android 12+); playback still works while the app is open
            }
        }

        /** The user allowed screen capture: Android wants the projection taken in a foreground service that
         *  already runs as the "media projection" kind (Android 14), so the service takes it. */
        fun startScreen(context: Context, resultCode: Int, data: Intent) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java)
                    .setAction(ACTION_SCREEN).putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data))
            } catch (e: Exception) {
                context.engine.screenRefused(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private val scope = MainScope()
    private lateinit var media: MediaSession
    private var volume: VolumeProvider? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val wifiLocks = ArrayList<WifiManager.WifiLock>()
    private var shownTitle: String? = null
    private var shownPlaying: Boolean? = null
    private var lastState: Triple<Int, Long, Long>? = null // (state, position ms, elapsedRealtime when set)
    private var shownMode: PlayerMode? = null
    private var shownAgain: Boolean? = null
    private var shownArt: android.graphics.Bitmap? = null
    private var foreground = false
    /** The foreground service types it runs as now (screen capture adds media projection). */
    private var types = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.channel_description)
            setShowBadge(false)
        })
        media = MediaSession(this, "OpusCast").apply {
            setCallback(object : MediaSession.Callback() {
                // the TV left the shared screen: Play (the shade, the lock screen, a headset) shows it there again
                override fun onPlay() { if (engine.screenAway) engine.showScreenAgain() else if (!engine.isPlaying) engine.togglePlay() }
                override fun onPause() { if (engine.isPlaying) engine.togglePlay() }
                override fun onStop() = engine.stop()
                override fun onSeekTo(pos: Long) = engine.seekTo(pos / 1000.0)
                override fun onFastForward() = engine.skip(10.0)
                override fun onRewind() = engine.skip(-10.0)
                override fun onSkipToNext() = engine.next()
                override fun onSkipToPrevious() = engine.previous()
                override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                    when (action) {
                        ACTION_BACK -> engine.skip(-10.0)
                        ACTION_FORWARD -> engine.skip(10.0)
                    }
                }
            })
            setSessionActivity(openApp())
            isActive = true
        }
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpusCast:serving").apply { setReferenceCounted(false); acquire(WAKE_MS) }
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        val modes = listOfNotNull(WifiManager.WIFI_MODE_FULL_HIGH_PERF, if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else null)
        for (mode in modes) runCatching {
            wifi.createWifiLock(mode, "OpusCast:serving").apply { setReferenceCounted(false); acquire() }
        }.getOrNull()?.let { wifiLocks += it }
        goForeground(engine.ui.value.player?.title ?: getString(R.string.app_name), false)
        scope.launch {
            combine(engine.ui, engine.clock) { ui, clock -> ui to clock }.collect { (ui, clock) -> update(ui, clock) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SCREEN) {
            takeScreen(intent)
            return START_NOT_STICKY
        }
        // every startForegroundService() must be answered with startForeground()
        goForeground(shownTitle ?: getString(R.string.app_name), shownPlaying ?: false)
        runCatching { wakeLock?.acquire(WAKE_MS) } // a new video: the full time again
        when (intent?.action) {
            ACTION_TOGGLE -> engine.togglePlay()
            ACTION_BACK -> engine.skip(-10.0)
            ACTION_FORWARD -> engine.skip(10.0)
            ACTION_NEXT -> engine.next()
            ACTION_PREVIOUS -> engine.previous()
            ACTION_STOP -> engine.stopFromNotification()
        }
        if (!engine.hasSession) stopSelfSoon()
        return START_NOT_STICKY
    }

    /** Become a "media projection" foreground service first, then take the projection (Android 14's order). */
    private fun takeScreen(intent: Intent) {
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
        try {
            goForeground(getString(R.string.screen_title), false, screen = true)
        } catch (e: Exception) {
            goForeground(shownTitle ?: getString(R.string.app_name), false)
            engine.screenRefused(e.message ?: e.javaClass.simpleName)
            if (!engine.hasSession) stopSelfSoon()
            return
        }
        runCatching { wakeLock?.acquire(WAKE_MS) }
        val projection = try {
            data?.let { getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, it) }
        } catch (e: Exception) {
            null
        }
        if (projection == null) {
            engine.screenRefused()
            goForeground(shownTitle ?: getString(R.string.app_name), false)
            if (!engine.hasSession) stopSelfSoon()
            return
        }
        engine.screenGranted(projection)
        if (!engine.screenActive) {
            goForeground(shownTitle ?: getString(R.string.app_name), false)
            if (!engine.hasSession) stopSelfSoon()
        }
    }

    private fun stopSelfSoon() {
        scope.launch {
            if (!engine.hasSession) {
                ServiceCompat.stopForeground(this@PlaybackService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun action(name: String, code: Int) = PendingIntent.getService(this, code,
        Intent(this, PlaybackService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    @Suppress("DEPRECATION") // the Icon-less Action.Builder: the resource-id one works on every Android version here
    private fun notification(title: String, playing: Boolean, mode: PlayerMode, again: Boolean = false): Notification {
        val tv = engine.tvNameNow
        fun act(icon: Int, label: Int, name: String, code: Int) =
            Notification.Action.Builder(icon, getString(label), action(name, code)).build()
        val pause = if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val stop = act(android.R.drawable.ic_menu_close_clear_cancel, R.string.stop, ACTION_STOP, 4)
        val actions = when (mode) {
            PlayerMode.MUSIC, PlayerMode.PHOTO -> listOf(
                act(android.R.drawable.ic_media_previous, R.string.previous, ACTION_PREVIOUS, 5),
                act(pause, if (mode == PlayerMode.PHOTO) R.string.slideshow else R.string.play_pause, ACTION_TOGGLE, 2),
                act(android.R.drawable.ic_media_next, R.string.next, ACTION_NEXT, 6), stop)
            // the TV left the screen (still shared): one tap shows it there again
            PlayerMode.SCREEN -> if (again) listOf(act(android.R.drawable.ic_media_play, R.string.show_again, ACTION_TOGGLE, 2), stop)
                else listOf(stop)
            PlayerMode.VIDEO, PlayerMode.INTRO -> listOf(
                act(android.R.drawable.ic_media_rew, R.string.back_10, ACTION_BACK, 1),
                act(pause, R.string.play_pause, ACTION_TOGGLE, 2),
                act(android.R.drawable.ic_media_ff, R.string.forward_10, ACTION_FORWARD, 3), stop)
        }
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_opuscast)
            .setContentTitle(title)
            .setContentText(if (tv != null) getString(R.string.notification_text, tv) else getString(R.string.app_name))
            .setContentIntent(openApp())
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        actions.forEach { b.addAction(it) }
        val compact = if (actions.size >= 3) intArrayOf(0, 1, 2) else intArrayOf(0)
        return b.setStyle(Notification.MediaStyle().setMediaSession(media.sessionToken).setShowActionsInCompactView(*compact)).build()
    }

    /** Foreground with the notification, as the kinds of service it is now: media playback, plus media
     *  projection (and recording what apps play) while the screen is cast. */
    private fun goForeground(title: String, playing: Boolean, screen: Boolean = engine.screenActive,
                             again: Boolean = showAgain(engine.ui.value.player)) {
        val mode = if (screen) PlayerMode.SCREEN else engine.ui.value.player?.mode ?: PlayerMode.VIDEO
        val n = notification(title, playing, mode, again)
        var want = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        if (screen && Build.VERSION.SDK_INT >= 29) {
            want = want or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (Build.VERSION.SDK_INT >= 30 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                want = want or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
        }
        if (!foreground || want != types) {
            try {
                ServiceCompat.startForeground(this, NOTIFICATION, n, want)
            } catch (e: SecurityException) {
                if (Build.VERSION.SDK_INT < 30 || want and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE == 0) throw e
                want = want and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE.inv() // the picture without the sound then
                ServiceCompat.startForeground(this, NOTIFICATION, n, want)
            }
            foreground = true
            types = want
        } else {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION, n)
        }
        shownTitle = title
        shownPlaying = playing
        shownMode = mode
        shownAgain = again
    }

    /** The TV left the shared screen, which is still shared (and isn't being sent there again right now). */
    private fun showAgain(p: PlayerUi?) = p != null && p.mode == PlayerMode.SCREEN && p.away && !p.sendingAgain

    private fun metadata(ui: UiState, p: PlayerUi, clock: Clock) {
        val b = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, p.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, p.artist ?: ui.tvName ?: "")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, (clock.dur * 1000).toLong())
        p.album?.let { b.putString(MediaMetadata.METADATA_KEY_ALBUM, it) }
        p.art?.let { b.putBitmap(MediaMetadata.METADATA_KEY_ART, it) }
        media.setMetadata(b.build())
        shownArt = p.art
    }

    private fun update(ui: UiState, clock: Clock) {
        if (!ui.sessionActive) {
            stopSelfSoon()
            return
        }
        val p = ui.player ?: return
        val title = p.title
        // photos don't play: what shows as playing is the slideshow; the shared screen plays while the TV shows it
        val playing = when (p.mode) {
            PlayerMode.PHOTO -> p.slideshow
            PlayerMode.SCREEN -> p.playing && !p.away
            else -> p.playing
        }
        // the TV left the shared screen a few seconds after it stopped playing there: then the notification
        // offers to show it on the TV again
        val again = showAgain(p)
        if (title != shownTitle || playing != shownPlaying || p.mode != shownMode || again != shownAgain) {
            goForeground(title, playing, again = again)
            metadata(ui, p, clock)
        } else if (p.art !== shownArt) {
            metadata(ui, p, clock)
        }
        // Android 13+ draws the shade's and the lock screen's controls from this state, not from the notification's
        // buttons: while the TV is away from the shared screen, Play there shows it on the TV again.
        // The system moves the position along by itself; tell it again only when it's off (seek, pause).
        val state = if (playing) PlaybackState.STATE_PLAYING
            else if (again || p.seekable || p.mode == PlayerMode.PHOTO) PlaybackState.STATE_PAUSED
            else PlaybackState.STATE_BUFFERING
        val posMs = if (p.mode == PlayerMode.PHOTO || p.mode == PlayerMode.SCREEN) PlaybackState.PLAYBACK_POSITION_UNKNOWN else (clock.pos * 1000).toLong()
        val nowMs = SystemClock.elapsedRealtime()
        val last = lastState
        val expected = if (last == null) -1L else last.second + if (last.first == PlaybackState.STATE_PLAYING) nowMs - last.third else 0
        if (last == null || last.first != state || Math.abs(expected - posMs) > 2000 || (posMs < 0 && last.second >= 0)) {
            lastState = Triple(state, posMs, nowMs)
            val actions = when (p.mode) {
                PlayerMode.MUSIC -> PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS
                PlayerMode.PHOTO -> PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
                PlayerMode.SCREEN -> if (again) PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP
                    else PlaybackState.ACTION_STOP
                PlayerMode.VIDEO, PlayerMode.INTRO -> PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP or
                    PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND
            }
            val ps = PlaybackState.Builder().setActions(actions).setState(state, posMs, if (playing) 1f else 0f, nowMs)
            if (p.mode == PlayerMode.VIDEO || p.mode == PlayerMode.INTRO) {
                ps.addCustomAction(PlaybackState.CustomAction.Builder(ACTION_BACK, getString(R.string.back_10), android.R.drawable.ic_media_rew).build())
                ps.addCustomAction(PlaybackState.CustomAction.Builder(ACTION_FORWARD, getString(R.string.forward_10), android.R.drawable.ic_media_ff).build())
            }
            media.setPlaybackState(ps.build())
            if (clock.dur > 0) metadata(ui, p, clock)
        }
        // the phone's volume buttons set the TV's volume
        val v = ui.volume
        if (v != null) {
            val provider = volume
            if (provider == null) {
                val created = object : VolumeProvider(VolumeProvider.VOLUME_CONTROL_ABSOLUTE, 100, v) {
                    override fun onSetVolumeTo(volume: Int) {
                        engine.setVolume(volume)
                        currentVolume = volume.coerceIn(0, 100)
                    }
                    override fun onAdjustVolume(direction: Int) {
                        if (direction != 0) engine.volumeStep(direction * 2)
                    }
                }
                volume = created
                media.setPlaybackToRemote(created)
            } else if (provider.currentVolume != v) {
                provider.currentVolume = v
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        media.isActive = false
        media.release()
        runCatching { wakeLock?.release() }
        wifiLocks.forEach { runCatching { it.release() } }
        super.onDestroy()
    }
}
