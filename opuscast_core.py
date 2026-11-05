"""OpusCast core: play local video files on DLNA/UPnP TVs.

Standard library only (ffmpeg/ffprobe are used for subtitles when present), so the
CLI works on any Linux with Python 3.8+. The TV pulls the file over HTTP from a tiny
server here; we drive it with UPnP AVTransport (play/pause/seek/stop), RenderingControl
(volume, Samsung subtitles) and listen to its change notifications (GENA events).
"""
import hashlib
import html
import ipaddress
import json
import os
import re
import secrets
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.realpath(__file__)))
from opuscast_i18n import _  # noqa: E402

APP_NAME = "OpusCast"
APP_VERSION = "1.1"
APP_ID = "io.github.m1omg.OpusCast"
DEFAULT_PORT = 8765
RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
DLNA_FEATURES = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
LIVE_FEATURES = "DLNA.ORG_OP=00;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=01700000000000000000000000000000"  # no seeking
MIME = {
    ".mkv": "video/x-mkv", ".mp4": "video/mp4", ".m4v": "video/mp4",
    ".mov": "video/quicktime", ".avi": "video/x-msvideo", ".webm": "video/webm",
    ".ts": "video/vnd.dlna.mpeg-tts", ".m2ts": "video/vnd.dlna.mpeg-tts",
    ".mts": "video/vnd.dlna.mpeg-tts", ".mpg": "video/mpeg", ".mpeg": "video/mpeg",
    ".flv": "video/x-flv", ".wmv": "video/x-ms-asf", ".3gp": "video/3gpp",
}
VIDEO_EXTS = tuple(MIME)
AUDIO_MIME = {
    ".mp3": "audio/mpeg", ".flac": "audio/flac", ".m4a": "audio/mp4", ".m4b": "audio/mp4", ".alac": "audio/mp4",
    ".aac": "audio/aac", ".ogg": "audio/ogg", ".oga": "audio/ogg", ".opus": "audio/ogg", ".wav": "audio/wav",
    ".wma": "audio/x-ms-wma", ".aif": "audio/aiff", ".aiff": "audio/aiff", ".mka": "audio/x-matroska",
}
AUDIO_EXTS = tuple(AUDIO_MIME)
IMAGE_MIME = {
    ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png", ".gif": "image/gif", ".bmp": "image/bmp",
    ".webp": "image/webp", ".heic": "image/heic", ".heif": "image/heic", ".avif": "image/avif",
    ".tif": "image/tiff", ".tiff": "image/tiff",
}
IMAGE_EXTS = tuple(IMAGE_MIME)
# The same format under the names different TVs know it by. We send whichever the TV lists itself
# (Samsung says video/x-mkv, others video/x-matroska; audio/flac or audio/x-flac...).
MIME_ALIASES = (
    ("video/x-matroska", "video/x-mkv", "video/mkv"),
    ("video/mp4", "video/mpeg4"),
    ("video/x-msvideo", "video/avi", "video/x-avi", "video/divx"),
    ("video/quicktime", "video/mov"),
    ("video/mp2t", "video/mpeg", "video/vnd.dlna.mpeg-tts"),
    ("video/x-ms-wmv", "video/x-ms-asf"),
    ("audio/flac", "audio/x-flac"),
    ("audio/mp4", "audio/x-m4a", "audio/m4a", "audio/aac"),
    ("audio/aac", "audio/x-aac", "audio/vnd.dlna.adts", "audio/aacp"),
    ("audio/wav", "audio/x-wav", "audio/wave"),
    ("audio/ogg", "application/ogg", "audio/x-ogg"),
    ("audio/aiff", "audio/x-aiff"),
    ("image/jpeg", "image/jpg"),
)
_ALIASES = {m: group for group in MIME_ALIASES for m in group}


def app_dirs():
    """(settings folder, cache folder) where each system keeps them. XDG_CONFIG_HOME / XDG_CACHE_HOME
    still win anywhere, so tests and portable setups can point them elsewhere."""
    home = os.path.expanduser("~")
    if sys.platform == "win32" and not os.environ.get("XDG_CONFIG_HOME"):
        roaming = os.environ.get("APPDATA") or os.path.join(home, "AppData", "Roaming")
        local = os.environ.get("LOCALAPPDATA") or os.path.join(home, "AppData", "Local")
        return os.path.join(roaming, APP_NAME), os.path.join(local, APP_NAME, "cache")
    if sys.platform == "darwin" and not os.environ.get("XDG_CONFIG_HOME"):
        return (os.path.join(home, "Library", "Application Support", APP_NAME),
                os.path.join(home, "Library", "Caches", APP_NAME))
    return (os.path.join(os.environ.get("XDG_CONFIG_HOME") or os.path.join(home, ".config"), "opuscast"),
            os.path.join(os.environ.get("XDG_CACHE_HOME") or os.path.join(home, ".cache"), "opuscast"))


CONFIG_DIR, CACHE_DIR = app_dirs()
CONFIG_FILE = os.path.join(CONFIG_DIR, "config.json")
DESC_ERRORS = (OSError, ET.ParseError, ValueError)


class TVError(Exception):
    """A TV command that failed. code is the UPnP error code when the TV answered with one."""

    def __init__(self, message, code=None):
        super().__init__(message)
        self.code = code


BLANK_SRT = b"1\n99:00:00,000 --> 99:00:01,000\n \n"  # what the TV shows for "subtitles off"


# ---------------------------------------------------------------- helpers

def fmt_time(seconds):
    s = max(0, int(seconds or 0))
    return f"{s // 3600}:{s // 60 % 60:02d}:{s % 60:02d}"


def parse_time(text):
    """'1:02:03', '1:02:03.500' or '62:03' -> seconds; anything else -> None."""
    m = re.fullmatch(r"(?:(\d+):)?(\d{1,2}):(\d{1,2})(?:\.\d+)?", (text or "").strip())
    if not m:
        return None
    return int(m.group(1) or 0) * 3600 + int(m.group(2)) * 60 + int(m.group(3))


def _tag(xml_text, name):
    m = re.search(rf"<{name}>([^<]*)</{name}>", xml_text)
    return html.unescape(m.group(1)) if m else None


def local_ip_towards(host):
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.connect((host, 9))
        return s.getsockname()[0]


def have(tool):
    return shutil.which(tool) is not None


def media_kind(path):
    """'video', 'audio', 'image' or None, from the file name."""
    ext = os.path.splitext(path)[1].lower()
    if ext in MIME:
        return "video"
    if ext in AUDIO_MIME:
        return "audio"
    if ext in IMAGE_MIME:
        return "image"
    import mimetypes
    guess = (mimetypes.guess_type(path)[0] or "").split("/")[0]
    return {"video": "video", "audio": "audio", "image": "image"}.get(guess)


def mime_of(path, kind=None):
    ext = os.path.splitext(path)[1].lower()
    kind = kind or media_kind(path)
    if kind == "audio":
        return AUDIO_MIME.get(ext, "audio/mpeg")
    if kind == "image":
        return IMAGE_MIME.get(ext, "image/jpeg")
    return MIME.get(ext, "video/mp4")


def parse_sink(text):
    """ConnectionManager's Sink list ("http-get:*:video/mp4:*,...") -> {lower-case MIME: the TV's spelling}.
    A TV that takes anything lists "*"."""
    mimes = {}
    for entry in (text or "").split(","):
        parts = entry.strip().split(":")
        if len(parts) >= 3 and parts[0].strip().lower() in ("http-get", "*"):
            spelled = parts[2].split(";")[0].strip()
            if spelled:
                mimes.setdefault(spelled.lower(), spelled)
    return mimes


def pick_mime(mime, sink):
    """The name a TV knows our format by: ours if it lists it (or won't say), else the alias it lists."""
    if not sink or "*" in sink:
        return mime
    if mime.lower() in sink:
        return sink[mime.lower()]
    for alias in _ALIASES.get(mime, ()):
        if alias.lower() in sink:
            return sink[alias.lower()]
    return mime


def didl_time(seconds):
    ms = max(0, int(round(seconds * 1000)))
    return f"{ms // 3600000}:{ms // 60000 % 60:02d}:{ms // 1000 % 60:02d}.{ms % 1000:03d}"


