#!/usr/bin/env python3
"""OpusCast - play videos, music and photos from this computer on your smart TV, or show it the desktop.

The Windows and macOS app (Qt). It runs on Linux too, where opuscast-gui (GTK) is the usual one: both
drive the TV the same way through opuscast_core, and use the same texts and settings.
"""
import os
import queue
import shutil
import subprocess
import sys
import threading
import time
import urllib.parse

HERE = os.path.dirname(os.path.realpath(__file__))
BUNDLE = getattr(sys, "_MEIPASS", HERE)  # where the packaged app keeps its files
sys.path.insert(0, HERE)


def _use_bundled_tools():
    """The packaged app brings its own ffmpeg: put it first on PATH, so every part finds it."""
    exe = "ffmpeg.exe" if sys.platform == "win32" else "ffmpeg"
    for d in (os.path.join(BUNDLE, "ffmpeg"), BUNDLE):
        if os.path.isfile(os.path.join(d, exe)):
            os.environ["PATH"] = d + os.pathsep + os.environ.get("PATH", "")
            return


_use_bundled_tools()
if sys.platform == "win32":
    # a windowed app: the tools it runs (ffmpeg, arp, route...) mustn't flash up a console window each time
    _popen_init = subprocess.Popen.__init__

    def _quiet_popen(self, *args, **kwargs):
        kwargs.setdefault("creationflags", 0x08000000)  # CREATE_NO_WINDOW
        _popen_init(self, *args, **kwargs)
    subprocess.Popen.__init__ = _quiet_popen

import opuscast_core as core  # noqa: E402
import opuscast_desktop as desk  # noqa: E402
import opuscast_intro as intro  # noqa: E402
import opuscast_media as media  # noqa: E402
from opuscast_i18n import _  # noqa: E402

from PySide6.QtCore import QObject, QSize, Qt, QTimer, QUrl, Signal, Slot  # noqa: E402
from PySide6.QtGui import QFont, QIcon, QImage, QImageReader, QPixmap  # noqa: E402
from PySide6.QtWidgets import (  # noqa: E402
    QApplication, QCheckBox, QComboBox, QFileDialog, QFrame, QGridLayout, QHBoxLayout, QLabel, QListWidget,
    QListWidgetItem, QMainWindow, QMenu, QProgressBar, QPushButton, QSizePolicy, QSlider, QStackedWidget,
    QStyle, QToolButton, QVBoxLayout, QWidget)

try:
    from PySide6.QtMultimedia import QMediaPlayer
    from PySide6.QtMultimediaWidgets import QVideoWidget
except ImportError:  # the slim install: everything works but "Show on PC"
    QMediaPlayer = QVideoWidget = None

fmt = core.fmt_time
KEEP = object()
INFO, WARNING, ERROR = "info", "warning", "error"
SLIDE_SECONDS = (3, 5, 10, 20, 30)
DEVICE_ICONS = {"tv": "📺", "speaker": "🔈", "router": "📶", "pc": "💻", "upnp": "🖧", "unknown": "❔"}
MODE_ICONS = {"music": "♫", "photo": "🖼", "desktop": "🖥"}
STYLE = """
QFrame#dropzone { border: 2px dashed palette(mid); border-radius: 14px; }
QFrame#dropzone[hover="true"] { border-color: palette(highlight); }
QLabel[role="big"] { font-size: 17pt; font-weight: bold; }
QLabel[role="dim"] { color: palette(placeholder-text); }
QLabel[role="section"] { font-weight: bold; }
QLabel[role="ok"] { color: #26a269; font-weight: bold; }
QLabel[role="audio"] { color: #c88800; font-weight: bold; }
QLabel[role="live"] { color: #e01b24; font-weight: bold; }
QPushButton[role="play"] { min-width: 64px; min-height: 64px; max-width: 64px; max-height: 64px;
                           border-radius: 32px; font-size: 22px; border: none; color: white; background: #3584e4; }
QPushButton[role="play"]:hover { background: #1c71d8; }
QPushButton[role="play"]:pressed { background: #1a5fb4; }
QPushButton[role="suggested"] { font-weight: bold; }
QWidget#videoArea { background: black; }
"""


def shorten(text, n=60):
    return text if len(text) <= n else text[:n - 1] + "…"


def label(text="", role=None, wrap=False, align=None):
    lbl = QLabel(text)
    if role:
        lbl.setProperty("role", role)
    lbl.setWordWrap(wrap)
    if align is not None:
        lbl.setAlignment(align)
    return lbl


# ---------------------------------------------------------------- threads

class _Bridge(QObject):
    """Runs functions on the window's thread, whichever thread asks."""
    ran = Signal(object)

    def __init__(self):
        super().__init__()
        self.ran.connect(self._run, Qt.QueuedConnection)

    @Slot(object)
    def _run(self, fn):
        fn()


_bridge = None


def post(fn, *args):
    _bridge.ran.emit(lambda: fn(*args))


def after(ms, fn, *args):
    QTimer.singleShot(int(ms), lambda: fn(*args))


class Worker:
    """Runs TV commands one after another, off the window's thread."""

    def __init__(self, on_error):
        self.q, self.on_error = queue.Queue(), on_error
        threading.Thread(target=self._run, daemon=True).start()

    def submit(self, fn, *args, done=None, **kwargs):
        self.q.put((fn, args, kwargs, done))

    def _run(self):
        while True:
            fn, args, kwargs, done = self.q.get()
            try:
                result = fn(*args, **kwargs)
            except Exception as e:  # noqa: BLE001 - every failure is shown to the user
                post(self.on_error, e)
                continue
            if done:
                post(done, result)


class Poller(threading.Thread):
    """Asks the TV what it's doing: twice a second while playing, every 5 s otherwise."""

    def __init__(self, callback):
        super().__init__(daemon=True)
        self.callback, self.tv, self.wake = callback, None, threading.Event()
        self.active = False

    def run(self):
        last_volume = 0.0
        while True:
            tv = self.tv
            if tv is None:
                self.wake.wait(1)
                self.wake.clear()
                continue
            result = {"tv": tv}
            try:
                result["state"] = tv.state()
                result["pos"], result["dur"], result["uri"] = tv.position()
                if time.monotonic() - last_volume > 4:
                    last_volume = time.monotonic()
                    try:
                        result["volume"] = tv.get_volume()
                    except core.TVError:
                        pass
            except core.TVError as e:
                result["error"] = str(e)
            post(self.callback, result)
            self.wake.wait(2.0 if "error" in result else 0.5 if self.active else 5.0)
            self.wake.clear()


# ---------------------------------------------------------------- the PC preview

class Preview:
    """A muted copy of the video in the window, kept in step with the TV (Qt Multimedia)."""

    LOADED = ()

    def __init__(self, on_error):
        self.on_error, self.path, self.pending, self.want_playing = on_error, None, None, False
        self.ok = QMediaPlayer is not None
        self.reason = "" if self.ok else _("Qt Multimedia isn't installed")
        self.widget = None
        if not self.ok:
            return
        self.player = QMediaPlayer()
        self.widget = QVideoWidget()
        self.player.setVideoOutput(self.widget)  # and no audio output: muted
        self.player.errorOccurred.connect(lambda _e, msg: self.path and self.on_error(msg or "?"))
        self.player.mediaStatusChanged.connect(self._status)
        S = QMediaPlayer.MediaStatus
        Preview.LOADED = (S.LoadedMedia, S.BufferingMedia, S.BufferedMedia, S.EndOfMedia)

    def _status(self, status):
        if status in self.LOADED and self.pending is not None:
            pos, self.pending = self.pending, None
            self.player.setPosition(int(pos * 1000))
            self.set_playing(self.want_playing)

    def load(self, path, pos=0.0):
        if not self.ok:
            return
        self.path, self.pending = path, max(0.0, pos)
        self.player.setSource(QUrl.fromLocalFile(path))
        self.player.pause()

    def unload(self):
        if self.ok and self.path:
            self.player.stop()
            self.player.setSource(QUrl())
        self.path, self.pending = None, None

    def seek(self, pos, accurate=True):  # noqa: ARG002 - same call as the GTK preview
        if not self.path:
            return
        if self.pending is not None:
            self.pending = max(0.0, pos)
        else:
            self.player.setPosition(int(max(0.0, pos) * 1000))

    def set_playing(self, on):
        self.want_playing = on
        if not self.path or self.pending is not None:
            return
        state = self.player.playbackState()
        if on and state != QMediaPlayer.PlaybackState.PlayingState:
            self.player.play()
        elif not on and state == QMediaPlayer.PlaybackState.PlayingState:
            self.player.pause()

    def position(self):
        if not self.path or self.pending is not None:
            return None
        return self.player.position() / 1000


# ---------------------------------------------------------------- small widgets

class InfoBar(QFrame):
    """A message across the top of the window, with buttons."""
    COLORS = {INFO: ("#dbe9ff", "#16325c"), WARNING: ("#fff1cc", "#4d3700"), ERROR: ("#ffd9d9", "#5c1616")}

    def __init__(self, text, kind, actions, selectable, on_gone):
        super().__init__()
        bg, fg = self.COLORS[kind]
        self.setStyleSheet(f"InfoBar {{ background: {bg}; border-bottom: 1px solid {fg}33; }}"
                           f"QLabel {{ color: {fg}; }}")
        row = QHBoxLayout(self)
        row.setContentsMargins(12, 8, 8, 8)
        col = QVBoxLayout()
        col.addWidget(label(text, wrap=True))
        if selectable:
            cmd = label(selectable, wrap=True)
            cmd.setTextInteractionFlags(Qt.TextSelectableByMouse)
            mono = QFont("monospace")
            mono.setStyleHint(QFont.Monospace)
            cmd.setFont(mono)
            col.addWidget(cmd)
        row.addLayout(col, 1)
        for text_, callback in actions:
            b = QPushButton(text_)
            b.clicked.connect(lambda _c=False, cb=callback: (self.close_bar(), cb()))
            row.addWidget(b, 0, Qt.AlignVCenter)
        close = QToolButton(text="✕")
        close.setAutoRaise(True)
        close.clicked.connect(self.close_bar)
        row.addWidget(close, 0, Qt.AlignTop)
        self.on_gone = on_gone

    def close_bar(self):
        if self.parent() is not None:
            self.on_gone(self)


class Popup(QFrame):
    """A panel that drops down under a button and closes on a click elsewhere."""

    def __init__(self, parent):
        super().__init__(parent, Qt.Popup)
        self.setFrameShape(QFrame.StyledPanel)
        self.on_show = None

    def show_under(self, button):
        if self.on_show:
            self.on_show()
        self.adjustSize()
        pos = button.mapToGlobal(button.rect().bottomLeft())
        screen = button.screen().availableGeometry()
        pos.setX(max(screen.left(), min(pos.x(), screen.right() - self.width())))
        self.move(pos)
        self.show()


def pixmap_from(path=None, data=None, box=(640, 400)):
    """A picture scaled to fit box, or None. QImage reads JPEG, PNG, GIF, BMP, WebP (and HEIC where the
    system can); for others the converted JPEG is used. Fine off the window's thread (QImage only)."""
    if data is not None:
        img = QImage.fromData(data)
    elif path:
        reader = QImageReader(path)
        reader.setAutoTransform(True)  # phones store photos sideways with an "upright" note
        img = reader.read()
    else:
        return None
    if img.isNull():
        return None
    return img.scaled(QSize(*box), Qt.KeepAspectRatio, Qt.SmoothTransformation) \
        if img.width() > box[0] or img.height() > box[1] else img


# ---------------------------------------------------------------- the window

class OpusCastWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle(core.APP_NAME)
        self.resize(780, 680)
        self.cfg = core.load_config()
        self.server = core.MediaServer()
        self.server.httpd.on_event = lambda *a: post(self._on_event, *a)
        self.tv = None
        self.session = None
        self.gen = 0
        self.tv_state, self.tv_pos, self.tv_pos_raw, self.pos_seen, self.tv_dur = "UNKNOWN", 0.0, None, 0.0, 0.0
        self.hold = None  # (target, since, timeout) after a seek, until the TV catches up
        self.dragging, self.drag_value, self.last_scrub = False, None, 0.0
        self.vol_touched, self.setting_vol, self.setting_slider = 0.0, False, False
        self.last_save, self.poll_errors, self.range_dur = 0.0, 0, None
        self.devices, self.scanning, self.scanned_at = [], False, 0.0
        self.pending_play = None  # (function, args) to run once a TV is found
        self.mode, self.slideshow, self.pending_cast = None, False, None
        self.event_sids, self.last_caption_event, self.last_caption_cmd = {}, 0.0, 0.0
        self.events_pending = False  # a SUBSCRIBE is on its way
        self.play_icon = "play"
        self.worker = Worker(self._on_worker_error)
        self.preview = Preview(self._on_preview_error)
        self.seek_timer = self._timer(350, lambda: self.seek_to(self.slider.value() / 10))
        self.vol_timer = self._timer(150, self._send_volume)
        self.slide_timer = self._timer(5000, self._slide_due)
        self._build_ui()
        self.poller = Poller(self._on_poll)
        self.poller.start()
        self.ticker = QTimer(self)
        self.ticker.timeout.connect(self._tick)
        self.ticker.start(250)
        self.renewer = QTimer(self)
        self.renewer.timeout.connect(self._renew_events)
        self.renewer.start(600_000)
        self.setAcceptDrops(True)
        self._find_tv()

    def _timer(self, ms, fn):
        t = QTimer(self)
        t.setSingleShot(True)
        t.setInterval(ms)
        t.timeout.connect(fn)
        return t

    # ------------------------------------------------------------ UI construction

    def _icon(self, name):
        return self.style().standardIcon(getattr(QStyle.StandardPixmap, name))

    def _build_ui(self):
        central = QWidget()
        outer = QVBoxLayout(central)
        outer.setContentsMargins(0, 0, 0, 0)
        outer.setSpacing(0)

        top = QHBoxLayout()
        top.setContentsMargins(12, 8, 12, 8)
        self.dev_button = QToolButton()
        self.dev_button.setToolButtonStyle(Qt.ToolButtonTextOnly)
        self.dev_button.setToolTip(_("Choose the TV - see which devices on your network can play video"))
        self.dev_button.setText("📺  " + _("TV") + "  ▾")
        self.dev_button.clicked.connect(self._show_devices)
        top.addWidget(self.dev_button)
        self.subtitle_label = label(role="dim")
        top.addWidget(self.subtitle_label, 1)
        open_btn = QPushButton(self._icon("SP_DialogOpenButton"), _("Open"))
        open_btn.setToolTip(_("Choose videos, music or photos (Ctrl+O)"))
        open_btn.clicked.connect(self.choose_file)
        top.addWidget(open_btn)
        outer.addLayout(top)
        outer.addWidget(self._line())
        self._set_subtitle(_("Looking for your TV…"))

        self.info_holder = QWidget()
        self.info_box = QVBoxLayout(self.info_holder)
        self.info_box.setContentsMargins(0, 0, 0, 0)
        self.info_box.setSpacing(0)
        outer.addWidget(self.info_holder)
        self.stack = QStackedWidget()
        self.stack.addWidget(self._build_pick_page())
        self.stack.addWidget(self._build_player_page())
        outer.addWidget(self.stack, 1)
        outer.addWidget(self._line())
        outer.addWidget(self._build_options_bar())
        self.setCentralWidget(central)
        self.devices_popup = self._build_device_popup()
        self.desk_popup = self._build_desktop_popup()

    @staticmethod
    def _line():
        line = QFrame()
        line.setFrameShape(QFrame.HLine)
        line.setFrameShadow(QFrame.Sunken)
        return line

    def _build_pick_page(self):
        page = QWidget()
        col = QVBoxLayout(page)
        col.setContentsMargins(18, 18, 18, 18)
        col.setSpacing(14)
        self.dropzone = QFrame(objectName="dropzone")
        dz = QVBoxLayout(self.dropzone)
        dz.setContentsMargins(26, 26, 26, 26)
        dz.setSpacing(10)
        icon = QLabel()
        icon.setPixmap(QIcon(app_icon_path()).pixmap(72, 72))
        icon.setAlignment(Qt.AlignCenter)
        dz.addWidget(icon)
        dz.addWidget(label(_("Drop videos, music or photos here"), "big", align=Qt.AlignCenter))
        dz.addWidget(label(_("They play on your TV - control them with the TV remote or the buttons here."), "dim",
                           wrap=True, align=Qt.AlignCenter))
        buttons = QHBoxLayout()
        buttons.addStretch(1)
        choose = QPushButton(_("Choose…"))
        choose.setProperty("role", "suggested")
        choose.setDefault(True)
        choose.setToolTip(_("Videos, music or photos - pick several songs or photos to play them one after another"))
        choose.setMinimumSize(200, 46)
        choose.clicked.connect(self.choose_file)
        buttons.addWidget(choose)
        cast = QPushButton("🖥  " + _("Cast Desktop"))
        cast.setToolTip(_("Show this PC's screen and sound on the TV (Ctrl+D)"))
        cast.setMinimumHeight(46)
        cast.clicked.connect(self.cast_desktop)
        buttons.addWidget(cast)
        self.desk_opts = QToolButton(text="▾")
        self.desk_opts.setToolTip(_("Desktop casting settings"))
        self.desk_opts.setMinimumHeight(46)
        self.desk_opts.clicked.connect(lambda: self.desk_popup.show_under(self.desk_opts))
        buttons.addWidget(self.desk_opts)
        buttons.addStretch(1)
        dz.addLayout(buttons)
        col.addWidget(self.dropzone)
        col.addWidget(label(_("Recent"), "section"))
        self.recent_list = QListWidget()
        self.recent_list.itemClicked.connect(lambda item: self.play_file(item.data(Qt.UserRole)))
        self.recent_empty = label(_("Videos you play will show up here, so you can carry on where you left off."),
                                  "dim", wrap=True, align=Qt.AlignCenter)
        col.addWidget(self.recent_list, 1)
        col.addWidget(self.recent_empty, 1)
        self._refresh_recent()
        return page

    def _build_player_page(self):
        page = QWidget()
        col = QVBoxLayout(page)
        col.setContentsMargins(18, 18, 18, 18)
        col.setSpacing(10)
        self.video_area = QStackedWidget()
        self.video_area.setMinimumSize(480, 270)
        self.video_area.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Expanding)
        holder = QWidget()
        ph = QVBoxLayout(holder)
        ph.addStretch(1)
        self.placeholder_icon = label("📺", align=Qt.AlignCenter)
        self.placeholder_icon.setStyleSheet("font-size: 72px;")
        self.art = label(align=Qt.AlignCenter)  # a song's cover or the photo on the TV
        self.art.hide()
        self.placeholder_label = label(role="dim", align=Qt.AlignCenter)
        for w in (self.placeholder_icon, self.art, self.placeholder_label):
            ph.addWidget(w)
        ph.addStretch(1)
        self.video_area.addWidget(holder)
        if self.preview.ok:
            self.preview.widget.setObjectName("videoArea")
            self.video_area.addWidget(self.preview.widget)
        col.addWidget(self.video_area, 1)

        self.title_label = label(role="big", align=Qt.AlignCenter)
        self.title_label.setTextInteractionFlags(Qt.TextSelectableByMouse)
        self.status_label = label(role="dim", align=Qt.AlignCenter, wrap=True)
        col.addWidget(self.title_label)
        col.addWidget(self.status_label)

        self.slider_row = QWidget()
        row = QHBoxLayout(self.slider_row)
        row.setContentsMargins(0, 0, 0, 0)
        self.elapsed, self.total = label("0:00:00"), label("--:--")
        self.slider = QSlider(Qt.Horizontal)
        self.slider.setToolTip(_("Drag to jump (with “Show on PC” on you see where you're going)"))
        self.slider.setRange(0, 1)
        self.slider.sliderPressed.connect(self._on_slider_pressed)
        self.slider.sliderReleased.connect(self._on_slider_released)
        self.slider.sliderMoved.connect(self._on_slider_moved)
        self.slider.actionTriggered.connect(self._on_slider_action)
        row.addWidget(self.elapsed)
        row.addWidget(self.slider, 1)
        row.addWidget(self.total)
        col.addWidget(self.slider_row)

        self.play_buttons = []

        def button(text, tip, callback, icon=None):
            b = QPushButton(text)
            if icon:
                b.setIcon(self._icon(icon))
            b.setToolTip(tip)
            b.setMinimumHeight(44)
            b.clicked.connect(lambda _c=False: callback())
            return b

        def play_button(tip, callback):
            b = QPushButton("▶")
            b.setProperty("role", "play")
            b.setToolTip(tip)
            b.clicked.connect(lambda _c=False: callback())
            return b

        def centered(*widgets):
            w = QWidget()
            r = QHBoxLayout(w)
            r.setContentsMargins(0, 0, 0, 0)
            r.addStretch(1)
            for x in widgets:
                r.addWidget(x)
            r.addStretch(1)
            return w

        # the middle row changes with what's on: a video, songs, photos or the desktop
        self.transport = QStackedWidget()
        self.transport.setSizePolicy(QSizePolicy.Preferred, QSizePolicy.Maximum)
        self.play_btn = play_button(_("Play / pause (Space)"), self.toggle_play)
        self.play_buttons.append(self.play_btn)
        self.transport.addWidget(centered(
            button(_("30s"), _("Back 30 seconds (Shift+←)"), lambda: self.skip(-30), "SP_MediaSeekBackward"),
            button(_("10s"), _("Back 10 seconds (←)"), lambda: self.skip(-10), "SP_MediaSeekBackward"),
            self.play_btn,
            button(_("10s"), _("Forward 10 seconds (→)"), lambda: self.skip(10), "SP_MediaSeekForward"),
            button(_("30s"), _("Forward 30 seconds (Shift+→)"), lambda: self.skip(30), "SP_MediaSeekForward")))
        music_play = play_button(_("Play / pause (Space)"), self.toggle_play)
        self.play_buttons.append(music_play)
        self.transport.addWidget(centered(
            button("", _("Previous song (Page Up)"), lambda: self.step(-1), "SP_MediaSkipBackward"),
            button(_("10s"), _("Back 10 seconds (←)"), lambda: self.skip(-10), "SP_MediaSeekBackward"),
            music_play,
            button(_("10s"), _("Forward 10 seconds (→)"), lambda: self.skip(10), "SP_MediaSeekForward"),
            button("", _("Next song (Page Down)"), lambda: self.step(1), "SP_MediaSkipForward")))
        self.slide_btn = play_button(_("Slideshow on / off (Space)"), self.toggle_slideshow)
        self.slide_every = QComboBox()
        self.slide_every.setToolTip(_("How long each photo stays"))
        for secs in SLIDE_SECONDS:
            self.slide_every.addItem(_("{n} s each").format(n=secs), secs)
        want = int(self.cfg.get("slide_seconds", 5))
        self.slide_every.setCurrentIndex(SLIDE_SECONDS.index(want) if want in SLIDE_SECONDS else 1)
        self.slide_every.currentIndexChanged.connect(self._on_slide_every)
        self.transport.addWidget(centered(
            button(_("Previous"), _("Previous photo (←)"), lambda: self.step(-1), "SP_ArrowBack"),
            self.slide_btn,
            button(_("Next"), _("Next photo (→)"), lambda: self.step(1), "SP_ArrowForward"),
            self.slide_every))
        self.transport.addWidget(centered(label("● " + _("LIVE"), "live"),
                                          label(_("The TV shows the screen a second or two late."), "dim")))
        self.transport_pages = {"video": 0, "music": 1, "photo": 2, "live": 3}
        col.addWidget(self.transport)

        stop = button(_("Stop"), _("Stop playback"), self.stop, "SP_MediaStop")
        self.sub_button = QToolButton()
        self.sub_button.setText(_("Subtitles"))
        self.sub_button.setToolTip(_("Subtitles (S)"))
        self.sub_button.setMinimumHeight(44)
        self.sub_button.setPopupMode(QToolButton.InstantPopup)
        self.sub_menu = QMenu(self)
        self.sub_menu.aboutToShow.connect(self._fill_sub_menu)
        self.sub_button.setMenu(self.sub_menu)
        self.sub_button.hide()
        self.tracks_button = QToolButton()
        self.tracks_button.setText("☰  " + _("Songs"))
        self.tracks_button.setToolTip(_("All songs"))
        self.tracks_button.setMinimumHeight(44)
        self.tracks_button.setPopupMode(QToolButton.InstantPopup)
        self.tracks_menu = QMenu(self)
        self.tracks_menu.aboutToShow.connect(self._fill_tracks)
        self.tracks_button.setMenu(self.tracks_menu)
        self.tracks_button.hide()
        self.skip_intro_btn = button(_("Skip intro"), _("Start the video now"), self.skip_intro, "SP_MediaSkipForward")
        self.skip_intro_btn.hide()
        self.volume_box = QWidget()
        vb = QHBoxLayout(self.volume_box)
        vb.setContentsMargins(6, 0, 0, 0)
        vol_icon = QLabel()
        vol_icon.setPixmap(self._icon("SP_MediaVolume").pixmap(20, 20))
        self.volume = QSlider(Qt.Horizontal)
        self.volume.setRange(0, 100)
        self.volume.setFixedWidth(120)
        self.volume.setToolTip(_("TV volume (↑/↓)"))
        self.volume.valueChanged.connect(self._on_volume)
        vb.addWidget(vol_icon)
        vb.addWidget(self.volume)
        col.addWidget(centered(stop, self.sub_button, self.tracks_button, self.skip_intro_btn, self.volume_box))
        return page

    def _build_options_bar(self):
        bar = QWidget()
        row = QHBoxLayout(bar)
        row.setContentsMargins(18, 8, 18, 8)
        row.setSpacing(22)
        self.preview_switch = QCheckBox(_("Show on PC"))
        self.preview_switch.setToolTip(_("Show a muted copy of the video here, in step with the TV (P)"))
        self.preview_switch.setChecked(bool(self.cfg.get("preview")) and self.preview.ok)
        self.preview_switch.toggled.connect(self._on_preview_toggled)
        self.intro_switch = QCheckBox(_("Skynet intro"))
        self.intro_switch.setToolTip(_("Play the Skynet boot animation before each video"))
        self.intro_switch.setChecked(bool(self.cfg.get("intro")) and intro.available()[0])
        self.intro_switch.toggled.connect(self._on_intro_toggled)
        row.addWidget(self.preview_switch)
        row.addWidget(self.intro_switch)
        row.addStretch(1)
        row.addWidget(label(_("Keep OpusCast open while you watch"), "dim"))
        return bar

    def _build_device_popup(self):
        pop = Popup(self)
        pop.setMinimumWidth(480)
        col = QVBoxLayout(pop)
        top = QHBoxLayout()
        title = label(_("Devices on your network"))
        title.setStyleSheet("font-weight: bold;")
        top.addWidget(title, 1)
        self.scan_label = label(role="dim")
        top.addWidget(self.scan_label)
        rescan = QToolButton()
        rescan.setIcon(self._icon("SP_BrowserReload"))
        rescan.setToolTip(_("Scan again"))
        rescan.clicked.connect(lambda: self._scan(force=True))
        top.addWidget(rescan)
        col.addLayout(top)
        self.dev_list = QListWidget()
        self.dev_list.setMinimumHeight(300)
        col.addWidget(self.dev_list, 1)
        col.addWidget(label(_("Pick a device marked “Can play video”. Routers, phones and speakers can't show videos."),
                            "dim", wrap=True))
        pop.on_show = lambda: (self._refresh_device_rows(), self._scan())
        return pop

    def _build_desktop_popup(self):
        pop = Popup(self)
        grid = QGridLayout(pop)
        grid.setContentsMargins(14, 14, 14, 14)
        grid.setHorizontalSpacing(12)
        grid.setVerticalSpacing(10)

        def combo(key, choices, default):
            c = QComboBox()
            for value, text in choices:
                c.addItem(text, value)
            want = self.cfg.get(key, default)
            c.setCurrentIndex(max(0, c.findData(want)))
            c.currentIndexChanged.connect(lambda _i, w=c: self._desk_set(key, w.currentData()))
            return c
        self.desk_screen = QComboBox()
        self.desk_screen.activated.connect(lambda _i: self.desk_screen.currentData() not in (None, "ask")
                                           and self._desk_set("desk_screen", self.desk_screen.currentData()))
        rows = [(_("Screen"), self.desk_screen),
                (_("Picture"), combo("desk_height", [(1080, _("1080p - sharpest")),
                                                    (720, _("720p - lighter on the PC and Wi-Fi"))], 1080)),
                (_("Smoothness"), combo("desk_fps", [(30, _("30 pictures a second")),
                                                   (60, _("60 pictures a second - needs a fast PC"))], 30))]
        sound = QCheckBox()
        sound.setChecked(bool(self.cfg.get("desk_sound", True)))
        sound.toggled.connect(lambda on: self._desk_set("desk_sound", on))
        rows.append((_("Sound from this PC"), sound))
        for i, (text, widget) in enumerate(rows):
            grid.addWidget(label(text), i, 0)
            grid.addWidget(widget, i, 1)
        note = _("The TV shows the screen a second or two late - fine for slides, photos and videos, not for games.")
        if sys.platform == "darwin":
            note += "\n\n" + _("macOS doesn't hand the Mac's sound to apps by itself. With BlackHole (free) "
                               "installed and set as the output, the sound goes to the TV too.")
        grid.addWidget(label(note, "dim", wrap=True), len(rows), 0, 1, 2)
        pop.setMaximumWidth(460)
        pop.on_show = self._fill_desk_screens
        return pop

    # ------------------------------------------------------------ messages

    def _set_subtitle(self, text):
        self.subtitle_label.setText(text)

    def show_info(self, text, kind=INFO, actions=(), selectable=None):
        self.clear_info()
        bar = InfoBar(text, kind, list(actions), selectable, self._drop_bar)
        self.info_box.addWidget(bar)
        return bar

    def clear_info(self):
        while self.info_box.count():
            w = self.info_box.takeAt(0).widget()
            if w:
                w.deleteLater()

    def _drop_bar(self, bar):
        if bar is not None and bar.parent() is not None:
            self.info_box.removeWidget(bar)
            bar.setParent(None)
            bar.deleteLater()

    # ------------------------------------------------------------ TV selection & events

    def _find_tv(self):
        self._set_subtitle(_("Looking for your TV…"))
        location = (self.cfg.get("tv") or {}).get("location")

        def work():
            try:
                tv = core.find_tv(location=location)
            except Exception:  # noqa: BLE001
                tv = None
            post(self._found_tv, tv)
        threading.Thread(target=work, daemon=True).start()

    def _found_tv(self, tv):
        if tv:
            self.select_tv(tv)
        elif not self.tv:
            self._set_subtitle(_("No TV found"))
            self.dev_button.setText("📺  " + _("No TV") + "  ▾")
            self.show_info(_("Couldn't find a TV. Make sure it's switched on and on the same network as this PC."),
                           WARNING, [(_("Search again"), self._find_tv), (_("Show devices"), self._show_devices)])

    def select_tv(self, tv):
        if self.tv and tv.location != self.tv.location and self.session:
            self.stop()
        old, self.tv = self.tv, tv
        if old is not None and old.location != tv.location:
            self._unsubscribe_events(old)
        self.poller.tv = tv
        self.poller.wake.set()
        self.poll_errors = 0
        self.dev_button.setText("📺  " + shorten(tv.name, 22) + "  ▾")
        self._set_subtitle(_("Connected to {tv}").format(tv=tv.name))
        self.cfg["tv"] = {"location": tv.location, "name": tv.name}
        self._save()
        self.clear_info()
        self._refresh_device_rows()
        self._subscribe_events(tv)
        self.worker.submit(tv.sink)  # which formats it plays (and whether it's a speaker)
        if self.pending_play:
            (fn, args), self.pending_play = self.pending_play, None
            self.worker.submit(lambda: None, done=lambda _r: fn(*args))

    def _subscribe_events(self, tv):
        self.events_pending = True

        def work():
            sids = {}
            for service in ("AVTransport", "RenderingControl"):
                try:
                    sids[service] = tv.subscribe(service, self.server.event_url(tv.host, service))[0]
                except core.TVError:
                    pass
            return sids
        self.worker.submit(work, done=lambda sids: self._events_ready(tv, sids))

    def _events_ready(self, tv, sids):
        self.events_pending = False
        if tv is self.tv:
            self.event_sids = sids
        else:
            self.worker.submit(lambda: [tv.unsubscribe(s, sid) for s, sid in sids.items()])

    def _unsubscribe_events(self, tv):
        sids, self.event_sids = self.event_sids, {}
        self.worker.submit(lambda: [tv.unsubscribe(s, sid) for s, sid in sids.items()])

    def _renew_events(self):
        tv, sids = self.tv, dict(self.event_sids)
        if tv and sids:
            def work():
                for service, sid in sids.items():
                    try:
                        tv.renew(service, sid)
                    except core.TVError:
                        return False
                return True
            self.worker.submit(work, done=lambda ok: ok or self._subscribe_events(tv))

    def _on_event(self, service, sid, body):
        if sid not in self.event_sids.values():
            return  # left over from an earlier run
        if service == "AVTransport":
            self.poller.wake.set()  # react to the TV remote right away
            return
        changes = core.parse_last_change(body)
        if changes is None:
            return
        now = time.monotonic()
        if "Volume" in changes and changes["Volume"].isdigit() and now - self.vol_touched > 3:
            self.setting_vol = True
            self.volume.setValue(int(changes["Volume"]))
            self.setting_vol = False
        s = self.session
        if "X_EnabledCaptions" in changes:
            self.last_caption_event = now
            if s and s["kind"] == "movie":
                media_ = s["media"]
                shown = {urllib.parse.urlsplit(u).path for u in core.caption_uris(changes["X_EnabledCaptions"])}
                s["sub_on"] = bool(shown & (set(media_["subs"]) - {media_["blank_path"]}))
                by_path = {p: i for i, p in s["paths"].items()}
                shown_id = next((by_path[p] for p in shown if p in by_path), None)
                if shown_id and s["subs"]:
                    s["sub_entry"] = next((e for e in s["subs"] if e["id"] == shown_id), s["sub_entry"])
                s["sub_tv_changed"], s["caption_api"] = False, True
                self._update_sub_button()
        elif not changes:
            # the TV says "something changed" but not what: that's what its own subtitle menu sends
            after(1500, self._maybe_changed_on_tv, now)

    def _maybe_changed_on_tv(self, stamp):
        s = self.session
        last_cmd = max(self.last_caption_cmd, s["media"]["caption_sent"]) if s else 0.0
        if (s and s["kind"] == "movie" and s["seen_playing"] and self.last_caption_event < stamp - 0.5
                and time.monotonic() - last_cmd > 4):
            s["sub_tv_changed"] = True
            self._update_sub_button()

    # ------------------------------------------------------------ devices

    def _show_devices(self):
        self.devices_popup.show_under(self.dev_button)

    def _scan(self, force=False):
        if self.scanning or (not force and self.devices and time.monotonic() - self.scanned_at < 60):
            return
        self.scanning = True
        self.scan_label.setText(_("Scanning your network…"))
        self._refresh_device_rows()

        def work():
            try:
                devices = core.scan_network()
            except Exception:  # noqa: BLE001
                devices = []
            post(self._scan_done, devices)
        threading.Thread(target=work, daemon=True).start()

    def _scan_done(self, devices):
        self.scanning = False
        self.scan_label.setText("")
        self.devices, self.scanned_at = devices, time.monotonic()
        self._refresh_device_rows()

    def _refresh_device_rows(self):
        self.dev_list.clear()
        if not self.devices:
            item = QListWidgetItem(_("Scanning your network…") if self.scanning else
                                   _("Open this list to scan your network."))
            item.setFlags(Qt.NoItemFlags)
            self.dev_list.addItem(item)
        for d in self.devices:
            row = QWidget()
            hb = QHBoxLayout(row)
            hb.setContentsMargins(8, 6, 8, 6)
            icon = label(DEVICE_ICONS[d["kind"]])
            icon.setStyleSheet("font-size: 24px;" + ("" if d["can_play"] else "color: gray;"))
            hb.addWidget(icon)
            vb = QVBoxLayout()
            name = label(d["name"])
            name.setStyleSheet("font-weight: bold;")
            vb.addWidget(name)
            vb.addWidget(label(" · ".join(x for x in (d["ip"], d["detail"]) if x), "dim"))
            if d["kind"] == "tv":
                note, role = "✓ " + _("Can play video"), "ok"
            elif d["kind"] == "speaker":
                note, role = "♪ " + d["note"], "audio"
            elif d["kind"] == "pc":
                note, role = d["note"], "dim"
            else:
                note, role = "✗ " + d["note"], "dim"
            vb.addWidget(label(note, role, wrap=True))
            hb.addLayout(vb, 1)
            if self.tv and d["tv"] and d["ip"] == self.tv.host:
                hb.addWidget(label("✓ " + _("In use")))
            elif d["can_play"]:
                use = QPushButton(_("Use this speaker") if d["kind"] == "speaker" else _("Use this TV"))
                use.clicked.connect(lambda _c=False, dev=d: self._use_device(dev))
                hb.addWidget(use)
            item = QListWidgetItem()
            item.setSizeHint(row.sizeHint())
            item.setFlags(Qt.ItemIsEnabled)
            self.dev_list.addItem(item)
            self.dev_list.setItemWidget(item, row)

    def _use_device(self, device):
        self.devices_popup.hide()
        self.select_tv(device["tv"])

    # ------------------------------------------------------------ recent list

    def _recent_item(self, path):
        return next((r for r in self.cfg["recent"] if r.get("path") == path), None)

    def _remember(self, path, pos=None, dur=None, watched=None, sub=KEEP):
        old = self._recent_item(path) or {}
        item = {"path": path, "pos": old.get("pos", 0), "dur": old.get("dur", 0),
                "watched": old.get("watched", False), "sub": old.get("sub")}
        if pos is not None:
            item["pos"] = round(pos, 1)
            if pos > 5:
                item["watched"] = False
        if dur:
            item["dur"] = dur
        if watched is not None:
            item["watched"] = watched
        if sub is not KEEP:
            item["sub"] = sub
        self.cfg["recent"] = [item] + [r for r in self.cfg["recent"] if r.get("path") != path][:19]
        self._save()

    def _forget(self, path):
        self.cfg["recent"] = [r for r in self.cfg["recent"] if r.get("path") != path]
        self._save()
        self._refresh_recent()

    def _refresh_recent(self):
        self.recent_list.clear()
        home = os.path.expanduser("~")
        shown = 0
        for item in self.cfg["recent"]:
            path = item.get("path", "")
            if not os.path.isfile(path):
                continue
            shown += 1
            pos, dur, watched = item.get("pos", 0), item.get("dur", 0), item.get("watched")
            row = QWidget()
            hb = QHBoxLayout(row)
            hb.setContentsMargins(8, 6, 8, 6)
            hb.addWidget(label("🎬"))
            vb = QVBoxLayout()
            vb.setSpacing(2)
            title = label(os.path.splitext(os.path.basename(path))[0])
            title.setStyleSheet("font-weight: bold;")
            vb.addWidget(title)
            if watched:
                status = _("Watched")
            elif pos > 5 and dur:
                status = _("Resume at {pos} of {dur}").format(pos=fmt(pos), dur=fmt(dur))
            elif pos > 5:
                status = _("Resume at {pos}").format(pos=fmt(pos))
            else:
                status = _("Not started")
            folder = os.path.dirname(path)
            if folder.startswith(home):
                folder = "~" + folder[len(home):]
            vb.addWidget(label(f"{status}  ·  {folder}", "dim"))
            if pos > 5 and dur and not watched:
                bar = QProgressBar()
                bar.setRange(0, 1000)
                bar.setValue(int(min(1.0, pos / dur) * 1000))
                bar.setTextVisible(False)
                bar.setMaximumHeight(6)
                vb.addWidget(bar)
            hb.addLayout(vb, 1)
            if pos > 5 and not watched:
                again = QToolButton()
                again.setIcon(self._icon("SP_MediaSkipBackward"))
                again.setToolTip(_("Play from the beginning"))
                again.setAutoRaise(True)
                again.clicked.connect(lambda _c=False, p=path: self.play_file(p, start_at=0))
                hb.addWidget(again)
            remove = QToolButton(text="✕")
            remove.setToolTip(_("Remove from this list"))
            remove.setAutoRaise(True)
            remove.clicked.connect(lambda _c=False, p=path: self._forget(p))
            hb.addWidget(remove)
            it = QListWidgetItem()
            it.setData(Qt.UserRole, path)
            it.setSizeHint(row.sizeHint())
            self.recent_list.addItem(it)
            self.recent_list.setItemWidget(it, row)
        self.recent_list.setVisible(shown > 0)
        self.recent_empty.setVisible(shown == 0)

    # ------------------------------------------------------------ choosing what to play

    def choose_file(self):
        exts = " ".join(f"*{e} *{e.upper()}" for e in core.VIDEO_EXTS + core.AUDIO_EXTS + core.IMAGE_EXTS)
        filters = ";;".join([
            f"{_('Videos, music and photos')} ({exts})",
            f"{_('Videos')} ({' '.join('*' + e for e in core.VIDEO_EXTS)})",
            f"{_('Music')} ({' '.join('*' + e for e in core.AUDIO_EXTS)})",
            f"{_('Photos')} ({' '.join('*' + e for e in core.IMAGE_EXTS)})",
            f"{_('All files')} (*)"])
        start = self.cfg.get("last_dir") or os.path.expanduser("~")
        paths, _sel = QFileDialog.getOpenFileNames(self, _("Choose what to play on the TV"), start, filters)
        if paths:
            self.open_paths(paths)

    def _set_drop_hover(self, on):
        self.dropzone.setProperty("hover", "true" if on else "false")
        self.dropzone.style().unpolish(self.dropzone)
        self.dropzone.style().polish(self.dropzone)

    def dragEnterEvent(self, event):  # noqa: N802 - Qt's name
        if event.mimeData().hasUrls():
            event.acceptProposedAction()
            self._set_drop_hover(True)

    def dragLeaveEvent(self, event):  # noqa: N802
        self._set_drop_hover(False)

    def dropEvent(self, event):  # noqa: N802
        self._set_drop_hover(False)
        paths = [u.toLocalFile() for u in event.mimeData().urls() if u.isLocalFile()]
        event.acceptProposedAction()
        subs = [p for p in paths if p.lower().endswith(core.SUB_EXTS)]
        if subs and self.session and self.session["kind"] == "movie" and len(subs) == len(paths):
            self._use_sub_file(subs[0])  # a subtitle file dropped while a video plays
            return
        self.open_paths([p for p in paths if not p.lower().endswith(core.SUB_EXTS)])

    def open_paths(self, paths):
        """Play what was chosen: the (first) video, the songs one after another, or the photos as a
        slideshow - one photo brings the rest of its folder along, to flip through."""
        kind, files = media.collect(paths)
        if kind is None:
            self.show_info(_("That doesn't look like a video, song or photo on this PC."), WARNING)
            return
        self.cfg["last_dir"] = os.path.dirname(files[0])
        if kind == "video":
            self.play_file(files[0])
        elif kind == "audio":
            self.play_music(files, 0)
        elif len(files) == 1:
            folder = media.folder_items(files[0], "image")
            self.show_photos(folder, folder.index(files[0]) if files[0] in folder else 0)
        else:
            self.show_photos(files, 0)

    def _ready_for(self, what, fn, args):
        """True if the TV is there and can do this; otherwise say why (or wait for the TV)."""
        if not self.tv:
            self.pending_play = (fn, args)
            self.show_info(_("Waiting for the TV - it starts as soon as the TV is found."))
            return False
        if what != "audio" and self._is_speaker():
            self.show_info(_("{device} plays music only - choose a TV for videos, photos and the desktop.").format(
                device=self.tv.name), WARNING, [(_("Show devices"), self._show_devices)])
            return False
        return True

    def _is_speaker(self):
        sink = self.tv.cached_sink() if self.tv else None
        return bool(sink) and "*" not in sink and not any(m.startswith("video/") for m in sink)

    # ------------------------------------------------------------ videos

    def play_file(self, path, start_at=None):
        path = os.path.abspath(path)
        if not os.path.isfile(path):
            self.show_info(_("File not found: {path}").format(path=path), WARNING)
            return
        if core.media_kind(path) in ("audio", "image"):
            self.open_paths([path])
            return
        if not self._ready_for("video", self.play_file, (path, start_at)):
            return
        self.cfg["last_dir"] = os.path.dirname(path)
        item = self._recent_item(path)
        if start_at is None:
            start_at = item["pos"] if item and not item.get("watched") and item.get("pos", 0) > 5 else 0
        sub_id = item.get("sub") if item else None
        if self.session:
            self._end_session(stop_tv=False)
        self.clear_info()
        self._remember(path, pos=start_at)
        self.gen += 1
        gen = self.gen
        if not sub_id:
            self._begin(gen, path, start_at, None, None)
            return
        self._show_player(os.path.splitext(os.path.basename(path))[0], _("Preparing subtitles…"))

        def work():  # the subtitle used last time, so it's on from the start
            entries = core.list_subtitles(path)
            entry = next((e for e in entries if e["id"] == sub_id and e["ok"]), None)
            if entry is None and sub_id.startswith("file:") and os.path.isfile(sub_id[5:]):
                entry = core.subtitle_file_entry(sub_id[5:])
                entries.append(entry)
            data = None
            if entry:
                try:
                    data = core.prepare_subtitle(entry, path)
                except core.TVError:
                    entry = None
            post(self._begin, gen, path, start_at, (entry, data) if entry else None, entries)
        threading.Thread(target=work, daemon=True).start()

    def _begin(self, gen, path, start_at, subtitle, subs):
        if gen != self.gen:
            return
        if self.intro_switch.isChecked():
            ok, reason = intro.available()
            if ok:
                self._start_intro(path, start_at, subtitle, subs)
                return
            self.show_info(_("Skipping the Skynet intro: it {reason}.").format(reason=reason), WARNING)
        self._start_session("movie", path, start_at, subtitle=subtitle, subs_on=bool(subtitle), subs=subs)

    def _start_intro(self, path, start_at, subtitle, subs):
        self.gen += 1
        gen = self.gen
        self._set_mode("intro")
        self._show_player(_("Skynet is booting…"), _("Preparing the intro…"))

        def work():
            try:
                clip = intro.ensure_intro()
            except Exception as e:  # noqa: BLE001
                post(self._intro_failed, gen, path, start_at, subtitle, subs, str(e))
                return
            post(self._intro_ready, gen, clip, (path, start_at, subtitle, subs))
        threading.Thread(target=work, daemon=True).start()

    def _intro_ready(self, gen, clip, next_up):
        if gen == self.gen:
            self._start_session("intro", clip, 0, next_up=next_up)

    def _intro_failed(self, gen, path, start_at, subtitle, subs, message):
        if gen == self.gen:
            self.show_info(_("The Skynet intro failed ({error}) - playing the video straight away.").format(
                error=message), WARNING)
            self._start_session("movie", path, start_at, subtitle=subtitle, subs_on=bool(subtitle), subs=subs)

    def _start_session(self, kind, path, start_at, next_up=None, subtitle=None, subs_on=False, subs=None,
                       media=None, title=None, **extra):
        """Start something on the TV. kind: movie, intro, music (one song of a queue), photo (one photo
        of a slideshow) or desktop; media is already published for all but videos."""
        self.gen += 1
        entry, data = subtitle if subtitle else (None, None)
        if media is None:
            try:
                media = self.server.publish(path, data)
            except OSError as e:
                self.show_info(_("Can't read that file: {error}").format(error=e), ERROR)
                self.stack.setCurrentIndex(0)
                return
        now = time.monotonic()
        self.session = {"kind": kind, "path": path, "media": media, "next": next_up, "seen_playing": False,
                        "started": now, "gen": self.gen, "subs": subs, "sub_entry": entry, "sub_on": False,
                        "sub_tv_changed": False, "caption_api": None, "sub_loading": bool(entry and subs_on),
                        "paths": {entry["id"]: media["sub_path"]} if entry else {},
                        "start_at": start_at, "subs_on": subs_on,
                        # starting it failed, but the TV may still play it: a Samsung asks "Allow this
                        # device?" the first time, holds the commands meanwhile and carries them out once allowed
                        "start_error": None, "start_failed": 0.0, "answering_since": 0.0, "retried": False,
                        "wait_bar": None, "queue": None, "index": 0, "cast": None, "tags": {}}
        self.session.update(extra)
        # the URLs of this song/photo and the one before: while switching, the TV may still report the old one
        self.session["ours"] = (list(extra.get("ours") or []) + [media["url_path"]])[-3:]
        self.poller.active = True
        self.tv_state, self.tv_pos, self.tv_pos_raw, self.pos_seen, self.tv_dur = "TRANSITIONING", float(start_at), None, now, 0.0
        self.hold = (float(start_at), now, 30.0) if start_at else None
        self.range_dur = None
        self._set_mode(kind)
        self._show_player(title or (_("Skynet is booting…") if kind == "intro" else media["title"]),
                          _("Starting on the TV…"))
        if self.preview_switch.isChecked() and kind in ("movie", "intro"):
            self._preview_load()
        gen = self.gen
        if subs_on:
            self.last_caption_cmd = now
        if not self.event_sids and not self.events_pending:  # e.g. the TV didn't answer until it was allowed
            self._subscribe_events(self.tv)
        self._start_on_tv(self.session)
        after(15000, self._check_fetched, gen)
        if kind == "movie" and subs is None:
            def find():
                found = core.list_subtitles(path)
                post(self._subs_found, gen, found)
            threading.Thread(target=find, daemon=True).start()
        self._update_sub_button()
        if kind == "movie" and start_at > 5:
            self.show_info(_("Carrying on from {pos}.").format(pos=fmt(start_at)), INFO,
                           [(_("Start from the beginning"), lambda: self.seek_to(0))])

    def _start_on_tv(self, s):
        """Hand the session's media to the TV (stop, set, play, resume point, subtitle)."""
        tv, gen = self.tv, s["gen"]

        def work():
            try:
                return core.play_media(tv, self.server, s["media"], s["start_at"], subs_on=s["subs_on"]), None
            except Exception as e:  # noqa: BLE001 - shown to the user
                return None, e
        self.worker.submit(work, done=lambda r: self._play_started(gen, *r))

    def _play_started(self, gen, captions, error=None):
        self.poller.wake.set()
        s = self.session
        if not (s and s["gen"] == gen):
            return
        if error is not None:
            self._start_failed(s, error)
            return
        s["start_error"] = None  # (a retry worked)
        if not s["sub_entry"]:
            return
        s["sub_loading"] = False
        self.last_caption_cmd = time.monotonic()
        if captions == "ok":
            s["sub_on"], s["caption_api"] = True, True
        elif captions == "unsupported":
            s["sub_on"], s["caption_api"] = True, False  # sent with the video; the TV decides
        self._update_sub_button()

    def _start_failed(self, s, error):
        """The start commands failed. Keep the session: the TV may be waiting for "Allow" and play it then."""
        s["sub_loading"] = False
        if s["seen_playing"]:  # it plays anyway
            self.show_info(_("The TV didn't accept that: {error}").format(error=error), WARNING)
            self._update_sub_button()
            self._update_status()
            return
        s["start_error"], s["start_failed"], s["answering_since"] = error, time.monotonic(), 0.0
        text = _("The TV hasn't taken the video yet ({error}). If the TV asks whether to allow this PC, "
                 "choose Allow - the video then starts by itself.") if s["kind"] in ("movie", "intro") else \
            _("The TV hasn't taken it yet ({error}). If the TV asks whether to allow this PC, choose Allow - "
              "it then starts by itself.")
        s["wait_bar"] = self.show_info(text.format(error=error), WARNING,
                                       [(_("Try again"), self._retry_start), (_("Stop"), self.stop)])
        after(120_000, self._give_up, s["gen"], s["start_failed"])
        self._update_sub_button()
        self._update_status()

    def _retry_start(self):
        """Send the start commands again, in the same session (same address for the TV)."""
        s = self.session
        if not s or s["seen_playing"]:
            return
        s["retried"] = True
        self._drop_bar(s["wait_bar"])
        s["sub_loading"] = bool(s["sub_entry"] and s["subs_on"])
        if not self.event_sids and not self.events_pending and self.tv:
            self._subscribe_events(self.tv)
        self._start_on_tv(s)
        self._update_sub_button()
        self._update_status()

    def _give_up(self, gen, stamp):
        s = self.session
        if (s and s["gen"] == gen and not s["seen_playing"] and s["start_error"] is not None
                and s["start_failed"] == stamp):
            error = s["start_error"]
            self._end_session(stop_tv=False)
            self.show_info(_("Couldn't start it on the TV: {error}").format(error=error), ERROR)

    def _recovered(self, s):
        """The TV plays it after all (allowed on the TV): carry on as if the start had worked."""
        s["start_error"] = None
        self._drop_bar(s["wait_bar"])
        tv = self.tv
        if s["start_at"] > 1:  # the resume point was never sent
            self.hold = (float(s["start_at"]), time.monotonic(), 30.0)
            self.worker.submit(tv.seek, s["start_at"], done=lambda _r: self.poller.wake.set())
        if s["kind"] == "movie" and s["sub_entry"] and s["subs_on"] and not s["sub_on"]:
            self.last_caption_cmd = time.monotonic()

            def done(result, sess=s):
                if sess is self.session:
                    sess["caption_api"] = result == "ok"
                    sess["sub_on"] = True  # announced with the video either way
                    self._update_sub_button()
            self.worker.submit(core.show_subtitle, tv, self.server, s["media"], s["media"]["sub_path"], done=done)

    def _subs_found(self, gen, found):
        s = self.session
        if s and s["gen"] == gen and s["subs"] is None:
            s["subs"] = found
            self._update_sub_button()

    def _show_player(self, title, status):
        self.title_label.setText(title)
        self.status_label.setText(status)
        self.placeholder_label.setText(_("Playing on {tv}").format(tv=self.tv.name) if self.tv else "")
        self.elapsed.setText("0:00:00")
        self.total.setText("--:--")
        self.stack.setCurrentIndex(1)
        self._update_video_area()

    def _check_fetched(self, gen):
        s = self.session
        if s and s["gen"] == gen and not s["media"]["requested"].is_set() and s["start_error"] is None:
            self._firewall_help()

    def _firewall_help(self):
        host, port = self.tv.host, self.server.port
        if sys.platform == "darwin":
            self.show_info(_("The TV hasn't started loading it. macOS's firewall may be blocking it: in System "
                             "Settings → Network → Firewall → Options, let OpusCast accept incoming connections.")
                           if desk.mac_major() >= 13 else
                           _("The TV hasn't started loading it. macOS's firewall may be blocking it: in System "
                             "Preferences → Security & Privacy → Firewall → Firewall Options, let OpusCast accept "
                             "incoming connections."), WARNING)
            return
        if sys.platform == "win32":
            rule = (f'advfirewall firewall add rule name="OpusCast" dir=in action=allow protocol=TCP '
                    f'localport={port} remoteip={host}')
            fix = ["powershell", "-NoProfile", "-Command",
                   f"Start-Process netsh -ArgumentList '{rule}' -Verb RunAs -Wait"]
            self.show_info(_("The TV hasn't started loading it. A firewall on this PC may be blocking it. "
                             "This lets the TV in:"), WARNING,
                           [(_("Allow it (asks for your password)"), lambda: self._run_admin(fix))],
                           selectable="netsh " + rule)
            return
        which = shutil.which
        rule = f"rule family=ipv4 source address={host} port port={port} protocol=tcp accept"
        commands, fixes = [], []
        if which("ufw"):
            commands.append(f"sudo ufw allow from {host} to any port {port} proto tcp")
            fixes.append(["pkexec", which("ufw"), "allow", "from", host, "to", "any", "port", str(port), "proto", "tcp"])
        if which("firewall-cmd"):
            commands.append(f"sudo firewall-cmd --permanent --add-rich-rule='{rule}' && sudo firewall-cmd --reload")
        if not commands:
            commands = [f"sudo ufw allow from {host} to any port {port} proto tcp"]
        actions = [(_("Allow it (asks for your password)"), lambda: self._run_admin(fixes[0]))] \
            if fixes and which("pkexec") else []
        self.show_info(_("The TV hasn't started loading it. A firewall on this PC may be blocking it. "
                         "This lets the TV in:"), WARNING, actions, selectable="\n".join(commands))

    def _run_admin(self, cmd):
        def work():
            try:
                ok = subprocess.run(cmd, capture_output=True, timeout=180).returncode == 0
            except (OSError, subprocess.SubprocessError):
                ok = False
            post(self._admin_done, ok)
        threading.Thread(target=work, daemon=True).start()

    def _admin_done(self, ok):
        s = self.session
        if ok and s:
            self.show_info(_("Firewall updated - starting again."))
            self._restart_current(s)
        elif ok:
            self.show_info(_("Firewall updated."))
        else:
            self.show_info(_("The firewall wasn't changed."), WARNING)

    def est_pos(self):
        if self.hold:
            return self.hold[0]
        p = self.tv_pos
        if self.tv_state == "PLAYING":
            p += min(time.monotonic() - self.pos_seen, 1.0)
        return min(p, self.tv_dur) if self.tv_dur else p

    def _on_poll(self, r):
        if r["tv"] is not self.tv:
            return
        if "error" in r:
            self.poll_errors += 1
            if self.session:
                self.session["answering_since"] = 0.0
            if self.poll_errors >= 3:
                self._set_subtitle(_("Can't reach {tv} - is it on?").format(tv=self.tv.name))
            return
        if self.poll_errors >= 3:
            self._set_subtitle(_("Connected to {tv}").format(tv=self.tv.name))
        self.poll_errors = 0
        if r.get("volume") is not None and time.monotonic() - self.vol_touched > 3:
            self.setting_vol = True
            self.volume.setValue(int(r["volume"]))
            self.setting_vol = False
        s = self.session
        if not s:
            return
        state, pos, dur, uri = r["state"], r["pos"], r["dur"], r["uri"]
        current = not uri or s["media"]["url_path"] in uri
        ours = current or any(p in uri for p in s["ours"])
        now = time.monotonic()
        was_seen = s["seen_playing"]
        photo = s["kind"] == "photo"
        if current:
            # a photo counts as shown once the TV fetched it: some TVs report STOPPED while showing one
            if state == "PLAYING" or (photo and s["media"]["requested"].is_set() and state != "TRANSITIONING"):
                s["seen_playing"] = True
            if s["seen_playing"] and state in ("PLAYING", "PAUSED_PLAYBACK", "TRANSITIONING"):
                if pos is not None:
                    if pos != self.tv_pos_raw:
                        self.tv_pos_raw, self.pos_seen = pos, now
                    self.tv_pos = float(pos)
                if dur:
                    self.tv_dur = float(dur)
                self.tv_state = state
        if self.hold:
            target, since, limit = self.hold
            arrived = current and pos is not None and s["seen_playing"] and target - 2 <= pos <= target + 8
            if arrived or now - since > limit:
                self.hold = None
        if photo:
            finished = s["seen_playing"] and bool(uri) and not ours  # something else is on the TV now
        else:
            finished = s["seen_playing"] and (state in ("STOPPED", "NO_MEDIA_PRESENT") or not ours)
        if finished:
            # a song that stopped at its end: the next one (stopped half-way: the TV's Stop button)
            length = self.tv_dur or s["tags"].get("duration") or 0
            s["ended"] = ours and (not length or self.tv_pos >= length - 5)
            self._session_finished()
            return
        if not was_seen and s["seen_playing"] and s["start_error"] is not None:
            self._recovered(s)
        if not s["seen_playing"] and s["start_error"] is not None:
            # the TV answers again (allowed?): if it doesn't start it by itself, send it once more
            if not s["answering_since"]:
                s["answering_since"] = now
            if not s["retried"] and now - s["answering_since"] >= 4:
                self._retry_start()
        if (not s["seen_playing"] and s["start_error"] is None and now - s["started"] > 40 and not photo
                and state in ("STOPPED", "NO_MEDIA_PRESENT")):
            kind = s["kind"]
            self._end_session(stop_tv=False)
            self.show_info({"music": _("The TV didn't start the song. It may not play this format."),
                            "desktop": _("The TV didn't start showing the desktop.")}.get(kind, _(
                                "The TV didn't start the video. It may not support this file's format "
                                "(Dolby Vision files often don't work on Samsung TVs).")), ERROR)
            return
        self._update_status()
        self._sync_preview()

    def _update_status(self):
        s = self.session
        if not s:
            return
        st = self.tv_state if s["seen_playing"] else "STARTING"
        text = {"PLAYING": _("Playing"), "PAUSED_PLAYBACK": _("Paused"), "TRANSITIONING": _("Loading…")}.get(
            st, _("Starting on the TV…"))
        if s["kind"] == "intro" and st == "PLAYING":
            text = _("Skynet is coming online")
        if not s["seen_playing"] and s["start_error"] is not None:
            text = _("Waiting for the TV…")
        if s.get("sub_loading"):
            text = _("Loading subtitles…")
        if s["kind"] == "photo" and s["start_error"] is None:
            text = _("On the TV") if s["seen_playing"] else _("Sending the photo…")
        line = f"{text} - {self.tv.name}" if self.tv else text
        if s["kind"] == "music":
            line = " · ".join(x for x in (media.song_line(s["tags"]), line) if x)
        elif s["kind"] == "photo":
            line = f"{s['index'] + 1} / {len(s['queue'])}  ·  {line}"
        elif s["kind"] == "desktop" and s["cast"]:
            line = f"{s['cast'].describe()}  ·  {line}"
        self.status_label.setText(line)
        icon = "pause" if st == "PLAYING" else "play"
        if icon != self.play_icon:
            self.play_icon = icon
            for b in self.play_buttons:
                b.setText("⏸" if icon == "pause" else "▶")

    def _tick(self):
        s = self.session
        if s and not self.dragging and s["kind"] not in ("photo", "desktop"):
            pos = self.est_pos()
            if self.tv_dur and self.tv_dur != self.range_dur:
                self.range_dur = self.tv_dur
                self.slider.setRange(0, int(self.tv_dur * 10))
                self.total.setText(fmt(self.tv_dur))
            self.slider.setEnabled(bool(self.tv_dur))
            self.slider.setValue(int(pos * 10) if self.tv_dur else 0)
            self.elapsed.setText(fmt(pos))
        now = time.monotonic()
        if s and s["kind"] == "movie" and s["seen_playing"] and now - self.last_save > 15:
            self.last_save = now
            self._remember(s["path"], pos=self.est_pos(), dur=self.tv_dur)

    def _session_finished(self):
        s = self.session
        if s["kind"] == "intro" and s["next"]:
            path, start_at, subtitle, subs = s["next"]
            self.session = None
            self._start_session("movie", path, start_at, subtitle=subtitle, subs_on=bool(subtitle), subs=subs)
        elif s["kind"] == "music" and s.get("ended") and s["index"] + 1 < len(s["queue"]):
            self._go_track(s["queue"], s["index"] + 1)
        else:
            self._end_session(stop_tv=False)

    def _end_session(self, stop_tv=True):
        s, self.session = self.session, None
        if not s:
            return
        self.poller.active = False
        self.gen += 1
        if s["kind"] == "movie" and s["seen_playing"]:
            pos, dur = self.est_pos(), self.tv_dur
            watched = bool(dur) and (pos >= dur - 90 or pos >= dur * 0.95)
            self._remember(s["path"], pos=0 if watched else pos, dur=dur, watched=watched)
        if stop_tv and self.tv:
            self.worker.submit(self.tv.stop)
        if s["cast"]:
            threading.Thread(target=s["cast"].stop, daemon=True).start()
        self._cancel_slide()
        self.preview.unload()
        self.hold = None
        self.skip_intro_btn.hide()
        self.sub_button.hide()
        self.tracks_button.hide()
        self._set_art(None)
        self.stack.setCurrentIndex(0)
        self._update_video_area()
        self._refresh_recent()

    def toggle_play(self):
        if not (self.session and self.tv):
            return
        if self.session["kind"] == "photo":
            self.toggle_slideshow()
            return
        if self.session["kind"] == "desktop":
            return  # a live picture: pausing would only make it fall behind
        if self.tv_state == "PLAYING":
            self.tv_pos, self.tv_state = self.est_pos(), "PAUSED_PLAYBACK"
            self.worker.submit(self.tv.pause, done=lambda _r: self.poller.wake.set())
        else:
            self.tv_state, self.pos_seen = "PLAYING", time.monotonic()
            self.worker.submit(self.tv.play, done=lambda _r: self.poller.wake.set())
        self._update_status()
        self._sync_preview()

    def seek_to(self, target):
        if not (self.session and self.tv):
            return
        dur = self.tv_dur
        target = max(0.0, min(target, dur - 2) if dur else target)
        self.hold = (target, time.monotonic(), 8.0)
        self.worker.submit(self.tv.seek, target, done=lambda _r: self.poller.wake.set())
        if self.preview.path:
            self.preview.seek(target)
        self._tick()

    def skip(self, delta):
        self.seek_to(self.est_pos() + delta)

    def skip_intro(self):
        if self.session and self.session["kind"] == "intro":
            self._session_finished()

    def stop(self):
        if not self.session:  # still preparing (subtitles / intro / a song / the desktop): just go back
            self.gen += 1
            if self.pending_cast:
                cast, self.pending_cast = self.pending_cast, None
                threading.Thread(target=cast.stop, daemon=True).start()
            self.stack.setCurrentIndex(0)
            return
        self._end_session(stop_tv=True)

    # ------------------------------------------------------------ music, photos, the desktop

    def _set_mode(self, kind):
        """Show the controls for what's on: a video (or the intro), songs, photos or the desktop."""
        self.mode = kind
        page = {"music": "music", "photo": "photo", "desktop": "live"}.get(kind, "video")
        self.transport.setCurrentIndex(self.transport_pages[page])
        self.slider_row.setVisible(kind not in ("photo", "desktop"))
        self.skip_intro_btn.setVisible(kind == "intro")
        self.sub_button.setVisible(kind == "movie")
        self.tracks_button.setVisible(kind == "music")
        self.volume_box.setVisible(kind != "photo")
        self.placeholder_icon.setText(MODE_ICONS.get(kind, "📺"))
        if kind not in ("music", "photo"):
            self._set_art(None)
        self._update_slide_button()

    def _set_art(self, image):
        if image is None:
            self.art.clear()
        else:
            self.art.setPixmap(QPixmap.fromImage(image))
        self.art.setVisible(image is not None)
        self.placeholder_icon.setVisible(image is None)

    def step(self, delta):
        """Next / previous photo or song."""
        s = self.session
        if not s or not s["queue"]:
            return
        n = len(s["queue"])
        if s["kind"] == "photo" and n > 1:
            self._go_photo(s["queue"], (s["index"] + delta) % n)
        elif s["kind"] == "music":
            if delta < 0 and self.est_pos() > 5:
                self.seek_to(0)  # like music players: "previous" first goes back to the start of the song
                return
            i = s["index"] + delta
            if 0 <= i < n:
                self._go_track(s["queue"], i)

    def _restart_current(self, s):
        if s["kind"] == "music":
            self._go_track(s["queue"], s["index"])
        elif s["kind"] == "photo":
            self._go_photo(s["queue"], s["index"])
        elif s["kind"] == "desktop":
            self._end_session(stop_tv=False)
            self.cast_desktop()
        elif s["kind"] == "intro" and s["next"]:
            self.play_file(s["next"][0], s["next"][1])
        else:
            self.play_file(s["path"], self.est_pos())

    # music

    def play_music(self, files, index=0):
        if not files or not self._ready_for("audio", self.play_music, (files, index)):
            return
        if self.session:
            self._end_session(stop_tv=False)
        self.clear_info()
        self._go_track(files, index)

    def _go_track(self, files, index):
        path, tv = files[index], self.tv
        self.gen += 1
        gen = self.gen
        if not (self.session and self.session["kind"] == "music"):
            self._set_mode("music")
        self._show_player(os.path.splitext(os.path.basename(path))[0], _("Starting on the TV…"))

        def work():
            tags = media.audio_tags(path)
            cover = media.song_cover(path, tags)
            art = pixmap_from(data=cover[1], box=(280, 280)) if cover else None
            play, mime = path, None
            if media.needs_mp3(tv, core.mime_of(path, "audio")):  # the TV doesn't list this format
                post(self._status_if, gen, _("Converting the song for the TV…"))
                try:
                    play, mime = media.to_mp3(path), "audio/mpeg"
                except (core.TVError, OSError) as e:
                    post(self._track_failed, gen, files, index, str(e))
                    return
            post(self._track_ready, gen, files, index, play, mime, tags, cover, art)
        threading.Thread(target=work, daemon=True).start()

    def _status_if(self, gen, text):
        if gen == self.gen:
            self.status_label.setText(text)

    def _track_ready(self, gen, files, index, play, mime, tags, cover, art):
        if gen != self.gen:
            return
        prev = self.session
        meta = {k: tags[k] for k in ("artist", "albumartist", "album", "track", "duration") if tags.get(k)}
        try:
            m = self.server.publish(play, kind="audio", mime=mime, title=tags.get("title"), meta=meta)
        except OSError as e:
            self._track_failed(gen, files, index, str(e))
            return
        if cover:
            m["meta"]["cover_path"], m["meta"]["cover_mime"] = self.server.add_extra(m, *cover), cover[0]
        ours = prev["ours"] if prev and prev["kind"] == "music" else []
        self._start_session("music", files[index], 0, media=m, title=tags.get("title"), queue=files, index=index,
                            tags=tags, ours=ours)
        self._set_art(art)
        self.tracks_button.setText(f"☰  {index + 1} / {len(files)}")
        self._update_status()
        if index + 1 < len(files):  # have the next song ready (converted, if this TV needs that)
            nxt, tv = files[index + 1], self.tv

            def prepare_next():
                if media.needs_mp3(tv, core.mime_of(nxt, "audio")):
                    media.to_mp3(nxt)
            threading.Thread(target=self._quietly, args=(prepare_next,), daemon=True).start()

    def _track_failed(self, gen, files, index, error):
        if gen != self.gen:
            return
        self.show_info(_("Can't play {name}: {error}").format(name=os.path.basename(files[index]), error=error),
                       WARNING)
        if index + 1 < len(files):
            self._go_track(files, index + 1)
        elif self.session:
            self._end_session(stop_tv=False)
        else:
            self.stack.setCurrentIndex(0)

    def _fill_tracks(self):
        self.tracks_menu.clear()
        s = self.session
        if not s or not s["queue"]:
            return
        for i, path in enumerate(s["queue"]):
            name = os.path.splitext(os.path.basename(path))[0]
            act = self.tracks_menu.addAction(("▶  " if i == s["index"] else f"{i + 1}.  ") + shorten(name, 70))
            if i == s["index"]:
                f = act.font()
                f.setBold(True)
                act.setFont(f)
            act.triggered.connect(lambda _c=False, n=i: self._pick_track(n))

    def _pick_track(self, index):
        s = self.session
        if s and s["kind"] == "music" and s["queue"]:
            self._go_track(s["queue"], index)

    # photos

    def show_photos(self, files, index=0):
        if not files or not self._ready_for("image", self.show_photos, (files, index)):
            return
        if self.session:
            self._end_session(stop_tv=False)
        self.clear_info()
        self._go_photo(files, index)

    def _go_photo(self, files, index):
        path, tv = files[index], self.tv
        self.gen += 1
        gen = self.gen
        self._cancel_slide()
        if not (self.session and self.session["kind"] == "photo"):
            self._set_mode("photo")
            self._show_player(os.path.basename(path), _("Preparing the photo…"))

        def work():
            try:
                prep = media.prepare_photo(path, tv)
            except OSError as e:
                post(self._photo_failed, gen, str(e))
                return
            shown = pixmap_from(path=prep.get("file"), data=prep.get("data"))
            post(self._photo_ready, gen, files, index, prep, shown)
        threading.Thread(target=work, daemon=True).start()

    def _photo_ready(self, gen, files, index, prep, shown):
        if gen != self.gen:
            return
        prev, path = self.session, files[index]
        m = self.server.publish(path, kind="image", data=prep.get("data"), mime=prep["mime"],
                                features=prep["features"],
                                meta={"resolution": f"{prep['w']}x{prep['h']}"} if prep["w"] else None)
        ours = prev["ours"] if prev and prev["kind"] == "photo" else []
        warned = bool(prev and prev["kind"] == "photo" and prev.get("warned"))
        self._start_session("photo", path, 0, media=m, title=os.path.basename(path), queue=files, index=index,
                            ours=ours, warned=warned or bool(prep.get("unconverted")))
        if prep.get("unconverted") and not warned:  # once per slideshow
            self.show_info(_("The TV may not show {name}: this PC can't convert this kind of photo. "
                             "This helps:").format(name=os.path.basename(path)), WARNING,
                           selectable=core.install_hint(["photos"]))
        self._set_art(shown)
        self._update_status()
        if self.slideshow:
            self._schedule_slide()
        tv = self.tv
        for j in {(index + 1) % len(files), (index - 1) % len(files)} - {index}:  # the neighbours, for a quick click
            threading.Thread(target=self._quietly, args=(media.prepare_photo, files[j], tv), daemon=True).start()

    def _photo_failed(self, gen, error):
        if gen != self.gen:
            return
        self.show_info(_("Can't read that photo: {error}").format(error=error), WARNING)
        if not self.session:
            self.stack.setCurrentIndex(0)
        elif self.slideshow:
            self._schedule_slide()

    def toggle_slideshow(self):
        self.slideshow = not self.slideshow
        self._update_slide_button()
        if self.slideshow:
            self._schedule_slide()
        else:
            self._cancel_slide()

    def _update_slide_button(self):
        self.slide_btn.setText("⏸" if self.slideshow else "▶")

    def _schedule_slide(self):
        s = self.session
        if s and s["kind"] == "photo":
            self.slide_gen = s["gen"]
            self.slide_timer.start(int(self.slide_every.currentData() or 5) * 1000)

    def _cancel_slide(self):
        self.slide_timer.stop()

    def _slide_due(self):
        s = self.session
        if s and s["kind"] == "photo" and s["gen"] == getattr(self, "slide_gen", None) and self.slideshow:
            self.step(1)

    def _on_slide_every(self, _index):
        self.cfg["slide_seconds"] = int(self.slide_every.currentData() or 5)
        self._save()
        if self.slideshow:
            self._schedule_slide()

    # the desktop

    def _desk_set(self, key, value):
        self.cfg[key] = value
        self._save()

    def _fill_desk_screens(self):
        c = self.desk_screen
        c.clear()
        kind = desk.session_kind()
        if kind == "wayland":  # the desktop itself asks which screen or window
            c.addItem(_("Asked when you start"), "ask")
            c.setEnabled(False)
            return
        c.setEnabled(True)
        if kind == "macos":
            for _index, name in desk.mac_devices()[0]:
                n = int(name.rsplit(" ", 1)[-1])
                c.addItem(_("Screen {n}").format(n=n + 1), str(n))
        else:
            c.addItem(_("The one with this window"), "here")
            screens = QApplication.screens()
            for i, sc in enumerate(screens):
                g, r = sc.geometry(), sc.devicePixelRatio()
                c.addItem(f"{sc.name() or _('Screen {n}').format(n=i + 1)} "
                          f"({round(g.width() * r)}×{round(g.height() * r)})", str(i))
            if len(screens) > 1:
                c.addItem(_("All screens"), "all")
        i = c.findData(str(self.cfg.get("desk_screen", "here")))
        c.setCurrentIndex(max(0, i))

    def _cast_area(self):
        """X11 / Windows: (x, y, width, height) of the screen to cast, in real pixels; None for all screens."""
        choice = str(self.cfg.get("desk_screen", "here"))
        if choice == "all":
            return None
        screens = QApplication.screens()
        sc = screens[int(choice)] if choice.isdigit() and int(choice) < len(screens) else None
        sc = sc or self.screen() or QApplication.primaryScreen()
        if sc is None:
            return None
        if sys.platform == "win32":  # with screens at different scales only Windows knows the real pixels
            rect = _windows_monitors().get(sc.name())
            if rect:
                return rect
        g, r = sc.geometry(), sc.devicePixelRatio()
        return round(g.x() * r), round(g.y() * r), round(g.width() * r), round(g.height() * r)

    def cast_desktop(self):
        if not self._ready_for("desktop", self.cast_desktop, ()):
            return
        need = desk.missing()
        if need:
            self.show_info(_("Casting the desktop needs a little more software. Install it with:"), WARNING,
                           selectable=core.install_hint(need))
            return
        if self.session:
            self._end_session(stop_tv=False)
        if self.pending_cast:
            old, self.pending_cast = self.pending_cast, None
            threading.Thread(target=old.stop, daemon=True).start()
        self.clear_info()
        self.gen += 1
        gen = self.gen
        kind = desk.session_kind()
        self._set_mode("desktop")
        self._show_player(_("Your desktop"), _("Choose what to show in the window that opens…") if kind == "wayland"
                          else _("Starting the screen capture…"))
        screen = self.cfg.get("desk_screen")
        cast = desk.DesktopCast(height=int(self.cfg.get("desk_height", 1080)), fps=int(self.cfg.get("desk_fps", 30)),
                                sound=bool(self.cfg.get("desk_sound", True)),
                                area=self._cast_area() if kind in ("x11", "windows") else None,
                                restore_token=self.cfg.get("portal_token"),
                                screen=int(screen) if kind == "macos" and str(screen).isdigit() else None)
        cast.on_end = lambda msg: post(self._cast_ended, cast, msg)
        self.pending_cast = cast
        cast.start(lambda err: post(self._cast_started, gen, cast, err))

    def _cast_started(self, gen, cast, err):
        if cast is not self.pending_cast or gen != self.gen:
            threading.Thread(target=cast.stop, daemon=True).start()
            return
        self.pending_cast = None
        if err:
            self.stack.setCurrentIndex(0)
            self.show_info(_("Couldn't cast the desktop: {error}").format(error=err), ERROR)
            return
        if cast.restore_token and cast.restore_token != self.cfg.get("portal_token"):
            self.cfg["portal_token"] = cast.restore_token  # next time the desktop shares without asking
            self._save()
        m = self.server.publish(live=cast.live, title=_("Desktop"))
        self._start_session("desktop", None, 0, media=m, title=_("Your desktop"), cast=cast)
        if self.cfg.get("desk_sound", True) and not cast.sound:
            if sys.platform == "darwin":
                self.show_info(_("The Mac's sound isn't going to the TV: macOS doesn't hand it to apps by "
                                 "itself. Install BlackHole (free) and set it as the output to send it along."),
                               WARNING)
            else:
                self.show_info(_("The PC's sound isn't going to the TV: there's no sound server to record it from."),
                               WARNING)

    def _cast_ended(self, cast, message):
        s = self.session
        if s and s["cast"] is cast:
            self._end_session(stop_tv=True)
            self.show_info(_("Desktop casting stopped: {error}").format(error=message), WARNING)

    # ------------------------------------------------------------ subtitles

    def _sub_name(self, entry):
        return _(entry["lang"]) if entry.get("lang") else shorten(entry["label"], 24)

    def _update_sub_button(self):
        s = self.session
        if not s or s["kind"] != "movie":
            return
        if s.get("sub_loading"):
            text = _("Subtitles: loading…")
        elif s["sub_tv_changed"]:
            text = _("Subtitles: set on the TV")
        elif s["sub_on"] and s["sub_entry"]:
            text = _("Subtitles: {name}").format(name=self._sub_name(s["sub_entry"]))
        elif s["subs"] is None:
            text = _("Subtitles")
        elif not any(e["ok"] for e in s["subs"]):
            text = _("Subtitles: none")
        else:
            text = _("Subtitles: off")
        self.sub_button.setText(shorten(text, 32))

    def _fill_sub_menu(self):
        m = self.sub_menu
        m.clear()
        s = self.session
        title = m.addAction(_("Subtitles"))
        title.setEnabled(False)
        if not s or s["kind"] != "movie":
            return
        if s["subs"] is None:
            m.addAction(_("Looking for subtitles…")).setEnabled(False)
        else:
            current = None if s["sub_tv_changed"] else (s["sub_entry"]["id"] if s["sub_entry"] and s["sub_on"] else "off")
            for entry, text in [(None, _("Off"))] + [(e, shorten(e["label"])) for e in s["subs"] if e["ok"]]:
                act = m.addAction(text)
                act.setCheckable(True)
                act.setChecked((entry["id"] if entry else "off") == current)
                act.triggered.connect(lambda _c=False, e=entry: self._pick_subtitle(e))
            for e in s["subs"]:
                if not e["ok"]:
                    m.addAction(f"{shorten(e['label'], 40)} - {e['why']}").setEnabled(False)
            if not s["subs"]:
                m.addAction(_("No subtitles found next to this video or inside it.")).setEnabled(False)
        m.addSeparator()
        m.addAction(_("Choose a subtitle file…")).triggered.connect(lambda: after(0, self._choose_sub_file))
        notes = []
        if s["sub_tv_changed"]:
            notes.append(_("Subtitles were changed with the TV remote. The TV doesn't tell the PC what's on, "
                           "so pick an option here to set them from the PC."))
        if s["caption_api"] is False:
            notes.append(_("This TV can't switch subtitles on command, so OpusCast restarts the video "
                           "with the subtitles you pick."))
        else:
            notes.append(_("Subtitles not showing? The TV's own subtitle setting may be off. Turn subtitles on "
                           "once with the TV remote - the PC can't change that setting."))
        if core.check_deps()[3]:
            notes.append(_("Subtitles built into videos need ffmpeg:") + " " + core.install_hint(["subs"]))
        for n in notes:
            m.addSeparator()
            for line in _wrap(n, 60):
                m.addAction(line).setEnabled(False)

    def _pick_subtitle(self, entry):
        s = self.session
        if not s or s["kind"] != "movie":
            return
        s["sub_tv_changed"] = False
        self._remember(s["path"], sub=entry["id"] if entry else None)
        if s["caption_api"] is False:  # this TV only takes subtitles with the video: restart with them
            if entry is None and not s["sub_entry"]:
                s["sub_on"] = False
                self._update_sub_button()
            elif entry is not None and s["sub_entry"] and s["sub_entry"]["id"] == entry["id"]:
                s["sub_on"] = True
                self._update_sub_button()
            else:
                self._restart_with(entry)
            return
        if entry is None or entry["id"] in s["paths"]:
            self._show_sub(s, entry)
            return
        s["sub_loading"] = True  # extract / convert it first (cached after the first time)
        self._update_sub_button()
        self._update_status()

        def work():
            try:
                data = core.prepare_subtitle(entry, s["path"])
            except core.TVError as e:
                post(self._sub_failed, s["gen"], str(e))
                return
            post(self._sub_prepared, s, entry, data)
        threading.Thread(target=work, daemon=True).start()

    def _sub_prepared(self, s, entry, data):
        if s is self.session:
            s["paths"][entry["id"]] = self.server.add_subtitle(s["media"], data)
            self._show_sub(s, entry)

    def _show_sub(self, s, entry):
        """Switch the TV to this subtitle (None: the empty file, i.e. off) without restarting the video."""
        s["sub_loading"] = True
        self._update_sub_button()
        self.last_caption_cmd = time.monotonic()
        path = s["paths"][entry["id"]] if entry else None
        self.worker.submit(core.show_subtitle, self.tv, self.server, s["media"], path,
                           done=lambda r, sess=s, e=entry: self._sub_shown(sess, e, r))

    def _sub_shown(self, s, entry, result):
        if s is not self.session:
            return
        s["sub_loading"] = False
        if result != "ok":  # not a Samsung after all: the TV needs the subtitle announced with the video
            s["caption_api"] = False
            self._restart_with(entry)
            return
        s["caption_api"], s["sub_on"] = True, entry is not None
        if entry:
            s["sub_entry"] = entry
        self._update_sub_button()
        self._update_status()

    def _restart_with(self, entry):
        """Start the video again from here with a different subtitle (the TV needs it announced up front)."""
        s = self.session
        gen = s["gen"]
        s["sub_loading"] = True
        self._update_sub_button()
        self._update_status()

        def work():
            try:
                data = core.prepare_subtitle(entry, s["path"]) if entry else None
            except core.TVError as e:
                post(self._sub_failed, gen, str(e))
                return
            post(self._sub_ready, gen, entry, data)
        threading.Thread(target=work, daemon=True).start()

    def _sub_ready(self, gen, entry, data):
        s = self.session
        if s and s["gen"] == gen:
            self._start_session("movie", s["path"], self.est_pos(), subtitle=(entry, data) if entry else None,
                                subs_on=entry is not None, subs=s["subs"])

    def _sub_failed(self, gen, message):
        s = self.session
        if s and s["gen"] == gen:
            s["sub_loading"] = False
            self._update_sub_button()
            self.show_info(_("Couldn't use those subtitles: {error}").format(error=message), WARNING)

    def _choose_sub_file(self):
        s = self.session
        if not s or s["kind"] != "movie":
            return
        pattern = " ".join(f"*{e} *{e.upper()}" for e in core.SUB_EXTS)
        path, _sel = QFileDialog.getOpenFileName(self, _("Choose a subtitle file"), os.path.dirname(s["path"]),
                                                 f"{_('Subtitles')} ({pattern})")
        if path:
            self._use_sub_file(path)

    def _use_sub_file(self, path):
        s = self.session
        if not s:
            return
        entry = core.subtitle_file_entry(path)
        if s["subs"] is not None and not any(e["id"] == entry["id"] for e in s["subs"]):
            s["subs"].append(entry)
        self._pick_subtitle(entry)

    def _cycle_subtitles(self):
        """S key: off -> last/first subtitle -> off."""
        s = self.session
        if not s or s["kind"] != "movie" or not s["subs"]:
            return
        if s["sub_on"] and not s["sub_tv_changed"]:
            self._pick_subtitle(None)
            return
        usable = [e for e in s["subs"] if e["ok"]]
        entry = s["sub_entry"] or core.auto_subtitle(usable)
        if entry:
            self._pick_subtitle(entry)

    # ------------------------------------------------------------ seek bar & volume

    def _on_slider_pressed(self):
        self.dragging, self.drag_value = True, None

    def _on_slider_moved(self, value):
        if not self.session:
            return
        pos = value / 10
        self.elapsed.setText(fmt(pos))
        self.drag_value = pos
        now = time.monotonic()
        if self.preview.path and now - self.last_scrub > 0.12:
            self.last_scrub = now
            self.preview.seek(pos, accurate=False)

    def _on_slider_released(self):
        self.dragging = False
        if self.drag_value is not None:
            value, self.drag_value = self.drag_value, None
            self.seek_to(value)

    def _on_slider_action(self, action):
        # a click on the bar or the keyboard (dragging is handled above): jump once it settles
        if action not in (QSlider.SliderAction.SliderMove, QSlider.SliderAction.SliderNoAction) and not self.dragging:
            self.seek_timer.start()

    def _on_volume(self, _value):
        if self.setting_vol or not self.tv:
            return
        self.vol_touched = time.monotonic()
        self.vol_timer.start()

    def _send_volume(self):
        if self.tv:
            self.worker.submit(self.tv.set_volume, self.volume.value())

    # ------------------------------------------------------------ preview

    def _on_preview_toggled(self, on):
        if on and not self.preview.ok:
            self.preview_switch.setChecked(False)
            self.show_info(_("“Show on PC” isn't available here ({reason}).").format(reason=self.preview.reason),
                           WARNING)
            return
        self.cfg["preview"] = on
        self._save()
        if on and self.session:
            self._preview_load()
        elif not on:
            self.preview.unload()
        self._update_video_area()

    def _update_video_area(self):
        show = (bool(self.session) and self.session["kind"] in ("movie", "intro") and self.preview_switch.isChecked()
                and self.preview.ok)
        self.video_area.setCurrentIndex(1 if show else 0)
        if show and self.height() < 760 and not self.isMaximized():
            self.resize(max(self.width(), 900), 820)

    def _preview_load(self):
        self._update_video_area()
        if self.session and self.session["kind"] in ("movie", "intro") and self.preview.ok:
            gen = self.session["gen"]
            after(0, self._preview_load_now, gen)

    def _preview_load_now(self, gen):
        s = self.session
        if s and s["gen"] == gen and self.preview_switch.isChecked():
            self.preview.load(s["path"], self.est_pos())
            self._sync_preview()

    def _sync_preview(self):
        p, s = self.preview, self.session
        if not (s and p.path == s["path"]):
            return
        want = self.tv_state == "PLAYING" and s["seen_playing"] and not self.hold and not self.dragging
        p.set_playing(want)
        if self.dragging:
            return
        here = p.position()
        if here is None:
            return
        target = self.est_pos()
        if abs(here - target) > (1.5 if want else 0.3):
            p.seek(target)

    def _on_preview_error(self, message):
        self.preview_switch.setChecked(False)
        self.show_info(_("The PC preview can't play this video ({error}). The TV isn't affected.").format(
            error=message), WARNING)

    # ------------------------------------------------------------ misc

    def _on_intro_toggled(self, on):
        if on:
            ok, reason = intro.available()
            if not ok:
                self.intro_switch.setChecked(False)
                self.show_info(_("The Skynet intro {reason}. Install with:").format(reason=reason), WARNING,
                               selectable=core.install_hint(["intro"]))
                return
            if not intro.cached_path():  # render it now so the first play starts right away
                threading.Thread(target=lambda: self._quietly(intro.ensure_intro), daemon=True).start()
        self.cfg["intro"] = on
        self._save()

    @staticmethod
    def _quietly(fn, *args, **kw):
        try:
            fn(*args, **kw)
        except Exception:  # noqa: BLE001
            pass

    def _on_worker_error(self, error):
        s = self.session
        if s and s.get("sub_loading"):
            s["sub_loading"] = False
            self._update_sub_button()
        if s and s["kind"] == "photo" and s["index"]:
            self.show_info(_("The TV didn't take this photo: {error}").format(error=error), WARNING)
        elif s and not s["seen_playing"]:
            self._end_session(stop_tv=False)
            self.show_info(_("Couldn't start it on the TV: {error}").format(error=error), ERROR)
        else:
            self.show_info(_("The TV didn't accept that: {error}").format(error=error), WARNING)

    def keyPressEvent(self, ev):  # noqa: N802 - Qt's name
        if not self._key(ev.key(), ev.modifiers()):
            super().keyPressEvent(ev)

    def eventFilter(self, obj, ev):  # noqa: N802 - keys reach us before buttons, sliders and lists
        if (ev.type() == ev.Type.KeyPress and isinstance(obj, QWidget) and obj.window() is self
                and not isinstance(obj, QComboBox)):
            if self._key(ev.key(), ev.modifiers()):
                return True
        return super().eventFilter(obj, ev)

    def _key(self, k, mods):
        ctrl = bool(mods & Qt.ControlModifier)
        shift = bool(mods & Qt.ShiftModifier)
        if ctrl and k == Qt.Key_O:
            self.choose_file()
            return True
        if ctrl and k == Qt.Key_D:
            self.cast_desktop()
            return True
        if not self.session or ctrl:
            return False
        kind = self.session["kind"]
        if kind in ("music", "photo") and k in (Qt.Key_PageUp, Qt.Key_PageDown):
            self.step(1 if k == Qt.Key_PageDown else -1)
        elif kind == "photo" and k in (Qt.Key_Left, Qt.Key_Right):
            self.step(1 if k == Qt.Key_Right else -1)
        elif kind == "desktop" and k in (Qt.Key_Left, Qt.Key_Right, Qt.Key_Space):
            return False
        elif kind != "movie" and k in (Qt.Key_P, Qt.Key_S):
            return False
        elif k == Qt.Key_Space:
            self.toggle_play()
        elif k in (Qt.Key_Left, Qt.Key_Right):
            self.skip((30 if shift else 10) * (1 if k == Qt.Key_Right else -1))
        elif k in (Qt.Key_Up, Qt.Key_Down):
            self.volume.setValue(min(100, max(0, self.volume.value() + (5 if k == Qt.Key_Up else -5))))
        elif k == Qt.Key_P:
            self.preview_switch.setChecked(not self.preview_switch.isChecked())
        elif k == Qt.Key_S:
            self._cycle_subtitles()
        else:
            return False
        return True

    def _save(self):
        try:
            core.save_config(self.cfg)
        except OSError:
            pass

    def closeEvent(self, event):  # noqa: N802 - Qt's name
        s, tv = self.session, self.tv
        sids = dict(self.event_sids)

        def shutdown():
            if s:
                self._quietly(tv.stop, timeout=2.5)
            for service, sid in sids.items():
                tv.unsubscribe(service, sid)
        cast = s["cast"] if s else self.pending_cast
        if s:
            self._end_session(stop_tv=False)
        if cast:
            cast.stop()
        if tv:
            t = threading.Thread(target=shutdown, daemon=True)
            t.start()
            t.join(4)
        self.preview.unload()
        self._save()
        self.server.close()
        event.accept()


