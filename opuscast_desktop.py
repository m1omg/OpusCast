"""OpusCast desktop casting: this PC's screen and sound as a live video stream for the TV.

X11 (Cinnamon, MATE, Xfce, KDE or GNOME on X11): ffmpeg grabs the screen directly - no screen-sharing
portal needed, which is what Linux Mint's Cinnamon doesn't have. Wayland (GNOME, KDE Plasma, Sway...):
the desktop's screen-cast portal asks which screen or window to share and hands over a PipeWire
stream; GStreamer reads the pictures from it and ffmpeg encodes them. Either way the TV gets H.264
(or MPEG-2 when no H.264 encoder works here) with the PC's sound in an MPEG-TS stream - the format
every DLNA TV plays live.
"""
import glob
import os
import re
import secrets
import subprocess
import sys
import threading
import time

import opuscast_core as core
from opuscast_i18n import _

SIZES = {720: (1280, 720), 1080: (1920, 1080)}
SILENCE = ["-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=48000"]


def session_kind():
    """'windows', 'macos', 'wayland', 'x11', or None when there's no graphical session."""
    if sys.platform == "win32":
        return "windows"
    if sys.platform == "darwin":
        return "macos"
    if os.environ.get("WAYLAND_DISPLAY") or os.environ.get("XDG_SESSION_TYPE") == "wayland":
        return "wayland"
    return "x11" if os.environ.get("DISPLAY") else None


def missing():
    """Dependency keys (see core.PACKAGES) desktop casting still needs here."""
    need = [] if core.have("ffmpeg") else ["desktop"]
    if session_kind() == "wayland" and not _gst_pipewire():
        need.append("desktop-wayland")
    return need


def _gst_pipewire():
    try:
        import gi
        gi.require_version("Gst", "1.0")
        from gi.repository import Gst
        Gst.init(None)
        return Gst.ElementFactory.find("pipewiresrc") is not None
    except (ImportError, ValueError):
        return False


# ---------------------------------------------------------------- encoding

_encoder, _encoder_lock = None, threading.Lock()
_queue_ok, _queue_lock = None, threading.Lock()


def input_queue(packets):
    """["-thread_queue_size", packets] to put before a live input, if this ffmpeg takes it there: up to
    ffmpeg 9.0 it keeps a short encoder stall from losing sound. Later ffmpeg sizes its input queues
    itself and refuses the option before an input (it only sets the muxer's queue now): then nothing."""
    global _queue_ok
    with _queue_lock:
        if _queue_ok is None:
            try:
                _queue_ok = subprocess.run(
                    ["ffmpeg", "-v", "error", "-nostdin", "-thread_queue_size", "8", "-f", "lavfi", "-i",
                     "anullsrc", "-t", "0.1", "-f", "null", "-"],
                    capture_output=True, timeout=15, **core.NO_WINDOW).returncode == 0
            except (OSError, subprocess.SubprocessError):
                _queue_ok = False
    return ["-thread_queue_size", str(packets)] if _queue_ok else []


def video_encoder():
    """(name, VA-API device) of the first video encoder that really works here: libx264, then the
    graphics card (VA-API), then OpenH264 - else MPEG-2, which every DLNA TV plays too."""
    global _encoder
    with _encoder_lock:
        if _encoder is None:
            _encoder = _find_encoder()
        return _encoder


def _find_encoder():
    try:
        listed = subprocess.run(["ffmpeg", "-hide_banner", "-encoders"], capture_output=True, text=True,
                                timeout=15, **core.NO_WINDOW).stdout
    except (OSError, subprocess.SubprocessError):
        return None, None
    for name in ("libx264", "h264_vaapi", "libopenh264", "mpeg2video"):
        if not re.search(rf"^\s*V\S*\s+{name}\s", listed, re.M):
            continue
        devices = sorted(glob.glob("/dev/dri/renderD*")) if name == "h264_vaapi" else [None]
        for dev in devices:
            pre, vf, args = encoder_args(name, dev, 30, 720)
            test = ["ffmpeg", "-v", "error", "-nostdin", *pre, "-f", "lavfi", "-i", "testsrc2=s=1280x720:r=30",
                    "-frames:v", "20", "-vf", vf, *args, "-f", "null", "-"]
            try:
                if subprocess.run(test, capture_output=True, timeout=20, **core.NO_WINDOW).returncode == 0:
                    return name, dev
            except (OSError, subprocess.SubprocessError):
                pass
    return None, None