def didl(url, title, mime, size=None, kind="video", features=DLNA_FEATURES, sub_url=None, meta=None):
    """The DIDL-Lite description the TV gets with the URL: what it is (video, song, photo), its format,
    and for songs the artist, album and cover. Subtitles are announced the Samsung (sec:), LG/Sony/
    others (pv:) and plain DLNA ways."""
    e, meta = html.escape, meta or {}
    upnp_class = {"audio": "object.item.audioItem.musicTrack", "image": "object.item.imageItem.photo"}.get(
        kind, "object.item.videoItem")
    res_attrs = f' size="{size}"' if size else ""
    if meta.get("duration"):
        res_attrs += f' duration="{didl_time(meta["duration"])}"'
    if meta.get("resolution"):
        res_attrs += f' resolution="{meta["resolution"]}"'
    extra = sub_caption = ""
    if sub_url:
        res_attrs += f' pv:subtitleFileType="srt" pv:subtitleFileUri="{e(sub_url)}"'
        sub_caption = (f'<res protocolInfo="http-get:*:text/srt:*">{e(sub_url)}</res>'
                       f'<sec:CaptionInfoEx sec:type="srt">{e(sub_url)}</sec:CaptionInfoEx>'
                       f'<sec:CaptionInfo sec:type="srt">{e(sub_url)}</sec:CaptionInfo>')
    if kind == "audio":
        artist = meta.get("artist") or meta.get("albumartist")
        if artist:
            extra += f"<dc:creator>{e(artist)}</dc:creator><upnp:artist>{e(artist)}</upnp:artist>"
        if meta.get("album"):
            extra += f"<upnp:album>{e(meta['album'])}</upnp:album>"
        if meta.get("track"):
            extra += f"<upnp:originalTrackNumber>{int(meta['track'])}</upnp:originalTrackNumber>"
        if meta.get("cover_url"):
            profile = ' dlna:profileID="JPEG_TN"' if meta.get("cover_mime") == "image/jpeg" else ""
            extra += f'<upnp:albumArtURI{profile}>{e(meta["cover_url"])}</upnp:albumArtURI>'
    return (
        '<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" '
        'xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" '
        'xmlns:dlna="urn:schemas-dlna-org:metadata-1-0/" '
        'xmlns:sec="http://www.sec.co.kr/" xmlns:pv="http://www.pv.com/pvns/">'
        f'<item id="0" parentID="-1" restricted="1"><dc:title>{e(title)}</dc:title>'
        f'<upnp:class>{upnp_class}</upnp:class>{extra}'
        f'<res protocolInfo="http-get:*:{mime}:{features}"{res_attrs}>{e(url)}</res>'
        f'{sub_caption}</item></DIDL-Lite>'
    )


def _ssdp(search_target, timeout):
    """M-SEARCH the LAN; returns the LOCATION URLs that answered."""
    msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\n"
           f"MX: 2\r\nST: {search_target}\r\n\r\n").encode()
    locations = []
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP) as s:
        s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2)
        s.settimeout(0.3)
        for _i in range(2):
            s.sendto(msg, ("239.255.255.250", 1900))
        end = time.time() + timeout
        while time.time() < end:
            try:
                data, _addr = s.recvfrom(65507)
            except socket.timeout:
                continue
            m = re.search(r"^location:\s*(\S+)", data.decode(errors="ignore"), re.I | re.M)
            if m and m.group(1) not in locations:
                locations.append(m.group(1))
    return locations


def _describe(location, timeout=3.0):
    """Parse a UPnP device description into a dict (any device type)."""
    with urllib.request.urlopen(location, timeout=timeout) as r:
        root = ET.fromstring(r.read())
    base = (root.findtext("{*}URLBase") or location).strip()
    devices = list(root.iterfind(".//{*}device"))
    types = [(d.findtext("{*}deviceType") or "").strip() for d in devices]
    renderers = [d for d in devices if "MediaRenderer" in (d.findtext("{*}deviceType") or "")]
    dev = renderers[0] if renderers else (devices[0] if devices else None)
    info = {"location": location, "host": urllib.parse.urlsplit(location).hostname, "types": types,
            "name": "", "manufacturer": "", "model": "", "services": {}}
    if dev is None:
        return info
    info["name"] = (dev.findtext("{*}friendlyName") or "").strip()
    info["manufacturer"] = (dev.findtext("{*}manufacturer") or "").strip()
    info["model"] = (dev.findtext("{*}modelName") or "").strip()
    if renderers:
        for svc in dev.iterfind(".//{*}service"):
            stype = (svc.findtext("{*}serviceType") or "").strip()
            ctl = (svc.findtext("{*}controlURL") or "").strip()
            ev = (svc.findtext("{*}eventSubURL") or "").strip()
            scpd = (svc.findtext("{*}SCPDURL") or "").strip()
            for short in ("AVTransport", "RenderingControl", "ConnectionManager"):
                if f":service:{short}:" in stype and short not in info["services"]:
                    info["services"][short] = {"type": stype, "control": urllib.parse.urljoin(base, ctl),
                                               "event": urllib.parse.urljoin(base, ev) if ev else None,
                                               "scpd": urllib.parse.urljoin(base, scpd) if scpd else None}
    return info


def _caption_entries(xml_text):
    """captionFileInfo entries from Samsung's caption XML -> list of attribute dicts."""
    out = []
    for attrs in re.findall(r"<captionFileInfo ([^>]*?)/?>", xml_text or ""):
        out.append({k: html.unescape(v) for k, v in re.findall(r'(\w+)="([^"]*)"', attrs)})
    return out


def parse_last_change(body):
    """A GENA NOTIFY body -> {variable: value} of what changed.
    {} means the TV said "something changed" without details; None means not a LastChange event."""
    try:
        root = ET.fromstring(body)
    except ET.ParseError:
        return None
    lc = root.find(".//{*}LastChange")
    if lc is None:
        return None
    text = (lc.text or "").strip()
    if not text:
        return {}
    try:
        event = ET.fromstring(text)
    except ET.ParseError:
        return {}
    changes = {}
    for inst in event.iterfind("{*}InstanceID"):
        for child in inst:
            name = child.tag.split("}")[-1]
            if name in ("Volume", "Mute") and child.get("channel", "Master") != "Master":
                continue
            changes[name] = child.get("val", "")
    return changes


def caption_uris(xml_text):
    return [e.get("uri") for e in _caption_entries(xml_text) if e.get("uri")]


# ---------------------------------------------------------------- the TV