def _windows_monitors():
    """Windows: {"\\\\.\\DISPLAY1": (x, y, width, height)} in real pixels of the whole desktop, the
    coordinates ffmpeg's screen grab uses (Qt names its screens the same way)."""
    import ctypes
    from ctypes import wintypes

    class MonitorInfo(ctypes.Structure):
        _fields_ = [("cbSize", wintypes.DWORD), ("rcMonitor", wintypes.RECT), ("rcWork", wintypes.RECT),
                    ("dwFlags", wintypes.DWORD), ("szDevice", wintypes.WCHAR * 32)]
    found = {}
    proc = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HMONITOR, wintypes.HDC, ctypes.POINTER(wintypes.RECT),
                              wintypes.LPARAM)

    def each(hmon, _hdc, _rect, _data):
        info = MonitorInfo()
        info.cbSize = ctypes.sizeof(MonitorInfo)
        if ctypes.windll.user32.GetMonitorInfoW(hmon, ctypes.byref(info)):
            r = info.rcMonitor
            found[info.szDevice] = (r.left, r.top, r.right - r.left, r.bottom - r.top)
        return True
    try:
        ctypes.windll.user32.EnumDisplayMonitors(None, None, proc(each), 0)
    except (AttributeError, OSError):
        return {}
    return found


