package io.github.m1omg.opuscast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
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
        /** Long enough for any film; renewed whenever a video starts, released when playback ends. */
        private const val WAKE_MS = 6 * 60 * 60 * 1000L

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
            } catch (e: Exception) {
                // not allowed from the background (Android 12+); playback still works while the app is open
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
    private var foreground = false

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
                override fun onPlay() { if (!engine.isPlaying) engine.togglePlay() }
                override fun onPause() { if (engine.isPlaying) engine.togglePlay() }
                override fun onStop() = engine.stop()
                override fun onSeekTo(pos: Long) = engine.seekTo(pos / 1000.0)
                override fun onFastForward() = engine.skip(10.0)
                override fun onRewind() = engine.skip(-10.0)
                override fun onSkipToNext() = engine.skip(30.0)
                override fun onSkipToPrevious() = engine.skip(-30.0)
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
        // every startForegroundService() must be answered with startForeground()
        goForeground(shownTitle ?: getString(R.string.app_name), shownPlaying ?: false)
        runCatching { wakeLock?.acquire(WAKE_MS) } // a new video: the full time again
        when (intent?.action) {
            ACTION_TOGGLE -> engine.togglePlay()
            ACTION_BACK -> engine.skip(-10.0)
            ACTION_FORWARD -> engine.skip(10.0)
            ACTION_STOP -> engine.stopFromNotification()
        }
        if (!engine.hasSession) stopSelfSoon()
        return START_NOT_STICKY
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

    private fun notification(title: String, playing: Boolean): Notification {
        val tv = engine.tvNameNow
        @Suppress("DEPRECATION")
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_opuscast)
            .setContentTitle(title)
            .setContentText(if (tv != null) getString(R.string.notification_text, tv) else getString(R.string.app_name))
            .setContentIntent(openApp())
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_rew, getString(R.string.back_10), action(ACTION_BACK, 1)).build())
            .addAction(Notification.Action.Builder(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                getString(R.string.play_pause), action(ACTION_TOGGLE, 2)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_ff, getString(R.string.forward_10), action(ACTION_FORWARD, 3)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.stop), action(ACTION_STOP, 4)).build())
            .setStyle(Notification.MediaStyle().setMediaSession(media.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun goForeground(title: String, playing: Boolean) {
        val n = notification(title, playing)
        if (!foreground) {
            ServiceCompat.startForeground(this, NOTIFICATION, n,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
            foreground = true
        } else {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION, n)
        }
        shownTitle = title
        shownPlaying = playing
    }

    private fun update(ui: UiState, clock: Clock) {
        if (!ui.sessionActive) {
            stopSelfSoon()
            return
        }
        val p = ui.player ?: return
        val title = p.title
        if (title != shownTitle || p.playing != shownPlaying) {
            goForeground(title, p.playing)
            media.setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, ui.tvName ?: "")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, (clock.dur * 1000).toLong())
                .build())
        }
        // the system moves the position along by itself; tell it again only when it's off (seek, pause)
        val state = if (p.playing) PlaybackState.STATE_PLAYING else if (p.seekable) PlaybackState.STATE_PAUSED else PlaybackState.STATE_BUFFERING
        val posMs = (clock.pos * 1000).toLong()
        val nowMs = SystemClock.elapsedRealtime()
        val last = lastState
        val expected = if (last == null) -1L else last.second + if (last.first == PlaybackState.STATE_PLAYING) nowMs - last.third else 0
        if (last == null || last.first != state || Math.abs(expected - posMs) > 2000) {
            lastState = Triple(state, posMs, nowMs)
            media.setPlaybackState(PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP or PlaybackState.ACTION_FAST_FORWARD or
                    PlaybackState.ACTION_REWIND)
                .addCustomAction(PlaybackState.CustomAction.Builder(ACTION_BACK, getString(R.string.back_10), android.R.drawable.ic_media_rew).build())
                .addCustomAction(PlaybackState.CustomAction.Builder(ACTION_FORWARD, getString(R.string.forward_10), android.R.drawable.ic_media_ff).build())
                .setState(state, posMs, if (p.playing) 1f else 0f, nowMs)
                .build())
            if (clock.dur > 0) media.setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, ui.tvName ?: "")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, (clock.dur * 1000).toLong())
                .build())
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