class TV:
    """A DLNA media renderer."""

    def __init__(self, info):
        self.location = info["location"]
        self.host = info["host"]
        self.name = info["name"] or "TV"
        self.manufacturer = info["manufacturer"]
        self.model = info["model"]
        self.services = info["services"]
        self._actions = {}
        self._sink = None

    def __repr__(self):
        return f"TV({self.name!r} at {self.host})"

    @classmethod
    def from_location(cls, location, timeout=3.0):
        info = _describe(location, timeout)
        if "AVTransport" not in info["services"]:
            raise TVError(_("this device can't play media"))
        return cls(info)

    def soap(self, service, action, args=(), timeout=6.0):
        if service not in self.services:
            raise TVError(_("the TV doesn't support {what}").format(what=service))
        stype, url = self.services[service]["type"], self.services[service]["control"]
        body = "".join(f"<{k}>{html.escape(str(v))}</{k}>" for k, v in args)
        envelope = (
            '<?xml version="1.0" encoding="utf-8"?>'
            '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
            's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>'
            f'<u:{action} xmlns:u="{stype}">{body}</u:{action}></s:Body></s:Envelope>'
        )
        req = urllib.request.Request(url, data=envelope.encode(), headers={
            "Content-Type": 'text/xml; charset="utf-8"', "SOAPACTION": f'"{stype}#{action}"'})
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.read().decode(errors="ignore")
        except urllib.error.HTTPError as e:
            body = e.read().decode(errors="ignore")
            detail, code = _tag(body, "errorDescription"), (_tag(body, "errorCode") or "").strip()
            raise TVError(_("the TV refused {action}: {detail}").format(action=action, detail=detail or e.reason),
                          int(code) if code.isdigit() else None) from None
        except OSError as e:
            raise TVError(_("can't reach the TV ({error})").format(error=e)) from None

    def sink(self):
        """The formats the TV says it plays, {lower-case MIME: its spelling}, or None if it won't say."""
        if self._sink is None:
            try:
                text = _tag(self.soap("ConnectionManager", "GetProtocolInfo", timeout=3), "Sink") or ""
            except TVError:
                return None  # ask again next time
            self._sink = parse_sink(text)
        return self._sink or None

    def cached_sink(self):
        """sink() without asking the TV: None until it has been asked."""
        return self._sink or None

    def plays_video(self):
        """True / False from the TV's supported formats, None if it won't say."""
        sink = self.sink()
        return None if sink is None else "*" in sink or any(m.startswith("video/") for m in sink)

    def plays_audio(self):
        sink = self.sink()
        return None if sink is None else "*" in sink or any(m.startswith("audio/") for m in sink)

    def accepts(self, mime):
        """True / False: does the TV list this format (under any of its names)? None if it won't say."""
        sink = self.sink()
        if not sink:
            return None
        return "*" in sink or any(m.lower() in sink for m in (mime,) + _ALIASES.get(mime, ()))

    def pick_mime(self, mime):
        return pick_mime(mime, self.sink())

    # transport
    def set_media(self, url, title, mime, size, sub_url=None, kind="video", features=DLNA_FEATURES, meta=None):
        self.soap("AVTransport", "SetAVTransportURI", [
            ("InstanceID", 0), ("CurrentURI", url),
            ("CurrentURIMetaData", didl(url, title, mime, size, kind, features, sub_url, meta))])

    def play(self):
        self.soap("AVTransport", "Play", [("InstanceID", 0), ("Speed", 1)])

    def pause(self):
        self.soap("AVTransport", "Pause", [("InstanceID", 0)])

    def stop(self, timeout=6.0):
        self.soap("AVTransport", "Stop", [("InstanceID", 0)], timeout=timeout)

    def seek(self, seconds):
        s = max(0, int(seconds))
        target = f"{s // 3600:02d}:{s // 60 % 60:02d}:{s % 60:02d}"
        try:
            self.soap("AVTransport", "Seek", [("InstanceID", 0), ("Unit", "REL_TIME"), ("Target", target)])
        except TVError as e:
            if e.code is None:  # no answer at all: trying the other way won't help
                raise
            # some renderers only take ABS_TIME (the same thing for a single file)
            self.soap("AVTransport", "Seek", [("InstanceID", 0), ("Unit", "ABS_TIME"), ("Target", target)])

    def state(self):
        """PLAYING, PAUSED_PLAYBACK, STOPPED, TRANSITIONING or NO_MEDIA_PRESENT."""
        return _tag(self.soap("AVTransport", "GetTransportInfo", [("InstanceID", 0)], timeout=4),
                    "CurrentTransportState") or "UNKNOWN"

    def position(self):
        """(position, duration, current URI) - times in seconds or None."""
        r = self.soap("AVTransport", "GetPositionInfo", [("InstanceID", 0)], timeout=4)
        return parse_time(_tag(r, "RelTime")), parse_time(_tag(r, "TrackDuration")), _tag(r, "TrackURI")

    # volume, 0-100
    def get_volume(self):
        v = _tag(self.soap("RenderingControl", "GetVolume", [("InstanceID", 0), ("Channel", "Master")], timeout=4),
                 "CurrentVolume")
        return int(v) if v and v.isdigit() else None

    def set_volume(self, value):
        self.soap("RenderingControl", "SetVolume",
                  [("InstanceID", 0), ("Channel", "Master"), ("DesiredVolume", int(value))])

    def actions(self, service):
        """Names of the actions the TV offers for a service (read once from its SCPD)."""
        if service not in self._actions:
            url = (self.services.get(service) or {}).get("scpd")
            names = set()
            if url:
                try:
                    with urllib.request.urlopen(url, timeout=4) as r:
                        root = ET.fromstring(r.read())
                except DESC_ERRORS:
                    return set()  # try again next time
                names = {(a.findtext("{*}name") or "").strip() for a in root.iterfind(".//{*}action")}
            self._actions[service] = names
        return self._actions[service]

    # subtitles (Samsung extension)
    def can_switch_subtitles(self):
        return "X_ControlCaption" in self.actions("RenderingControl")

    def caption_state(self):
        """(available, enabled) subtitle files the TV knows about, as attribute dicts."""
        r = self.soap("RenderingControl", "X_GetCaptionState", [("InstanceID", 0)], timeout=4)
        return _caption_entries(_tag(r, "Captions")), _caption_entries(_tag(r, "EnabledCaptions"))

    def show_caption(self, media_url, caption_url, name):
        """Fetch and show this subtitle file now. We only ever switch files - "off" is an empty file -
        because Operation=Disable also turns off the TV's own subtitle setting, which the PC can't
        turn back on (afterwards Enable is accepted but nothing is drawn)."""
        resource = "dlna" + media_url[len("http"):] if media_url.startswith("http") else media_url
        self.soap("RenderingControl", "X_ControlCaption", [
            ("InstanceID", 0), ("Operation", "Enable"), ("Name", name), ("ResourceURI", resource),
            ("CaptionURI", caption_url), ("CaptionType", "SRT"), ("Language", ""), ("Encoding", "UTF-8")])

    # change notifications (GENA)
    def subscribe(self, service, callback_url, timeout=1800):
        """Returns (SID, seconds until it must be renewed)."""
        return self._gena(service, "SUBSCRIBE", {"CALLBACK": f"<{callback_url}>", "NT": "upnp:event",
                                                 "TIMEOUT": f"Second-{timeout}"})

    def renew(self, service, sid, timeout=1800):
        return self._gena(service, "SUBSCRIBE", {"SID": sid, "TIMEOUT": f"Second-{timeout}"})

    def unsubscribe(self, service, sid):
        try:
            self._gena(service, "UNSUBSCRIBE", {"SID": sid})
        except TVError:
            pass

    def _gena(self, service, method, headers):
        svc = self.services.get(service) or {}
        if not svc.get("event"):
            raise TVError(_("the TV doesn't support {what}").format(what=f"{service} events"))
        req = urllib.request.Request(svc["event"], method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=5) as r:
                m = re.search(r"(\d+)", r.headers.get("TIMEOUT") or "")
                return r.headers.get("SID"), int(m.group(1)) if m else 300
        except OSError as e:
            raise TVError(_("can't reach the TV ({error})").format(error=e)) from None


def _load_tvs(locations):
    def load(loc):
        try:
            return TV.from_location(loc)
        except (TVError,) + DESC_ERRORS:
            return None
    with ThreadPoolExecutor(max_workers=8) as pool:
        found = [tv for tv in pool.map(load, locations) if tv]
    return sorted({tv.location: tv for tv in found}.values(), key=lambda t: t.name.lower())


def discover(timeout=3.0):
    """Find DLNA renderers (TVs) on the LAN."""
    return _load_tvs(_ssdp(RENDERER, timeout))


# where common renderers describe themselves, for a TV that doesn't answer searches
KNOWN_DESCRIPTIONS = ("http://{h}:9197/dmr",  # Samsung
                      "http://{h}:52323/dmr.xml",  # Sony
                      "http://{h}:1400/xml/device_description.xml")  # Sonos


def _ssdp_unicast(host, timeout=2.0):
    """Ask one device directly (UPnP 1.1 unicast M-SEARCH) - finds a renderer at a typed address
    whatever its brand. Returns LOCATION URLs."""
    msg = (f"M-SEARCH * HTTP/1.1\r\nHOST: {host}:1900\r\nMAN: \"ssdp:discover\"\r\n"
           f"ST: {RENDERER}\r\n\r\n").encode()
    found = []
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.settimeout(0.3)
            s.sendto(msg, (host, 1900))
            end = time.time() + timeout
            while time.time() < end and not found:
                try:
                    data, addr = s.recvfrom(65507)
                except socket.timeout:
                    s.sendto(msg, (host, 1900))
                    continue
                m = re.search(r"^location:\s*(\S+)", data.decode(errors="ignore"), re.I | re.M)
                if m and addr[0] == host:
                    found.append(m.group(1))
    except OSError:
        pass
    return found


def find_tv(location=None, host=None, timeout=3.0):
    """Remembered location first (fast), then SSDP; for a typed address also ask the device itself
    and try where common brands put their description."""
    if location:
        try:
            tv = TV.from_location(location, timeout=2.5)
            if not host or tv.host == host:
                return tv
        except (TVError,) + DESC_ERRORS:
            pass
    tvs = [tv for tv in discover(timeout) if not host or tv.host == host]
    if tvs:
        return tvs[0]
    if host:
        for loc in _ssdp_unicast(host) + [u.format(h=host) for u in KNOWN_DESCRIPTIONS]:
            try:
                return TV.from_location(loc, timeout=2.5)
            except (TVError,) + DESC_ERRORS:
                pass
    return None


# ---------------------------------------------------------------- network scan

OUI_FILES = ("/usr/share/hwdata/oui.txt", "/usr/share/ieee-data/oui.txt",
             "/var/lib/ieee-data/oui.txt", "/usr/share/misc/oui.txt")
TV_BRANDS = ("samsung", "lg electronics", "sony", "hisense", "tcl", "philips", "tp vision", "panasonic", "vizio",
             "xiaomi", "sharp", "toshiba", "roku", "skyworth", "grundig", "vestel", "funai")
KIND_ORDER = {"tv": 0, "speaker": 1, "router": 2, "upnp": 3, "unknown": 4, "pc": 5}


def _run_text(cmd, timeout=4):
    """A system tool's output ('' if it isn't there), without a console window popping up on Windows."""
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, errors="replace",
                              **NO_WINDOW).stdout or ""
    except (OSError, subprocess.SubprocessError):
        return ""