def _wrap(text, width):
    """Split a long note into menu-width lines."""
    lines, line = [], ""
    for word in text.split():
        if line and len(line) + 1 + len(word) > width:
            lines.append(line)
            line = word
        else:
            line = f"{line} {word}".strip()
    return lines + ([line] if line else [])


def app_icon_path():
    for d in (BUNDLE, os.path.join(HERE, "data")):
        for ext in (".svg", ".png"):
            p = os.path.join(d, core.APP_ID + ext)
            if os.path.exists(p):
                return p
    return ""


class _FileOpener(QObject):
    """Files to open arrive one at a time: macOS sends an event per file (Finder's Open With, a drop on
    the Dock icon); on Windows, opening several files starts the app once per file and the later ones
    hand theirs over here. Collected for a moment, so a whole album or set of photos plays together."""

    def __init__(self, window):
        super().__init__()
        self.window, self.paths = window, []

    def eventFilter(self, obj, ev):  # noqa: N802
        if ev.type() == ev.Type.FileOpen and ev.file():
            self.add([ev.file()])
            return True
        return super().eventFilter(obj, ev)

    def add(self, paths):
        self.paths += paths
        after(400, self._flush)

    def _flush(self):
        if self.paths:
            paths, self.paths = list(dict.fromkeys(self.paths)), []
            self.window.open_paths(paths)
            self.window.showNormal()
            self.window.raise_()
            self.window.activateWindow()


