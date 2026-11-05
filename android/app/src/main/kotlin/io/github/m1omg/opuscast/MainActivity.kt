package io.github.m1omg.opuscast

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings as AndroidSettings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat

class MainActivity : ComponentActivity() {
    private val engine by lazy { applicationContext.engine }

    /** The system file picker, opening in [initial] when given. */
    private class OpenIn : ActivityResultContracts.OpenDocument() {
        var initial: Uri? = null
        override fun createIntent(context: Context, input: Array<String>): Intent {
            val i = super.createIntent(context, input)
            initial?.let { i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
            return i
        }
    }

    private val subContract = OpenIn()
    private val pickMedia = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) engine.open(uris, persistable = true)
    }
    private val pickSubtitle = registerForActivityResult(subContract) { uri -> uri?.let { engine.useSubtitleFile(it) } }
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { engine.folderGranted(it) } }
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val askStorage = registerForActivityResult(ActivityResultContracts.RequestPermission()) { engine.refreshSubtitles() }
    /** Recording what apps play needs the microphone permission; without it the screen goes with silence. */
    private val askSound = registerForActivityResult(ActivityResultContracts.RequestPermission()) { askScreen() }
    private val captureScreen = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) PlaybackService.startScreen(this, r.resultCode, data)
        else engine.screenRefused()
    }
    private var askedNotifications = false
    private var askedSound = false
    private var wentToSettings = false

    /** Off while a sheet or dialog is open, so typing there isn't taken as player keys. */
    var playerKeys = true

    val actions = object : UiActions {
        override fun chooseVideo() {
            askNotificationsOnce()
            pickMedia.launch(arrayOf("video/*", "audio/*", "image/*", "application/x-matroska", "application/ogg"))
        }
        override fun castScreen() {
            if (!engine.canCastScreen()) return
            askNotificationsOnce()
            if (Build.VERSION.SDK_INT >= 29 && !askedSound &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                askedSound = true
                askSound.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                askScreen()
            }
        }
        override fun chooseSubtitleFile() {
            subContract.initial = engine.folderUri()
            pickSubtitle.launch(arrayOf("*/*"))
        }
        override fun allowFolder() {
            pickFolder.launch(engine.folderUri())
        }
        override fun allowAllFiles() {
            if (Build.VERSION.SDK_INT >= 30) {
                wentToSettings = true
                val app = Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                runCatching { startActivity(app) }.onFailure {
                    runCatching { startActivity(Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                }
            } else {
                askStorage.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        override fun setPlayerKeys(on: Boolean) {
            playerKeys = on
        }
    }

    private fun askScreen() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        runCatching { captureScreen.launch(mpm.createScreenCaptureIntent()) }.onFailure { engine.screenRefused(it.message) }
    }

    private fun askNotificationsOnce() {
        if (askedNotifications || Build.VERSION.SDK_INT < 33) return
        askedNotifications = true
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { OpusCastTheme { OpusCastScreen(engine, actions) } }
        if (savedInstanceState == null) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val uris = when (intent?.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        askNotificationsOnce()
        engine.open(uris, persistable = true)
    }

    override fun onStart() {
        super.onStart()
        engine.setUiVisible(true)
    }

    override fun onResume() {
        super.onResume()
        if (wentToSettings) { // back from "all files access"
            wentToSettings = false
            engine.refreshSubtitles()
        }
    }

    override fun onStop() {
        engine.setUiVisible(false)
        super.onStop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val state = engine.ui.value
        if (event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_O) {
            actions.chooseVideo()
            return true
        }
        if (!state.sessionActive || !playerKeys || event.isCtrlPressed) return super.onKeyDown(keyCode, event)
        val mode = state.player?.mode
        val photos = mode == PlayerMode.PHOTO
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> if (state.volume != null) engine.volumeStep(5) else return super.onKeyDown(keyCode, event)
            KeyEvent.KEYCODE_VOLUME_DOWN -> if (state.volume != null) engine.volumeStep(-5) else return super.onKeyDown(keyCode, event)
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> engine.togglePlay()
            KeyEvent.KEYCODE_MEDIA_NEXT -> engine.next()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> engine.previous()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND ->
                if (photos) engine.previousPhoto() else engine.skip(if (event.isShiftPressed) -30.0 else -10.0)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ->
                if (photos) engine.nextPhoto() else engine.skip(if (event.isShiftPressed) 30.0 else 10.0)
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_N -> engine.next()
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_B -> engine.previous()
            KeyEvent.KEYCODE_DPAD_UP -> engine.volumeStep(5)
            KeyEvent.KEYCODE_DPAD_DOWN -> engine.volumeStep(-5)
            KeyEvent.KEYCODE_S -> engine.cycleSubtitles()
            KeyEvent.KEYCODE_P -> engine.setPreview(!state.previewOn)
            KeyEvent.KEYCODE_MEDIA_STOP -> engine.stop()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }
}