NO_WINDOW = {"creationflags": 0x08000000} if sys.platform == "win32" else {}  # CREATE_NO_WINDOW


def _default_gateway():
    if sys.platform == "win32":
        m = re.search(r"^\s*0\.0\.0\.0\s+0\.0\.0\.0\s+(\d+\.\d+\.\d+\.\d+)", _run_text(["route", "print", "-4"]), re.M)
        return m.group(1) if m else None
    if sys.platform == "darwin":
        m = re.search(r"gateway:\s*(\d+\.\d+\.\d+\.\d+)", _run_text(["route", "-n", "get", "default"]))
        return m.group(1) if m else None
    try:
        with open("/proc/net/route") as f:
            for line in f.readlines()[1:]:
                p = line.split()
                if p[1] == "00000000" and int(p[3], 16) & 2:
                    return socket.inet_ntoa(struct.pack("<L", int(p[2], 16)))
    except (OSError, ValueError, IndexError):
        pass
    return None


def _local_net():
    """(this PC's LAN IP, prefix length)."""
    try:
        ip = local_ip_towards(_default_gateway() or "192.168.0.1")
    except OSError:
        return None, 24
    prefix = 24
    if sys.platform == "darwin":  # "inet 192.168.1.5 netmask 0xffffff00 ..."
        m = re.search(rf"inet {re.escape(ip)} netmask 0x([0-9a-f]+)", _run_text(["ifconfig"]))
        return ip, bin(int(m.group(1), 16)).count("1") if m else prefix
    if sys.platform == "win32":  # the mask shows a few lines below the address
        out = _run_text(["ipconfig"])
        i = out.find(ip)
        m = re.search(r"(\d+\.\d+\.\d+\.\d+)", out[i + len(ip):i + len(ip) + 200]) if i >= 0 else None
        if m:
            try:
                prefix = ipaddress.ip_network(f"0.0.0.0/{m.group(1)}").prefixlen
            except ValueError:
                pass
        return ip, prefix
    try:
        for iface in json.loads(_run_text(["ip", "-j", "-4", "addr", "show"]) or "[]"):
            for a in iface.get("addr_info", []):
                if a.get("local") == ip:
                    prefix = int(a.get("prefixlen", 24))
    except (ValueError, TypeError):
        pass
    return ip, prefix


def _wake_neighbours(ip, prefix):
    """Send an empty UDP packet to every address, so the kernel ARPs them all."""
    net = ipaddress.ip_network(f"{ip}/{max(prefix, 24)}", strict=False)
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.setblocking(False)
        for host in net.hosts():
            try:
                s.sendto(b"", (str(host), 9))
            except OSError:
                pass


def _arp_table():
    table = {}
    if sys.platform in ("win32", "darwin"):
        # Windows: "  192.168.1.1   aa-bb-cc-dd-ee-ff   dynamic"; macOS: "? (192.168.1.1) at a:b:c:d:e:f on en0"
        for ip, mac in re.findall(r"(\d+\.\d+\.\d+\.\d+)\)?\s+(?:at\s+)?([0-9a-fA-F]{1,2}(?:[:-][0-9a-fA-F]{1,2}){5})",
                                  _run_text(["arp", "-a"] if sys.platform == "win32" else ["arp", "-an"])):
            mac = ":".join(f"{int(x, 16):02x}" for x in re.split("[:-]", mac))
            if mac not in ("ff:ff:ff:ff:ff:ff", "00:00:00:00:00:00") and not ip.startswith(("224.", "239.", "255.")):
                table[ip] = mac
        return table
    try:
        with open("/proc/net/arp") as f:
            for line in f.readlines()[1:]:
                p = line.split()
                if len(p) >= 4 and p[2] != "0x0" and p[3] != "00:00:00:00:00:00":
                    table[p[0]] = p[3].lower()
    except OSError:
        pass
    return table


def _vendors(macs):
    prefixes = {m[:8].replace(":", "-").upper() for m in macs}
    found = {}
    for path in OUI_FILES:
        try:
            with open(path, encoding="utf-8", errors="ignore") as f:
                for line in f:
                    if "(hex)" in line and line[:8].upper() in prefixes:
                        found[line[:8].upper()] = line.split("(hex)", 1)[1].strip()
            break
        except OSError:
            continue
    return {m: found.get(m[:8].replace(":", "-").upper()) for m in macs}


def _reverse_dns(hosts, timeout):
    names = {}

    def look(h):
        try:
            name = socket.gethostbyaddr(h)[0]
        except OSError:
            return
        if name and not name.startswith("_") and name != h:
            names[h] = name.split(".")[0]
    threads = [threading.Thread(target=look, args=(h,), daemon=True) for h in hosts]
    for t in threads:
        t.start()
    deadline = time.time() + timeout
    for t in threads:
        t.join(max(0.0, deadline - time.time()))
    return dict(names)


def PHILIPS_HINT():  # noqa: N802 - a translated constant
    return _("Philips TV: turn on Digital Media Renderer - DMR (All Settings > Wireless and Networks > "
             "Wired or Wi-Fi), then scan again")


def scan_network(timeout=3.0):
    """Every device we can see on the LAN, with whether it can play video.

    Returns a list of dicts: ip, mac, vendor, name, detail, kind (tv/speaker/router/
    upnp/unknown/pc), can_play (bool), note, tv (TV object or None).
    """
    my_ip, prefix = _local_net()
    gateway = _default_gateway()
    if my_ip:
        _wake_neighbours(my_ip, prefix)
    locations = _ssdp("ssdp:all", timeout)

    def describe(loc):
        try:
            return _describe(loc)
        except DESC_ERRORS:
            return None
    with ThreadPoolExecutor(max_workers=8) as pool:
        descs = [d for d in pool.map(describe, locations) if d]
    arp = _arp_table()
    hosts = set(arp) | {d["host"] for d in descs} | ({my_ip} if my_ip else set())
    vendors = _vendors(list(arp.values()))
    hostnames = _reverse_dns(hosts, 1.5)

    devices = []
    for h in hosts:
        mine = [d for d in descs if d["host"] == h]
        mac = arp.get(h)
        vendor = vendors.get(mac) if mac else None
        private = bool(mac) and int(mac[1], 16) & 2 != 0
        dev = {"ip": h, "mac": mac, "vendor": vendor, "name": hostnames.get(h), "detail": "",
               "kind": "unknown", "can_play": False, "note": "", "tv": None}
        renderer = next((d for d in mine if "AVTransport" in d["services"]), None)
        if h == my_ip:
            dev.update(kind="pc", name=_("This computer"), note=_("This computer"), detail=socket.gethostname())
        elif renderer:
            tv = TV(renderer)
            video = tv.plays_video()
            dev.update(tv=tv, name=tv.name, detail=" ".join(x for x in (tv.manufacturer, tv.model) if x))
            if video is False:
                dev.update(kind="speaker", can_play=True, note=_("Plays music only"))
            else:
                dev.update(kind="tv", can_play=True, note=_("Can play video"))
        elif h == gateway or any("InternetGatewayDevice" in t for d in mine for t in d["types"]):
            named = next((d for d in mine if d["name"]), None)
            dev.update(kind="router", name=(named["name"] if named else None) or dev["name"] or _("Router"),
                       note=_("Router - can't play video"), detail=(named or {}).get("manufacturer") or "")
        elif mine:
            d = next((d for d in mine if d["name"]), mine[0])
            server = any("MediaServer" in t for t in d["types"])
            brand = " ".join([vendor or ""] + [x["manufacturer"] for x in mine]).lower()
            note = _("Media server / NAS - can't play video") if server else _("Network device - can't play video")
            if not server and ("philips" in brand or "tp vision" in brand):
                note = PHILIPS_HINT()
            elif not server and any("dial" in t.lower() for x in mine for t in x["types"]):
                note = _("Smart TV with its media player (DLNA) off - look for DLNA, media sharing or "
                         "DMR in its network settings")
            dev.update(kind="upnp", name=d["name"] or dev["name"], note=note,
                       detail=" ".join(x for x in (d["manufacturer"], d["model"]) if x))
        else:
            v = (vendor or "").lower()
            if "philips" in v or "tp vision" in v:
                dev["note"] = PHILIPS_HINT()
            elif "google" in v or "nvidia" in v:
                dev["note"] = _("Google Cast device (Chromecast, Google TV, Shield) - it doesn't take DLNA, "
                                "which OpusCast uses")
            elif vendor and any(b in v for b in TV_BRANDS):
                dev["note"] = _("No casting support found - if this is a TV, turn it on and scan again")
            elif private:
                dev["note"] = _("Private Wi-Fi address (probably a phone or laptop) - can't play video")
            else:
                dev["note"] = _("No casting support found")
        if not dev["detail"]:
            dev["detail"] = vendor or (_("Private Wi-Fi address") if private else "")
        dev["name"] = dev["name"] or vendor or _("Unknown device")
        if dev["detail"] == dev["name"]:
            dev["detail"] = ""
        devices.append(dev)
    devices.sort(key=lambda d: (KIND_ORDER[d["kind"]], ipaddress.ip_address(d["ip"])))
    return devices