def _instance_name():
    import getpass
    try:
        user = getpass.getuser()
    except Exception:  # noqa: BLE001
        user = "user"
    return f"opuscast-{user}"


def _hand_over(paths):
    """Is OpusCast already running? Then give it these files and say so (True)."""
    from PySide6.QtNetwork import QLocalSocket
    sock = QLocalSocket()
    sock.connectToServer(_instance_name())
    if not sock.waitForConnected(400):
        return False
    import json
    sock.write(json.dumps(paths).encode())
    sock.waitForBytesWritten(2000)
    sock.disconnectFromServer()
    return True


def _listen_for_files(opener):
    """Be the one running OpusCast: files opened later come to this window."""
    import json
    from PySide6.QtNetwork import QLocalServer
    server = QLocalServer(opener)
    QLocalServer.removeServer(_instance_name())  # left over after a crash
    if not server.listen(_instance_name()):
        return None

    def connected():
        sock = server.nextPendingConnection()

        def read():
            try:
                paths = json.loads(bytes(sock.readAll()).decode() or "[]")
            except ValueError:
                paths = []
            files = [p for p in paths if isinstance(p, str)]
            if files:
                opener.add(files)
            else:  # started again without files: just come to the front
                opener.window.showNormal()
                opener.window.raise_()
                opener.window.activateWindow()
        sock.readyRead.connect(read)
        sock.disconnected.connect(sock.deleteLater)
    server.newConnection.connect(connected)
    return server


