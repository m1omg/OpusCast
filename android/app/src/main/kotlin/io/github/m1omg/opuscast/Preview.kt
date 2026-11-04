package io.github.m1omg.opuscast

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A muted copy of the video on the phone, kept in step with the TV. Main thread only. */
@OptIn(UnstableApi::class)
class Preview(private val context: Context, private val onError: (String) -> Unit) {
    private val _player = MutableStateFlow<ExoPlayer?>(null)
    /** The player to show, while a video is loaded. */
    val player: StateFlow<ExoPlayer?> = _player
    private var exo: ExoPlayer? = null
    var ref: String? = null
        private set
    private var playing = false

    private fun create(): ExoPlayer {
        val p = ExoPlayer.Builder(context).build()
        p.volume = 0f
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                unload()
                onError(error.cause?.message ?: error.errorCodeName)
            }
        })
        exo = p
        return p
    }

    fun load(ref: String, pos: Double) {
        val p = exo ?: create()
        p.setMediaItem(MediaItem.fromUri(ref))
        p.setSeekParameters(SeekParameters.EXACT)
        p.seekTo((pos * 1000).toLong().coerceAtLeast(0))
        p.playWhenReady = false
        p.prepare()
        this.ref = ref
        playing = false
        _player.value = p
    }

    fun unload() {
        exo?.let {
            it.stop()
            it.clearMediaItems()
        }
        ref = null
        playing = false
        _player.value = null
    }

    fun seek(pos: Double, accurate: Boolean = true) {
        val p = exo ?: return
        if (ref == null) return
        p.setSeekParameters(if (accurate) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
        p.seekTo((pos * 1000).toLong().coerceAtLeast(0))
    }

    fun setPlaying(on: Boolean) {
        if (ref != null && on != playing) {
            playing = on
            exo?.playWhenReady = on
        }
    }

    /** Seconds, or null before the video is ready. */
    fun position(): Double? {
        val p = exo ?: return null
        if (ref == null || p.playbackState == Player.STATE_IDLE) return null
        return p.currentPosition / 1000.0
    }

    fun release() {
        unload()
        exo?.release()
        exo = null
    }
}