# ---------------------------------------------------------------- subtitles

SUB_EXTS = (".srt", ".ass", ".ssa", ".vtt", ".sub", ".smi", ".sami")
SUB_DIRS = ("subs", "sub", "subtitles", "titulky", "subtitle")
TEXT_SUB_CODECS = {"subrip", "srt", "ass", "ssa", "webvtt", "mov_text", "text", "microdvd", "sami",
                   "subviewer", "subviewer1", "jacosub", "mpl2", "realtext", "stl", "pjs", "vplayer"}
LANGUAGES = {
    "Slovak": ("sk", "slk", "slo", "slovak", "slovensky", "slovencina"),
    "Czech": ("cs", "cz", "cze", "ces", "czech", "cesky", "cestina"),
    "English": ("en", "eng", "english"), "German": ("de", "ger", "deu", "german"),
    "Hungarian": ("hu", "hun", "hungarian"), "Polish": ("pl", "pol", "polish"),
    "French": ("fr", "fre", "fra", "french"), "Spanish": ("es", "spa", "spanish"),
    "Italian": ("it", "ita", "italian"), "Russian": ("ru", "rus", "russian"),
    "Ukrainian": ("uk", "ukr", "ukrainian"), "Portuguese": ("pt", "por", "portuguese"),
    "Dutch": ("nl", "dut", "nld", "dutch"), "Croatian": ("hr", "hrv", "croatian"),
    "Serbian": ("sr", "srp", "serbian"), "Slovenian": ("sl", "slv", "slovenian"),
    "Romanian": ("ro", "rum", "ron", "romanian"), "Japanese": ("ja", "jpn", "japanese"),
    "Chinese": ("zh", "chi", "zho", "chinese"), "Korean": ("ko", "kor", "korean"),
}
_LANG_CODES = {code: name for name, codes in LANGUAGES.items() for code in codes}


def language_name(code):
    """'sk' / 'slk' / 'Slovak' -> 'Slovak' (English; translate with _())."""
    return _LANG_CODES.get((code or "").lower().strip(), "")


def _lang_from_filename(stem):
    for token in reversed(re.split(r"[.\-_ \[\]()]+", stem.lower())):
        if token in _LANG_CODES:
            return _LANG_CODES[token]
    return ""


def list_subtitles(video):
    """Subtitles for a video: files next to it and tracks inside it.

    Each entry: id, kind ('file'/'track'), path or index, lang (English name or ''),
    label, ok (can be sent to the TV), why (reason if not)."""
    folder = os.path.dirname(video)
    base = os.path.splitext(os.path.basename(video))[0].lower()
    entries = []
    try:
        names = os.listdir(folder)
    except OSError:
        names = []
    videos_here = [n for n in names if n.lower().endswith(VIDEO_EXTS)]
    dirs = [folder] + [os.path.join(folder, n) for n in names
                       if n.lower() in SUB_DIRS and os.path.isdir(os.path.join(folder, n))]
    for d in dirs:
        try:
            files = sorted(os.listdir(d))
        except OSError:
            continue
        for f in files:
            stem, ext = os.path.splitext(f)
            if ext.lower() not in SUB_EXTS:
                continue
            matches = stem.lower().startswith(base)
            if d == folder and len(videos_here) > 1 and not matches:
                continue  # several videos here: only take subtitles named after this one
            path = os.path.join(d, f)
            lang = _lang_from_filename(stem[len(base):] if matches else stem)
            entry = {"id": "file:" + path, "kind": "file", "path": path, "lang": lang,
                     "label": f"{_(lang)} · {f}" if lang else f, "ok": True, "why": ""}
            if ext.lower() == ".sub" and os.path.exists(os.path.join(d, stem + ".idx")):
                entry.update(ok=False, why=_("picture-based - can't be sent to the TV"))
            elif ext.lower() != ".srt" and not have("ffmpeg"):
                entry.update(ok=False, why=_("needs ffmpeg to convert"))
            entries.append(entry)
    if have("ffprobe"):
        try:
            out = subprocess.run(
                ["ffprobe", "-v", "error", "-select_streams", "s", "-show_entries",
                 "stream=index,codec_name:stream_tags=language,title:stream_disposition=forced", "-of", "json", video],
                capture_output=True, text=True, timeout=30).stdout
            streams = json.loads(out or "{}").get("streams", [])
        except (OSError, ValueError, subprocess.SubprocessError):
            streams = []
        for n, s in enumerate(streams, 1):
            tags = s.get("tags", {})
            lang = language_name(tags.get("language", ""))
            title = (tags.get("title") or "").strip()
            parts = [_(lang) if lang else _("Track {n}").format(n=n), _("built-in")]
            if s.get("disposition", {}).get("forced"):
                parts.append(_("forced"))
            if title and len(title) <= 40 and title.lower() != lang.lower():
                parts.append(title)
            ok = s.get("codec_name") in TEXT_SUB_CODECS
            entries.append({"id": f"track:{s['index']}", "kind": "track", "index": s["index"], "lang": lang,
                            "label": " · ".join(parts), "ok": ok,
                            "why": "" if ok else _("picture-based - can't be sent to the TV")})
    return entries


def subtitle_file_entry(path):
    stem = os.path.splitext(os.path.basename(path))[0]
    lang = _lang_from_filename(stem)
    name = os.path.basename(path)
    return {"id": "file:" + path, "kind": "file", "path": path, "lang": lang,
            "label": f"{_(lang)} · {name}" if lang else name, "ok": True, "why": ""}


def _decode_text(raw):
    if raw.startswith((b"\xff\xfe", b"\xfe\xff")):
        return raw.decode("utf-16")
    for enc in ("utf-8-sig", "cp1250"):  # cp1250: the usual Slovak/Czech subtitle encoding
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            continue
    return raw.decode("latin-1")


def _ffmpeg_srt(args):
    if not have("ffmpeg"):
        raise TVError(_("converting these subtitles needs ffmpeg"))
    r = subprocess.run(["ffmpeg", "-v", "error", "-nostdin", *args, "-c:s", "srt", "-f", "srt", "-"],
                       capture_output=True, timeout=300)
    if r.returncode != 0 or not r.stdout.strip():
        raise TVError(_("couldn't read these subtitles ({error})").format(
            error=r.stderr.decode(errors="ignore").strip()[-200:] or "ffmpeg"))
    return r.stdout


def prepare_subtitle(entry, video):
    """The subtitle as UTF-8 SRT bytes. Built-in tracks are extracted once and cached."""
    if entry["kind"] == "file":
        path = entry["path"]
        try:
            with open(path, "rb") as f:
                text = _decode_text(f.read())
        except OSError as e:
            raise TVError(_("can't read the subtitle file ({error})").format(error=e)) from None
        if path.lower().endswith(".srt"):
            text = text.replace("\r\n", "\n").replace("\r", "\n").strip("﻿")
            return (text.rstrip("\n") + "\n").encode("utf-8")
        with tempfile.NamedTemporaryFile("w", suffix=os.path.splitext(path)[1], encoding="utf-8",
                                         delete=False) as tmp:
            tmp.write(text)
        try:
            return _ffmpeg_srt(["-i", tmp.name])
        finally:
            os.unlink(tmp.name)
    st = os.stat(video)
    key = hashlib.sha1(f"{video}|{st.st_mtime_ns}|{st.st_size}|{entry['index']}".encode()).hexdigest()
    cached = os.path.join(CACHE_DIR, "subs", key + ".srt")
    try:
        with open(cached, "rb") as f:
            return f.read()
    except OSError:
        pass
    data = _ffmpeg_srt(["-i", video, "-map", f"0:{entry['index']}"])
    try:
        os.makedirs(os.path.dirname(cached), exist_ok=True)
        with open(cached + ".tmp", "wb") as f:
            f.write(data)
        os.replace(cached + ".tmp", cached)
    except OSError:
        pass
    return data


def auto_subtitle(entries, preferred=("Slovak", "Czech", "English")):
    """Best subtitle to use when asked for 'auto': preferred language, files before tracks."""
    usable = [e for e in entries if e["ok"]]
    for lang in preferred:
        for e in usable:
            if e["lang"] == lang:
                return e
    return usable[0] if usable else None


def _duration_ms(path):
    if not have("ffprobe"):
        return None
    try:
        out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path],
                             capture_output=True, text=True, timeout=20).stdout
        return int(float(out) * 1000)
    except (OSError, ValueError, subprocess.SubprocessError):
        return None