def main():
    global _bridge
    app = QApplication(sys.argv)
    app.setApplicationName(core.APP_NAME)
    app.setWindowIcon(QIcon(app_icon_path()))
    app.setStyleSheet(STYLE)
    _bridge = _Bridge()
    if "--selftest" in sys.argv:  # CI: everything imports and the window builds
        return _selftest(app)
    paths = [os.path.abspath(a) for a in sys.argv[1:] if not a.startswith("-") and os.path.exists(a)]
    if _hand_over(paths):
        return 0  # the OpusCast that's already open plays them
    try:
        win = OpusCastWindow()
    except core.TVError as e:
        from PySide6.QtWidgets import QMessageBox
        QMessageBox.critical(None, _("{app} can't start").format(app=core.APP_NAME), str(e))
        return 1
    app.installEventFilter(win)
    opener = _FileOpener(win)
    app.installEventFilter(opener)
    server = _listen_for_files(opener)  # noqa: F841 - kept alive with the app
    win.show()
    if paths:
        opener.add(paths)
    return app.exec()


def _selftest(app):
    win = OpusCastWindow()
    win.show()
    report = {"python": sys.version.split()[0], "qt": __import__("PySide6").__version__,
              "preview": win.preview.ok, "ffmpeg": core.have("ffmpeg"), "ffprobe": core.have("ffprobe"),
              "intro": intro.available()[0], "desktop": desk.session_kind(), "desktop_missing": desk.missing(),
              "encoder": desk.video_encoder()[0] if core.have("ffmpeg") else None,
              "config": core.CONFIG_DIR}
    out = os.environ.get("OPUSCAST_SELFTEST_OUT")  # a windowed Windows app has no console to print to
    if out:
        import json
        with open(out, "w", encoding="utf-8") as f:
            json.dump(report, f)
    elif sys.stdout:
        print("OPUSCAST SELFTEST", report, flush=True)
    after(1500, win.close)
    after(2500, app.quit)
    app.exec()
    return 0


if __name__ == "__main__":
    sys.exit(main())