def encoder_args(name, dev, fps, height):
    """(input options, last filters, encoder options). A keyframe every second, so the TV can join
    the stream quickly; no B-frames, so pictures leave right away."""
    mbit = (4 if height <= 720 else 6) * (1.5 if fps > 30 else 1)
    rate, half, gop = f"{int(mbit * 1000)}k", f"{int(mbit * 500)}k", str(fps)
    level = "4.2" if fps > 30 else "4.1"
    if name == "libx264":
        return [], "format=yuv420p", [
            "-c:v", "libx264", "-preset", "veryfast", "-tune", "zerolatency", "-profile:v", "main",
            "-level:v", level, "-g", gop, "-keyint_min", gop, "-sc_threshold", "0",
            "-b:v", rate, "-maxrate", rate, "-bufsize", half]
    if name == "h264_vaapi":
        return ["-vaapi_device", dev], "format=nv12,hwupload", [
            "-c:v", "h264_vaapi", "-profile:v", "main", "-g", gop, "-bf", "0", "-b:v", rate, "-maxrate", rate]
    if name == "libopenh264":
        return [], "format=yuv420p", ["-c:v", "libopenh264", "-g", gop, "-b:v", rate, "-maxrate", rate]
    big = f"{int(mbit * 2000)}k"
    return [], "format=yuv420p", ["-c:v", "mpeg2video", "-g", str(max(1, fps // 2)), "-bf", "0",
                                  "-b:v", big, "-maxrate", big, "-bufsize", rate]


def encoder_label(name):
    return {"libx264": "H.264", "h264_vaapi": _("H.264 (graphics card)"), "libopenh264": "H.264 (OpenH264)",
            "mpeg2video": "MPEG-2"}.get(name, name or "?")


def sound_source():
    """Where to record what this PC plays: Linux - the PulseAudio/PipeWire monitor of the default
    output; Windows - "wasapi" (loopback of the default output); macOS - a loopback device such as
    BlackHole, if one is installed (macOS itself can't hand over the Mac's sound). None if nothing."""
    if sys.platform == "win32":
        return "wasapi" if _Loopback.available() else None
    if sys.platform == "darwin":
        return next((name for _i, name in mac_devices()[1] if LOOPBACK_DEVICE.search(name)), None)
    names = []
    if core.have("pactl"):
        try:
            sink = subprocess.run(["pactl", "get-default-sink"], capture_output=True, text=True, timeout=3).stdout
            if not sink.strip():
                info = subprocess.run(["pactl", "info"], capture_output=True, text=True, timeout=3).stdout
                m = re.search(r"^Default Sink:\s*(\S+)", info, re.M)
                sink = m.group(1) if m else ""
            if sink.strip():
                names.append(sink.strip() + ".monitor")
        except (OSError, subprocess.SubprocessError):
            pass
    names.append("@DEFAULT_MONITOR@")
    for name in names:
        try:
            r = subprocess.run(["ffmpeg", "-v", "error", "-nostdin", "-f", "pulse", "-i", name, "-t", "0.2",
                                "-f", "null", "-"], capture_output=True, timeout=8)
            if r.returncode == 0:
                return name
        except (OSError, subprocess.SubprocessError):
            pass
    return None


LOOPBACK_DEVICE = re.compile(r"blackhole|loopback|soundflower|background music|vb-cable", re.I)


def mac_devices():
    """macOS: ([(index, "Capture screen 0"), ...], [(index, audio input name), ...]) as ffmpeg's
    AVFoundation input numbers them."""
    try:
        err = subprocess.run(["ffmpeg", "-hide_banner", "-f", "avfoundation", "-list_devices", "true", "-i", ""],
                             capture_output=True, text=True, timeout=15).stderr
    except (OSError, subprocess.SubprocessError):
        return [], []
    screens, audio, section = [], [], None
    for line in err.splitlines():
        if "video devices" in line:
            section = "video"
        elif "audio devices" in line:
            section = "audio"
        else:
            m = re.search(r"\]\s*\[(\d+)\]\s*(.+)$", line)
            if m and section == "video" and m.group(2).startswith("Capture screen"):
                screens.append((int(m.group(1)), m.group(2).strip()))
            elif m and section == "audio":
                audio.append((int(m.group(1)), m.group(2).strip()))
    return screens, audio


def mac_major():
    """macOS's major version (12 = Monterey, where the settings app still was System Preferences)."""
    import platform
    try:
        return int(platform.mac_ver()[0].split(".")[0])
    except ValueError:
        return 0


def mac_screen_access():
    """macOS: may this app record the screen? Asks macOS to show its question if not yet decided
    (until allowed, only the wallpaper would reach the TV)."""
    import ctypes
    try:
        cg = ctypes.cdll.LoadLibrary("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics")
        preflight, request = cg.CGPreflightScreenCaptureAccess, cg.CGRequestScreenCaptureAccess
    except (OSError, AttributeError):
        return True  # older than macOS 10.15: nothing to allow
    preflight.restype = request.restype = ctypes.c_bool
    if preflight():
        return True
    request()
    return False


class _Loopback:
    """Windows: what the PC plays (WASAPI loopback of the default output) as 16-bit PCM for ffmpeg.
    While nothing plays, Windows sends nothing at all - silence is filled in, so the TV's stream
    keeps going."""

    @staticmethod
    def available():
        try:
            import pyaudiowpatch as pa
            p = pa.PyAudio()
            try:
                p.get_default_wasapi_loopback()
                return True
            finally:
                p.terminate()
        except Exception:  # noqa: BLE001 - not installed, no output device, WASAPI unavailable...
            return False

    def __init__(self):
        import pyaudiowpatch as pa
        self.pa = pa.PyAudio()
        dev = self.pa.get_default_wasapi_loopback()
        self.rate, self.channels = int(dev["defaultSampleRate"]), max(1, int(dev["maxInputChannels"]))
        self.stream = self.pa.open(format=pa.paInt16, channels=self.channels, rate=self.rate, input=True,
                                   input_device_index=dev["index"], frames_per_buffer=1024)
        self.running = True

    def ffmpeg_input(self):
        return [*input_queue(1024), "-f", "s16le", "-ar", str(self.rate), "-ac", str(self.channels),
                "-i", "pipe:0"]

    def run(self, write):
        frame = 2 * self.channels
        start, sent = time.monotonic(), 0
        try:
            while self.running:
                ready = self.stream.get_read_available()
                if ready:
                    data = self.stream.read(ready, exception_on_overflow=False)
                    sent += len(data) // frame
                    write(data)
                    continue
                behind = int((time.monotonic() - start) * self.rate) - sent
                if behind > self.rate // 20:  # 50 ms without sound: nothing plays
                    write(b"\0" * (behind * frame))
                    sent += behind
                time.sleep(0.01)
        except (BrokenPipeError, ValueError, OSError):
            pass
        finally:
            self.stream.close()
            self.pa.terminate()

    def stop(self):
        self.running = False


def x11_screens():
    """[(name, x, y, width, height, primary)] from xrandr; [] if it can't tell."""
    try:
        out = subprocess.run(["xrandr", "--listactivemonitors"], capture_output=True, text=True, timeout=5).stdout
    except (OSError, subprocess.SubprocessError):
        return []
    screens = []
    for m in re.finditer(r"^\s*\d+:\s+\+?(\*?)(\S+)\s+(\d+)/\d+x(\d+)/\d+\+(\d+)\+(\d+)", out, re.M):
        screens.append((m.group(2), int(m.group(5)), int(m.group(6)), int(m.group(3)), int(m.group(4)),
                        bool(m.group(1))))
    return screens


# ---------------------------------------------------------------- the Wayland screen-cast portal

class Portal:
    """xdg-desktop-portal's ScreenCast: the desktop asks the user what to share and gives us a PipeWire
    stream of it. Needs a running GLib main loop (the app window has one; the command line runs one)."""

    NAME, PATH, IFACE = "org.freedesktop.portal.Desktop", "/org/freedesktop/portal/desktop", \
        "org.freedesktop.portal.ScreenCast"

    def __init__(self):
        import gi
        gi.require_version("Gio", "2.0")
        from gi.repository import Gio, GLib
        self.Gio, self.GLib = Gio, GLib
        self.bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)
        self.session = None
        self.closed_sub = None
        self.on_closed = None

    def prop(self, name):
        r = self.bus.call_sync(self.NAME, self.PATH, "org.freedesktop.DBus.Properties", "Get",
                               self.GLib.Variant("(ss)", (self.IFACE, name)), self.GLib.VariantType("(v)"),
                               self.Gio.DBusCallFlags.NONE, 5000, None)
        return r.unpack()[0]

    def _request(self, method, make_args, done):
        """Call a method that answers later, through a Request object's Response signal."""
        GLib, Gio = self.GLib, self.Gio
        token = "opuscast_" + secrets.token_hex(6)
        sender = self.bus.get_unique_name()[1:].replace(".", "_")
        path = f"/org/freedesktop/portal/desktop/request/{sender}/{token}"
        sub = []

        def on_response(_conn, _sender, _path, _iface, _signal, params):
            self.bus.signal_unsubscribe(sub[0])
            code, results = params.unpack()
            done(code, results)

        def called(bus, result):
            try:
                bus.call_finish(result)
            except GLib.Error as e:
                self.bus.signal_unsubscribe(sub[0])
                done(2, {"error": e.message})
        sub.append(self.bus.signal_subscribe(self.NAME, "org.freedesktop.portal.Request", "Response", path, None,
                                             Gio.DBusSignalFlags.NONE, on_response))
        self.bus.call(self.NAME, self.PATH, self.IFACE, method, make_args(GLib.Variant("s", token)), None,
                      Gio.DBusCallFlags.NONE, -1, None, called)

    def start(self, ready, failed, restore_token=None):
        """Ask the user; then ready(pipewire_fd, node_id, (w, h) or None, restore_token) or failed(message)."""
        GLib = self.GLib
        try:
            version = self.prop("version")
            sources = self.prop("AvailableSourceTypes")
            cursors = self.prop("AvailableCursorModes")
        except GLib.Error as e:
            failed(_("this desktop doesn't offer screen sharing to apps (no screen-cast portal: {error})").format(
                error=e.message))
            return

        def created(code, res):
            if code != 0:
                failed(res.get("error") or _("the desktop refused to start screen sharing"))
                return
            self.session = res["session_handle"]
            self.closed_sub = self.bus.signal_subscribe(
                self.NAME, "org.freedesktop.portal.Session", "Closed", self.session, None,
                self.Gio.DBusSignalFlags.NONE, lambda *_a: self.on_closed and self.on_closed())
            opts = {"types": GLib.Variant("u", (1 | 2) & sources or 1), "multiple": GLib.Variant("b", False),
                    "cursor_mode": GLib.Variant("u", 2 if cursors & 2 else 1)}
            if version >= 4:  # remember the choice, so next time it starts without asking
                opts["persist_mode"] = GLib.Variant("u", 2)
                if restore_token:
                    opts["restore_token"] = GLib.Variant("s", restore_token)
            self._request("SelectSources", lambda t: GLib.Variant("(oa{sv})", (self.session, {**opts, "handle_token": t})),
                          selected)

        def selected(code, res):
            if code != 0:
                failed(res.get("error") or _("screen sharing was cancelled"))
                return
            self._request("Start", lambda t: GLib.Variant("(osa{sv})", (self.session, "", {"handle_token": t})),
                          started)

        def started(code, res):
            if code == 1:
                failed(_("screen sharing was cancelled"))
                return
            streams = res.get("streams") or []
            if code != 0 or not streams:
                failed(res.get("error") or _("the desktop didn't share a screen"))
                return
            node, props = streams[0]
            try:
                fd = self._open_remote()
            except GLib.Error as e:
                failed(e.message)
                return
            ready(fd, node, props.get("size"), res.get("restore_token"))
        self._request("CreateSession", lambda t: GLib.Variant("(a{sv})", ({
            "handle_token": t, "session_handle_token": GLib.Variant("s", "opuscast" + secrets.token_hex(4))},)),
            created)

    def _open_remote(self):
        GLib, Gio = self.GLib, self.Gio
        result, fds = self.bus.call_with_unix_fd_list_sync(
            self.NAME, self.PATH, self.IFACE, "OpenPipeWireRemote",
            GLib.Variant("(oa{sv})", (self.session, {})), GLib.VariantType("(h)"),
            Gio.DBusCallFlags.NONE, 10000, None, None)
        return fds.get(result.unpack()[0])

    def close(self):
        if self.closed_sub is not None:
            self.bus.signal_unsubscribe(self.closed_sub)
            self.closed_sub = None
        if self.session:
            try:
                self.bus.call_sync(self.NAME, self.session, "org.freedesktop.portal.Session", "Close", None, None,
                                   self.Gio.DBusCallFlags.NONE, 2000, None)
            except self.GLib.Error:
                pass
            self.session = None


class _PipeWireFrames:
    """GStreamer reads the shared screen from PipeWire, scales it into the TV picture (black borders
    when the shapes differ) and hands out raw frames at a steady rate."""

    def __init__(self, fd, node, width, height, fps):
        import gi
        gi.require_version("Gst", "1.0")
        from gi.repository import Gst
        Gst.init(None)
        self.Gst = Gst
        probe = Gst.ElementFactory.make("pipewiresrc", None)
        if probe is None:
            raise core.TVError(_("GStreamer's PipeWire plugin is missing"))
        # the portal names the stream by node id: "path" takes that ("target-object" is its newer stand-in)
        target = "path" if probe.find_property("path") else "target-object"
        props = [f"fd={fd}", f"{target}={node}", "do-timestamp=true"]
        if probe.find_property("keepalive-time"):  # repeat the picture while nothing on screen changes
            props.append("keepalive-time=100")
        if probe.find_property("always-copy"):
            props.append("always-copy=true")
        self.pipeline = Gst.parse_launch(
            f"pipewiresrc {' '.join(props)} ! queue max-size-buffers=2 leaky=downstream ! videoconvert ! "
            "videoscale add-borders=true ! videorate ! "
            f"video/x-raw,format=I420,width={width},height={height},framerate={fps}/1,pixel-aspect-ratio=1/1 ! "
            "appsink name=sink sync=false max-buffers=2 drop=true")
        self.sink = self.pipeline.get_by_name("sink")
        self.frame = width * height * 3 // 2
        self.running = False
        self.fd = fd  # GStreamer works on its own copy; this one is ours to close

    def run(self, write, failed):
        """Push frames into write() until stopped; failed(message) if GStreamer gives up."""
        Gst = self.Gst
        self.running = True
        if self.pipeline.set_state(Gst.State.PLAYING) == Gst.StateChangeReturn.FAILURE:
            failed(_("GStreamer couldn't read the shared screen"))
            return
        bus = self.pipeline.get_bus()
        while self.running:
            sample = self.sink.emit("try-pull-sample", 200 * Gst.MSECOND)
            msg = bus.pop_filtered(Gst.MessageType.ERROR | Gst.MessageType.EOS)
            if msg is not None:
                if self.running:
                    failed(msg.parse_error()[0].message if msg.type == Gst.MessageType.ERROR
                           else _("the shared screen went away"))
                break
            if sample is None:
                continue
            buf = sample.get_buffer()
            if buf.get_size() != self.frame:
                continue
            try:
                write(buf.extract_dup(0, self.frame))
            except (BrokenPipeError, ValueError, OSError):
                break
        self.pipeline.set_state(Gst.State.NULL)
        try:
            os.close(self.fd)
        except OSError:
            pass

    def stop(self):
        self.running = False


# ---------------------------------------------------------------- one cast

class DesktopCast:
    """Captures the screen (and sound) into a LiveStream the media server hands to the TV.

    start(done) prepares everything - on Wayland the desktop asks the user first - and calls
    done(error) once the first picture is encoded (error is None) or it failed. on_end(error) is
    called if capturing stops by itself (the user stopped sharing, ffmpeg died...). Both may be
    called from another thread."""

    def __init__(self, height=1080, fps=30, sound=True, area=None, restore_token=None, screen=None):
        self.width, self.height = SIZES.get(height, SIZES[1080])
        # area: X11/Windows (x, y, width, height) in real pixels, None for everything; screen: macOS's
        # screen number ("Capture screen N"), None for the main one
        self.fps, self.want_sound, self.area, self.screen = fps, sound, area, screen
        self.loopback = None
        self.restore_token = restore_token  # Wayland: the portal's "remember this screen"
        self.live = core.LiveStream()
        self.proc = self.portal = self.frames = None
        self.on_end = None
        self.sound = None  # the source actually recorded, once started
        self.encoder = None
        self.stopped = False
        self._log = []
        self._ended = False

    # what the window shows
    def describe(self):
        bits = [f"{self.height}p", f"{self.fps} fps", encoder_label(self.encoder)]
        bits.append(_("with sound") if self.sound else _("no sound"))
        return " · ".join(bits)

    def start(self, done):
        kind = session_kind()
        if kind is None:
            done(_("there's no desktop to cast here"))
            return
        if not core.have("ffmpeg"):
            done(_("desktop casting needs ffmpeg"))
            return
        if kind in ("x11", "windows", "macos"):
            start = {"x11": self._start_x11, "windows": self._start_windows, "macos": self._start_macos}[kind]
            threading.Thread(target=start, args=(done,), daemon=True).start()
            return
        if not _gst_pipewire():
            done(_("desktop casting on Wayland needs GStreamer's PipeWire plugin"))
            return
        try:
            self.portal = Portal()
        except Exception as e:  # noqa: BLE001 - no D-Bus session, no PyGObject...
            done(_("can't reach the desktop's screen sharing ({error})").format(error=e))
            return
        self.portal.on_closed = lambda: self._ended_by(_("screen sharing was stopped"))

        def ready(fd, node, size, token):
            self.restore_token = token or self.restore_token
            threading.Thread(target=self._start_wayland, args=(fd, node, done), daemon=True).start()
        self.portal.start(ready, lambda msg: self._fail(done, msg), self.restore_token)

    def _common(self, audio_map="1:a:0"):
        """Prepare encoder and sound (slow the first time: tries what works). Returns (input options
        for the encoder, the last video filters, output options); the sound source is in self.sound."""
        self.encoder, dev = video_encoder()
        if not self.encoder:
            raise core.TVError(_("ffmpeg here can't encode video"))
        self.sound = sound_source() if self.want_sound else None
        pre, vf, venc = encoder_args(self.encoder, dev, self.fps, self.height)
        out = ["-map", "0:v:0", "-map", audio_map, *venc, "-c:a", "aac", "-b:a", "160k", "-ar", "48000",
               "-ac", "2", "-f", "mpegts", "-mpegts_flags", "+resend_headers", "-muxdelay", "0",
               "-muxpreload", "0", "-flush_packets", "1", "pipe:1"]
        return pre, vf, out

    def _fit(self, vf):
        """Scale whatever shape the screen has into the TV picture, black borders around."""
        return (f"scale={self.width}:{self.height}:force_original_aspect_ratio=decrease:flags=bicubic,"
                f"pad={self.width}:{self.height}:(ow-iw)/2:(oh-ih)/2:black,setsar=1,{vf}")

    def _start_x11(self, done):
        try:
            pre, vf, out = self._common()
        except core.TVError as e:
            self._fail(done, str(e))
            return
        audio = [*input_queue(1024), "-f", "pulse", "-sample_rate", "48000", "-channels", "2",
                 "-fragment_size", "4096", "-i", self.sound] if self.sound else SILENCE
        display = os.environ.get("DISPLAY", ":0")
        grab = [*input_queue(64), "-f", "x11grab", "-draw_mouse", "1", "-framerate", str(self.fps)]
        if self.area:
            x, y, w, h = self.area
            grab += ["-video_size", f"{w - w % 2}x{h - h % 2}", "-i", f"{display}+{x},{y}"]
        else:
            grab += ["-i", display]
        self._run(done, [*pre, *grab, *audio, "-vf", self._fit(vf), *out], stdin=subprocess.DEVNULL)

    def _start_windows(self, done):
        """GDI screen grab (every Windows 10/11), the sound through WASAPI loopback."""
        try:
            pre, vf, out = self._common()
        except core.TVError as e:
            self._fail(done, str(e))
            return
        if self.sound:
            try:
                self.loopback = _Loopback()
            except Exception:  # noqa: BLE001 - PortAudio errors come in many types
                self.sound = None  # the picture still goes, without sound
        grab = [*input_queue(64), "-f", "gdigrab", "-framerate", str(self.fps), "-draw_mouse", "1"]
        if self.area:
            x, y, w, h = self.area
            grab += ["-offset_x", str(x), "-offset_y", str(y), "-video_size", f"{w - w % 2}x{h - h % 2}"]
        grab += ["-i", "desktop"]
        audio = self.loopback.ffmpeg_input() if self.loopback else SILENCE
        stdin = subprocess.PIPE if self.loopback else subprocess.DEVNULL
        if not self._run(done, [*pre, *grab, *audio, "-vf", self._fit(vf), *out], stdin=stdin, wait=False):
            return
        if self.loopback:
            threading.Thread(target=self.loopback.run, args=(self.proc.stdin.write,), daemon=True).start()
        self._wait_first_picture(done)

    def _start_macos(self, done):
        """AVFoundation screen capture (allowed under Privacy → Screen Recording); the sound from a
        loopback device such as BlackHole when there is one."""
        if not mac_screen_access():
            self._fail(done, _("macOS hasn't allowed OpusCast to record the screen yet. Allow it in System "
                               "Settings → Privacy & Security → Screen Recording, then start OpusCast again.")
                       if mac_major() >= 13 else
                       _("macOS hasn't allowed OpusCast to record the screen yet. Allow it in System Preferences → "
                         "Security & Privacy → Privacy → Screen Recording, then start OpusCast again."))
            return
        screens, audio_devices = mac_devices()
        if not screens:
            self._fail(done, _("ffmpeg here can't see the screen (no AVFoundation screen capture)"))
            return
        video = next((i for i, name in screens if name.endswith(f" {self.screen or 0}")), screens[0][0])
        sound = next((i for i, name in audio_devices if self.want_sound and LOOPBACK_DEVICE.search(name)), None)
        try:
            pre, vf, out = self._common(audio_map="0:a:0" if sound is not None else "1:a:0")
        except core.TVError as e:
            self._fail(done, str(e))
            return
        grab = [*input_queue(64), "-f", "avfoundation", "-capture_cursor", "1", "-framerate",
                str(self.fps), "-i", f"{video}:{sound if sound is not None else 'none'}"]
        if sound is None:
            self.sound = None
            grab += SILENCE
        self._run(done, [*pre, *grab, "-vf", self._fit(vf), *out], stdin=subprocess.DEVNULL)

    def _start_wayland(self, fd, node, done):
        try:
            pre, vf, out = self._common()
            self.frames = _PipeWireFrames(fd, node, self.width, self.height, self.fps)
        except Exception as e:  # noqa: BLE001 - GStreamer errors come in many types
            self._fail(done, str(e))
            return
        audio = [*input_queue(1024), "-f", "pulse", "-sample_rate", "48000", "-channels", "2",
                 "-fragment_size", "4096", "-i", self.sound] if self.sound else SILENCE
        raw = [*input_queue(64), "-f", "rawvideo", "-pix_fmt", "yuv420p",
               "-video_size", f"{self.width}x{self.height}", "-framerate", str(self.fps),
               "-use_wallclock_as_timestamps", "1", "-i", "pipe:0"]
        if not self._run(done, [*pre, *raw, *audio, "-vf", vf, "-r", str(self.fps), *out], stdin=subprocess.PIPE,
                         wait=False):
            return
        stdin = self.proc.stdin
        threading.Thread(target=self.frames.run, args=(stdin.write, self._ended_by), daemon=True).start()
        self._wait_first_picture(done)

    def _run(self, done, args, stdin, wait=True):
        try:
            self.proc = subprocess.Popen(["ffmpeg", "-hide_banner", "-nostdin", "-loglevel", "error", *args],
                                         stdin=stdin, stdout=subprocess.PIPE, stderr=subprocess.PIPE, **core.NO_WINDOW)
        except OSError as e:
            self._fail(done, str(e))
            return False
        threading.Thread(target=self._pump, args=(self.proc,), daemon=True).start()
        threading.Thread(target=self._read_log, args=(self.proc,), daemon=True).start()
        if wait:
            self._wait_first_picture(done)
        return True

    def _wait_first_picture(self, done):
        if self.live.wait_started(20):
            done(None)
            return
        self._fail(done, self._why() or _("the screen capture didn't start"))

    def _pump(self, proc):
        fd = proc.stdout.fileno()
        while True:
            try:
                data = os.read(fd, 1 << 16)
            except OSError:
                data = b""
            if not data:
                break
            self.live.feed(data)
        proc.wait()
        time.sleep(0.2)  # let the log reader catch the last words
        self._ended_by(self._why() or _("the screen capture stopped"))

    def _read_log(self, proc):
        for line in proc.stderr:
            text = line.decode(errors="replace").strip()
            if text:
                self._log = (self._log + [text])[-12:]

    def _why(self):
        """ffmpeg's own reason: its last lines are often just "Error opening input files", the line
        before says what really failed."""
        generic = re.compile(r"^(Error (opening|parsing options for) (input|output)|Conversion failed|Error while|"
                             r"\[(in|out)#\d)", re.I)
        specific = [line for line in self._log if not generic.match(line)]
        return (specific or self._log or [None])[-1]

    def _fail(self, done, message):
        self.stop()
        done(message)

    def _ended_by(self, message):
        if self._ended:
            return
        self._ended = True
        stopped = self.stopped
        self.stop()
        if not stopped and self.on_end:
            self.on_end(message)

    def stop(self):
        self.stopped = True
        if self.frames:
            self.frames.stop()
        if self.loopback:
            self.loopback.stop()
        proc, self.proc = self.proc, None
        if proc and proc.poll() is None:
            try:
                if proc.stdin:
                    proc.stdin.close()
            except OSError:
                pass
            proc.terminate()
            try:
                proc.wait(3)
            except subprocess.TimeoutExpired:
                proc.kill()
        if self.portal:
            self.portal.close()
        self.live.close()