# ---------------------------------------------------------------- file server

class _Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def handle(self):
        try:
            super().handle()
        except (ConnectionError, TimeoutError):
            pass  # the TV drops connections all the time (seeking, buffering)

    def do_HEAD(self):
        self._serve(send_body=False)

    def do_GET(self):
        self._serve(send_body=True)

    def do_NOTIFY(self):  # change notifications from the TV
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else b""
        self.send_response(200)
        self.send_header("Content-Length", "0")
        self.end_headers()
        callback = self.server.on_event
        if callback:
            try:
                callback(urllib.parse.urlsplit(self.path).path.rsplit("/", 1)[-1], self.headers.get("SID"),
                         body.decode("utf-8", "ignore"))
            except Exception:  # noqa: BLE001 - never let a bad event kill the server
                pass

    def _serve(self, send_body):
        media = self.server.media
        path = urllib.parse.urlsplit(self.path).path
        if media and (path in media["subs"] or path in media["extras"]):
            mime, data = ("text/srt; charset=utf-8", media["subs"][path]) if path in media["subs"] \
                else media["extras"][path]
            self.send_response(200)
            self.send_header("Content-Type", mime)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("transferMode.dlna.org", "Interactive")
            self.end_headers()
            if send_body:
                self.wfile.write(data)
            return
        if not media or path != media["url_path"]:
            self.send_error(404)
            return
        media["requested"].set()
        mime = media.get("tv_mime") or media["mime"]
        # the TV may ask for a transfer mode (Sony does); answer in kind
        transfer = self.headers.get("transferMode.dlna.org") or media["transfer"]
        if media["live"] is not None:
            self._serve_live(media, mime, transfer, send_body)
            return
        size = media["size"]
        try:
            rng = _parse_range(self.headers.get("Range"), size)
        except ValueError:
            self.send_response(416)
            self.send_header("Content-Range", f"bytes */{size}")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        start, end = rng or (0, size - 1)
        self.send_response(206 if rng else 200)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(end - start + 1))
        self.send_header("Accept-Ranges", "bytes")
        if rng:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.send_header("transferMode.dlna.org", transfer)
        self.send_header("contentFeatures.dlna.org", media["features"])
        # Samsung TVs ask for these; answering like other DLNA servers do keeps their player happy
        if self.headers.get("getCaptionInfo.sec") and media.get("sub_path"):
            here = self.connection.getsockname()[0]
            self.send_header("CaptionInfo.sec", f"http://{here}:{self.server.server_address[1]}{media['sub_path']}")
        if self.headers.get("getMediaInfo.sec") and media["kind"] != "image":
            ms = _media_duration(media)
            if ms:
                self.send_header("MediaInfo.sec", f"SEC_Duration={ms};")
        self.end_headers()
        if not send_body:
            return
        if media["data"] is not None:
            self.wfile.write(media["data"][start:end + 1])
            return
        try:
            with open(media["file"], "rb") as f:
                self.connection.sendfile(f, offset=start, count=end - start + 1)
        except OSError:
            pass

    def _serve_live(self, media, mime, transfer, send_body):
        """A stream with no end and no length: chunked for HTTP/1.1 TVs, until the connection closes for
        HTTP/1.0 ones. Ranges are ignored - a live stream starts now wherever the TV asks."""
        chunked = self.request_version == "HTTP/1.1"
        self.send_response(200)
        self.send_header("Content-Type", mime)
        self.send_header("Cache-Control", "no-cache")
        self.send_header("transferMode.dlna.org", transfer)
        self.send_header("contentFeatures.dlna.org", media["features"])
        if chunked:
            self.send_header("Transfer-Encoding", "chunked")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True
        if not send_body:
            return
        client = media["live"].open()
        try:
            while True:
                data = client.read(timeout=5.0)
                if data is None:
                    continue  # nothing new yet (the encoder waits for the screen to change)
                if not data:
                    break  # the stream ended
                self.wfile.write(b"%X\r\n%s\r\n" % (len(data), data) if chunked else data)
            if chunked:
                self.wfile.write(b"0\r\n\r\n")
        except OSError:
            pass  # the TV went away
        finally:
            client.close()


_duration_lock = threading.Lock()


def _media_duration(media):
    with _duration_lock:
        if "duration_ms" not in media:
            known = media["meta"].get("duration")
            media["duration_ms"] = int(known * 1000) if known else _duration_ms(media["file"]) if media["file"] else None
        return media["duration_ms"]


def _parse_range(header, size):
    if not header or not header.startswith("bytes="):
        return None
    first, _sep, last = header[6:].split(",")[0].strip().partition("-")
    if first == "":
        start, end = max(0, size - int(last)), size - 1
    else:
        start, end = int(first), min(int(last) if last else size - 1, size - 1)
    if start > end:
        raise ValueError
    return start, end


class MediaServer:
    """Serves one file (plus its subtitle) at a time to the TV, and receives the TV's events.
    Tries DEFAULT_PORT, then the next few."""

    def __init__(self, port=DEFAULT_PORT):
        last_err = None
        for p in list(range(port, port + 10)) + [0]:
            try:
                self.httpd = ThreadingHTTPServer(("", p), _Handler)
                break
            except OSError as e:
                last_err = e
        else:
            raise TVError(_("can't open a port for the video server ({error})").format(error=last_err))
        self.httpd.daemon_threads = True
        self.httpd.media = None
        self.httpd.on_event = None
        self.port = self.httpd.server_address[1]
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def publish(self, path=None, subtitle=None, *, kind=None, data=None, mime=None, title=None, live=None,
                features=None, meta=None):
        """Serve one thing to the TV: a file, bytes (a converted photo) or a live stream (the desktop).
        A video can come with a subtitle (SRT bytes) announced to the TV with it."""
        token = secrets.token_hex(4)
        if live is not None:
            kind, mime, size, ext = kind or "video", mime or "video/mpeg", None, ".ts"
            features = features or LIVE_FEATURES
        else:
            kind = kind or media_kind(path or "") or "video"
            mime = mime or mime_of(path or "", kind)
            size = len(data) if data is not None else os.path.getsize(path)
            ext = os.path.splitext(path or "")[1].lower()
            if data is not None or kind != "video":
                ext = {"image/jpeg": ".jpg", "image/png": ".png", "audio/mpeg": ".mp3"}.get(mime, ext or ".bin")
            elif ext not in MIME:
                ext = ".mp4"
        media = {
            "file": path, "data": data, "live": live, "size": size, "mime": mime, "kind": kind,
            "features": features or DLNA_FEATURES, "transfer": "Interactive" if kind == "image" else "Streaming",
            "title": title or os.path.splitext(os.path.basename(path or ""))[0] or APP_NAME, "token": token,
            "url_path": f"/media/{token}{ext}", "meta": dict(meta or {}),
            "sub_path": None, "blank_path": f"/media/{token}-off.srt", "subs": {}, "extras": {},
            "requested": threading.Event(),
            # subtitle commands: a counter so a pending resend knows it's out of date, when the last went out
            "caption_lock": threading.Lock(), "caption_seq": 0, "caption_sent": 0.0,
        }
        if kind == "audio" and mime == "audio/mpeg":
            media["features"] = "DLNA.ORG_PN=MP3;" + media["features"]
        media["subs"][media["blank_path"]] = BLANK_SRT
        if subtitle is not None:
            media["sub_path"] = f"/media/{token}.srt"
            media["subs"][media["sub_path"]] = subtitle
        self.httpd.media = media
        return media

    def add_extra(self, media, mime, data):
        """Serve a file that goes with the media (a song's cover); returns its URL path."""
        ext = {"image/jpeg": ".jpg", "image/png": ".png"}.get(mime, ".bin")
        path = f"/media/{media['token']}-{secrets.token_hex(3)}{ext}"
        media["extras"][path] = (mime, data)
        return path

    def add_subtitle(self, media, data):
        """Serve one more subtitle file for the playing video; returns its URL path."""
        path = f"/media/{media['token']}-{secrets.token_hex(3)}.srt"
        media["subs"][path] = data
        return path

    def url_for(self, tv_host, path):
        return f"http://{local_ip_towards(tv_host)}:{self.port}{path}"

    def url(self, media, tv_host):
        return self.url_for(tv_host, media["url_path"])

    def sub_url(self, media, tv_host):
        return self.url_for(tv_host, media["sub_path"]) if media.get("sub_path") else None

    def event_url(self, tv_host, service):
        return f"http://{local_ip_towards(tv_host)}:{self.port}/events/{service}"

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()


