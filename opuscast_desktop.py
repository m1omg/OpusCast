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
import threading
import time

import opuscast_core as core
from opuscast_i18n import _

SIZES = {720: (1280, 720), 1080: (1920, 1080)}
SILENCE = ["-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=48000"]


def session_kind():
    """'wayland', 'x11', or None when there's no graphical session."""
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
                                timeout=15).stdout
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
                if subprocess.run(test, capture_output=True, timeout=20).returncode == 0:
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
    """The PulseAudio/PipeWire source that hears what this PC plays (the default output's monitor),
    or None when there's no sound server ffmpeg can record from."""
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
        props = [f"fd={fd}", f"path={node}", "do-timestamp=true"]
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

    def __init__(self, height=1080, fps=30, sound=True, area=None, restore_token=None):
        self.width, self.height = SIZES.get(height, SIZES[1080])
        self.fps, self.want_sound, self.area = fps, sound, area
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
        if kind == "x11":
            threading.Thread(target=self._start_x11, args=(done,), daemon=True).start()
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

    def _common(self):
        """Prepare encoder and sound (slow the first time: tries what works)."""
        self.encoder, dev = video_encoder()
        if not self.encoder:
            raise core.TVError(_("ffmpeg here can't encode video"))
        self.sound = sound_source() if self.want_sound else None
        audio = ["-thread_queue_size", "1024", "-f", "pulse", "-sample_rate", "48000", "-channels", "2",
                 "-fragment_size", "4096", "-i", self.sound] if self.sound else SILENCE
        pre, vf, venc = encoder_args(self.encoder, dev, self.fps, self.height)
        out = ["-map", "0:v:0", "-map", "1:a:0", *venc, "-c:a", "aac", "-b:a", "160k", "-ar", "48000", "-ac", "2",
               "-f", "mpegts", "-mpegts_flags", "+resend_headers", "-muxdelay", "0", "-muxpreload", "0",
               "-flush_packets", "1", "pipe:1"]
        return pre, audio, vf, out

    def _start_x11(self, done):
        try:
            pre, audio, vf, out = self._common()
        except core.TVError as e:
            self._fail(done, str(e))
            return
        display = os.environ.get("DISPLAY", ":0")
        grab = ["-thread_queue_size", "64", "-f", "x11grab", "-draw_mouse", "1", "-framerate", str(self.fps)]
        if self.area:
            x, y, w, h = self.area
            grab += ["-video_size", f"{w - w % 2}x{h - h % 2}", "-i", f"{display}+{x},{y}"]
        else:
            grab += ["-i", display]
        fit = (f"scale={self.width}:{self.height}:force_original_aspect_ratio=decrease:flags=bicubic,"
               f"pad={self.width}:{self.height}:(ow-iw)/2:(oh-ih)/2:black,setsar=1,{vf}")
        self._run(done, [*pre, *grab, *audio, "-vf", fit, *out], stdin=subprocess.DEVNULL)

    def _start_wayland(self, fd, node, done):
        try:
            pre, audio, vf, out = self._common()
            self.frames = _PipeWireFrames(fd, node, self.width, self.height, self.fps)
        except Exception as e:  # noqa: BLE001 - GStreamer errors come in many types
            self._fail(done, str(e))
            return
        raw = ["-thread_queue_size", "64", "-f", "rawvideo", "-pix_fmt", "yuv420p",
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
                                         stdin=stdin, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
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
        return self._log[-1] if self._log else None

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