class LiveStream:
    """One live MPEG-TS stream (the desktop) shared by every request for it.

    TVs often open the URL twice (a quick look, then for real) and reconnect after buffering, but there is
    only one encoder. Each request starts at the latest keyframe, right after the stream's PAT and PMT, so
    the TV's decoder can start at once; a request that falls far behind (the TV paused) skips ahead to the
    next keyframe instead of piling up old pictures."""

    BACKLOG = 8 << 20  # bytes a slow request may lag behind before it skips ahead
    GOP_LIMIT = 24 << 20

    def __init__(self, on_join=None):
        self.cond = threading.Condition()
        self.clients = []
        self.closed = False
        self.error = None
        self.on_join = on_join  # e.g. ask the encoder for a keyframe
        self._rest = b""
        self.pat = self.pmt = None
        self.pmt_pid = self.video_pid = self.video_type = None
        self.gop, self.gop_bytes = [], 0  # packets since the latest keyframe
        self.keyframes = 0
        self.bytes_in = 0

    def feed(self, data):
        """Encoder output, in any amount (partial packets wait for the rest)."""
        data = self._rest + data
        packets, pos, n = [], 0, len(data)
        while pos + 188 <= n:
            if data[pos] != 0x47:  # lost sync: find the next packet start
                nxt = data.find(b"\x47", pos + 1)
                if nxt < 0:
                    pos = n
                    break
                pos = nxt
                continue
            packets.append(data[pos:pos + 188])
            pos += 188
        self._rest = data[pos:]
        if not packets:
            return
        with self.cond:
            self.bytes_in += len(packets) * 188
            for p in packets:
                self._packet(p)
            self.cond.notify_all()

    def _packet(self, p):
        pid = ((p[1] & 0x1F) << 8) | p[2]
        start = bool(p[1] & 0x40)
        afc = (p[3] >> 4) & 3
        payload_at, rai = 4, False
        if afc & 2:
            if p[4] > 0:
                rai = bool(p[5] & 0x40)
            payload_at = 5 + p[4]
        payload = p[payload_at:] if afc & 1 else b""
        if pid == 0 and start and payload:
            self._parse_pat(payload)
            self.pat = p
        elif pid == self.pmt_pid and start and payload:
            self._parse_pmt(payload)
            self.pmt = p
        key = pid == self.video_pid and start and (rai or _starts_keyframe(payload, self.video_type))
        if key and self.pat and self.pmt:
            self.keyframes += 1
            self.gop, self.gop_bytes = [p], 188
        elif self.gop and self.gop_bytes < self.GOP_LIMIT:
            self.gop.append(p)
            self.gop_bytes += 188
        for c in self.clients:
            if c.waiting:
                if key and self.pat and self.pmt:
                    c.buf += self.pat + self.pmt + p
                    c.waiting = False
            else:
                c.buf += p
                if len(c.buf) > self.BACKLOG:
                    c.buf.clear()
                    c.waiting = True

    def _parse_pat(self, payload):
        sec = payload[1 + payload[0]:]
        length = ((sec[1] & 0x0F) << 8) | sec[2] if len(sec) >= 3 else 0
        for i in range(8, min(3 + length - 4, len(sec) - 3), 4):
            program, pid = (sec[i] << 8) | sec[i + 1], ((sec[i + 2] & 0x1F) << 8) | sec[i + 3]
            if program != 0:
                self.pmt_pid = pid
                return

    def _parse_pmt(self, payload):
        sec = payload[1 + payload[0]:]
        if len(sec) < 12:
            return
        length = ((sec[1] & 0x0F) << 8) | sec[2]
        i, end = 12 + (((sec[10] & 0x0F) << 8) | sec[11]), min(3 + length - 4, len(sec))
        while i + 5 <= end:
            stype, pid = sec[i], ((sec[i + 1] & 0x1F) << 8) | sec[i + 2]
            if stype in (0x01, 0x02, 0x10, 0x1B, 0x24):  # MPEG-1/2, MPEG-4, H.264, H.265 video
                self.video_pid, self.video_type = pid, stype
                return
            i += 5 + (((sec[i + 3] & 0x0F) << 8) | sec[i + 4])

    def open(self):
        """A reader for one HTTP request: it starts at the latest keyframe."""
        c = _LiveClient(self)
        with self.cond:
            if self.gop and self.pat and self.pmt:
                c.buf += self.pat + self.pmt + b"".join(self.gop)
                c.waiting = False
            self.clients.append(c)
        if self.on_join:
            try:
                self.on_join()
            except Exception:  # noqa: BLE001
                pass
        return c

    def wait_started(self, timeout):
        """True once the stream has its first keyframe (the TV can be told to play it)."""
        deadline = time.monotonic() + timeout
        with self.cond:
            while not (self.keyframes or self.closed):
                left = deadline - time.monotonic()
                if left <= 0:
                    return False
                self.cond.wait(left)
            return self.keyframes > 0

    def close(self, error=None):
        with self.cond:
            if not self.closed:
                self.closed, self.error = True, error
            self.cond.notify_all()


class _LiveClient:
    def __init__(self, stream):
        self.stream, self.buf, self.waiting = stream, bytearray(), True

    def read(self, timeout=5.0, most=256 << 10):
        """Bytes to send; None if nothing came within timeout; b"" when the stream has ended."""
        cond = self.stream.cond
        with cond:
            if not self.buf and not self.stream.closed:
                cond.wait(timeout)
            if self.buf:
                data = bytes(self.buf[:most])
                del self.buf[:most]
                return data
            return b"" if self.stream.closed else None

    def close(self):
        with self.stream.cond:
            if self in self.stream.clients:
                self.stream.clients.remove(self)


def _starts_keyframe(pes, stream_type):
    """Does this PES packet start a picture a decoder can begin with (H.264/H.265 parameter sets or IDR,
    MPEG-2 sequence header)? Only needed when the muxer doesn't flag keyframes itself."""
    if pes[:3] != b"\x00\x00\x01" or len(pes) < 9:
        return False
    es = pes[9 + pes[8]:]
    i = es.find(b"\x00\x00\x01")
    while 0 <= i < len(es) - 3:
        code = es[i + 3]
        if stream_type == 0x1B and not code & 0x80 and code & 0x1F in (5, 7):
            return True
        if stream_type == 0x24 and (code >> 1) & 0x3F in (19, 20, 32, 33):
            return True
        if stream_type in (0x01, 0x02) and code == 0xB3:
            return True
        i = es.find(b"\x00\x00\x01", i + 3)
    return False


def show_subtitle(tv, server, media, path=None):
    """Switch the TV to one of the playing video's subtitle files right away (Samsung). path=None
    shows the empty file, i.e. subtitles off. Returns 'ok', or 'unsupported' when the TV only takes
    subtitles together with the video (then restart playback with the subtitle announced)."""
    if not tv.can_switch_subtitles():
        return "unsupported"
    with media["caption_lock"]:
        media["caption_seq"] += 1
        _send_caption(tv, server, media, path)
    return "ok"


def _send_caption(tv, server, media, path):
    tv.show_caption(server.url(media, tv.host), server.url_for(tv.host, path or media["blank_path"]),
                    media["title"])
    media["caption_sent"] = time.monotonic()


def _reshow_when_playing(tv, server, media, seq, start_at, seen):
    """Send the start-up subtitle again once the video really plays.

    Switched on while the video is still starting or seeking to the resume point, a Samsung player
    can list the subtitle as on without drawing it (another Samsung caster saw the same). The same
    command a little later shows it. Gives up when the subtitle is changed from the PC, the video
    stops or another one plays."""
    floor = max(0.0, start_at - 15)  # seeks land on a keyframe, which can be a little earlier
    last, steady, deadline = None, 0, time.time() + 300
    while True:
        time.sleep(1)
        if media["caption_seq"] != seq or server.httpd.media is not media or time.time() > deadline:
            return
        try:
            state = tv.state()
            pos, _dur, uri = tv.position()
        except TVError:
            last = None
            continue
        if uri and media["url_path"] not in uri:
            return  # another video is on
        if state != "PLAYING":
            if seen and state in ("STOPPED", "NO_MEDIA_PRESENT"):
                return
            last = None
            continue
        seen = True
        if pos is not None and last is not None and pos > last:
            steady += 1
            if pos >= floor or steady >= 15:  # past the resume point (or it never got there)
                break
        last = pos
    for pause in (1, 4):
        time.sleep(pause)
        with media["caption_lock"]:
            if media["caption_seq"] != seq or server.httpd.media is not media:
                return
            try:
                _send_caption(tv, server, media, media["sub_path"])
            except TVError:
                return


def play(tv, tries=5):
    """Play, allowing for TVs that are still busy taking the new URL (UPnP error 701, "transition not
    available" - Sony, Philips and others) and for those that start by themselves."""
    for attempt in range(tries):
        try:
            tv.play()
            return
        except TVError as e:
            if e.code != 701 or attempt == tries - 1:
                raise
            try:
                if tv.state() in ("PLAYING", "TRANSITIONING"):
                    return
            except TVError:
                pass
            time.sleep(0.7)


def play_media(tv, server, media, start_at=0, wait=20.0, subs_on=False):
    """Hand published media to the TV and start it, optionally resuming at start_at seconds
    and switching its subtitle on. Returns the show_subtitle() result, or None without subtitles."""
    media["tv_mime"] = mime = tv.pick_mime(media["mime"])
    url = server.url(media, tv.host)
    sub = server.sub_url(media, tv.host) if media["kind"] == "video" else None
    meta = dict(media["meta"])
    if meta.get("cover_path"):
        meta["cover_url"] = server.url_for(tv.host, meta["cover_path"])
    try:
        tv.stop()
    except TVError:
        pass  # nothing was playing
    tv.set_media(url, media["title"], mime, media["size"], sub_url=sub, kind=media["kind"],
                 features=media["features"], meta=meta)
    play(tv)
    if media["kind"] == "image" or media["live"] is not None:
        return None
    playing = False
    if (start_at and start_at > 1) or (sub and subs_on):
        deadline = time.time() + wait
        while time.time() < deadline:
            try:
                if tv.state() == "PLAYING":
                    playing = True
                    if start_at and start_at > 1:
                        tv.seek(start_at)
                    break
            except TVError:
                pass  # not ready yet
            time.sleep(0.5)
    if sub and subs_on:
        if not tv.can_switch_subtitles():
            return "unsupported"  # announced with the video; the TV's own settings decide
        deadline = time.time() + 5  # let the TV register the announced file first
        while time.time() < deadline:
            try:
                if any(a.get("uri") == sub for a in tv.caption_state()[0]):
                    break
            except TVError:
                break
            time.sleep(0.5)
        result = show_subtitle(tv, server, media, media["sub_path"])
        threading.Thread(target=_reshow_when_playing, daemon=True,
                         args=(tv, server, media, media["caption_seq"], start_at, playing)).start()
        return result
    return None


def start_playback(tv, server, path, start_at=0, subtitle=None, subs_on=False):
    media = server.publish(os.path.abspath(path), subtitle)
    media["captions"] = play_media(tv, server, media, start_at, subs_on=subs_on)
    return media


# ---------------------------------------------------------------- settings

def load_config():
    cfg = {"tv": None, "recent": [], "preview": False, "intro": False, "last_dir": None}
    try:
        with open(CONFIG_FILE, encoding="utf-8") as f:
            cfg.update(json.load(f))
    except (OSError, ValueError):
        pass
    return cfg


def save_config(cfg):
    os.makedirs(CONFIG_DIR, exist_ok=True)
    tmp = CONFIG_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=1, ensure_ascii=False)
    os.replace(tmp, CONFIG_FILE)


# ---------------------------------------------------------------- dependencies

PACKAGES = {  # what to install, per distro family
    "debian": ("sudo apt install", {
        "gtk": "python3-gi gir1.2-gtk-3.0",
        "gst": "gir1.2-gstreamer-1.0 gir1.2-gst-plugins-base-1.0 gstreamer1.0-plugins-base",
        "gst-good": "gstreamer1.0-plugins-good", "gst-gtk": "gstreamer1.0-gtk3 gstreamer1.0-gl",
        "gst-libav": "gstreamer1.0-libav", "gst-va": "gstreamer1.0-plugins-bad",
        "intro": "python3-cairo ffmpeg", "subs": "ffmpeg", "desktop": "ffmpeg",
        "desktop-wayland": "python3-gi gir1.2-gstreamer-1.0 gstreamer1.0-plugins-base gstreamer1.0-pipewire",
        "photos": "heif-gdk-pixbuf webp-pixbuf-loader"}),
    "arch": ("sudo pacman -S --needed", {
        "gtk": "python-gobject gtk3", "gst": "gstreamer gst-plugins-base",
        "gst-good": "gst-plugins-good", "gst-gtk": "gst-plugin-gtk",
        "gst-libav": "gst-libav", "gst-va": "gst-plugin-va", "intro": "python-cairo ffmpeg", "subs": "ffmpeg",
        "desktop": "ffmpeg", "desktop-wayland": "python-gobject gstreamer gst-plugins-base gst-plugin-pipewire",
        "photos": "libheif webp-pixbuf-loader"}),
    "fedora": ("sudo dnf install", {
        "gtk": "python3-gobject gtk3", "gst": "gstreamer1 gstreamer1-plugins-base",
        "gst-good": "gstreamer1-plugins-good", "gst-gtk": "gstreamer1-plugins-good-gtk",
        "gst-libav": "gstreamer1-plugin-libav", "gst-va": "gstreamer1-plugins-bad-free",
        "intro": "python3-cairo ffmpeg-free", "subs": "ffmpeg-free", "desktop": "ffmpeg-free",
        "desktop-wayland": "python3-gobject gstreamer1 gstreamer1-plugins-base pipewire-gstreamer",
        "photos": "webp-pixbuf-loader"}),
}
GENERIC = {
    "gtk": "PyGObject + GTK 3", "gst": "GStreamer 1.x (Python bindings, base plugins)",
    "gst-good": "GStreamer good plugins", "gst-gtk": "GStreamer GTK plugin (gtksink)",
    "gst-libav": "GStreamer libav plugin", "gst-va": "GStreamer VA-API plugin",
    "intro": "pycairo + ffmpeg", "subs": "ffmpeg", "desktop": "ffmpeg",
    "desktop-wayland": "PyGObject + GStreamer with its PipeWire plugin",
    "photos": "GdkPixbuf loaders for HEIC (libheif) and WebP (webp-pixbuf-loader)",
}


def distro_family():
    ids = []
    try:
        with open("/etc/os-release", encoding="utf-8") as f:
            for line in f:
                key, _sep, val = line.strip().partition("=")
                if key in ("ID", "ID_LIKE"):
                    ids += val.strip('"').lower().split()
    except OSError:
        pass
    for i in ids:
        if i in ("arch", "debian", "fedora"):
            return i
        if i == "ubuntu":
            return "debian"
    return None


def install_hint(missing):
    fam = distro_family()
    if fam:
        cmd, pkgs = PACKAGES[fam]
        names = " ".join(dict.fromkeys(" ".join(pkgs[m] for m in missing).split()))
        return f"{cmd} {names}"
    return _("Install: {what}").format(what=", ".join(GENERIC[m] for m in missing))


def check_deps():
    """(missing for the GUI, missing for the PC preview, missing for the Skynet intro,
    missing for subtitle tracks, recommended)."""
    subs = [] if have("ffmpeg") and have("ffprobe") else ["subs"]
    intro = [] if have("ffmpeg") else ["intro"]
    try:
        import cairo  # noqa: F401
    except ImportError:
        intro = ["intro"]
    try:
        import gi
        gi.require_version("Gtk", "3.0")
        from gi.repository import Gtk  # noqa: F401
    except (ImportError, ValueError):
        return ["gtk"], [], intro, subs, []
    try:
        gi.require_version("Gst", "1.0")
        from gi.repository import Gst
        Gst.init(None)
    except (ImportError, ValueError):
        return [], ["gst"], intro, subs, []
    need = {"playbin": "gst", "matroskademux": "gst-good", "qtdemux": "gst-good", "avdec_h264": "gst-libav"}
    preview = [k for el, k in need.items() if not Gst.ElementFactory.find(el)]
    if not (Gst.ElementFactory.find("gtksink") or Gst.ElementFactory.find("gtkglsink")):
        preview.append("gst-gtk")
    recommended = [] if Gst.ElementFactory.find("vah265dec") else ["gst-va"]
    return [], list(dict.fromkeys(preview)), intro, subs, recommended


if __name__ == "__main__" and "--check-deps" in sys.argv:
    gui, preview, intro, subs, rec = check_deps()

    def line(ok, what, missing):
        if ok:
            print(f"  ✓ {what}")
        else:
            print(f"  ✗ {what}: {_('needs')} {', '.join(GENERIC[m] for m in missing)}\n      {install_hint(missing)}")
    line(True, _("Command line (opuscast)"), [])
    line(not gui, _("App window"), gui)
    if not gui:
        line(not preview, _("Show on PC"), preview)
    line(not subs, _("Built-in subtitle tracks"), subs)
    import opuscast_desktop
    desk = opuscast_desktop.missing()
    line(not desk, _("Cast the desktop"), desk)
    line(not subs, _("Music the TV doesn't play, converted to MP3"), subs)
    line(not intro, _("Skynet intro"), intro)
    if rec:
        print(f"  • {_('Recommended for a smooth 4K preview')}: {GENERIC[rec[0]]}\n      {install_hint(rec)}")
    sys.exit(1 if gui else 0)
