"""Photos and music for OpusCast: what a TV needs to show a photo or play a song.

Standard library only. Photos the TV can't show as they are (HEIC, WebP, rotated or huge ones) are
converted to JPEG with GdkPixbuf (part of GTK) or ffmpeg; song titles, artists, albums and covers are
read straight from the files (MP3, FLAC, M4A, Ogg/Opus, WAV), and songs in a format the TV doesn't
list are converted to MP3 with ffmpeg first.
"""
import base64
import hashlib
import os
import re
import struct
import subprocess
import threading

import opuscast_core as core
from opuscast_i18n import _

PHOTO_BOX = (3840, 2160)  # converted photos fit a 4K TV
MAX_SIDE = 4096  # DLNA's largest JPEG/PNG profile; bigger ones are shrunk first (some TVs show nothing)
COVER_NAMES = ("cover", "folder", "front", "album", "albumart", "albumartsmall", "thumb")


# ---------------------------------------------------------------- folders

def folder_items(path, kind):
    """The files of one kind (video/audio/image) in path's folder - or in path itself if it's a folder -
    in a sensible order: songs by disc and track number, everything else by name."""
    folder = path if os.path.isdir(path) else os.path.dirname(os.path.abspath(path))
    try:
        names = os.listdir(folder)
    except OSError:
        return [path] if os.path.isfile(path) else []
    files = [os.path.join(folder, n) for n in names if not n.startswith(".")]
    files = [f for f in files if os.path.isfile(f) and core.media_kind(f) == kind]
    if kind == "audio":
        tags = {f: audio_tags(f, cover=False) for f in files}
        return sorted(files, key=lambda f: (tags[f].get("disc") or 0, tags[f].get("track") or 0, natural_key(f)))
    return sorted(files, key=natural_key)


def natural_key(path):
    """'Track 2' before 'Track 10'."""
    name = os.path.basename(path).lower()
    return [int(t) if t.isdigit() else t for t in re.split(r"(\d+)", name)]


def collect(paths):
    """What a set of chosen files/folders amounts to: (kind, files). Videos win, then songs, then photos;
    a folder brings its files of that kind."""
    files = []
    for p in paths:
        if os.path.isdir(p):
            for kind in ("video", "audio", "image"):
                found = folder_items(p, kind)
                if found:
                    files += found
                    break
        elif os.path.isfile(p):
            files.append(os.path.abspath(p))
    for kind in ("video", "audio", "image"):
        chosen = [f for f in files if core.media_kind(f) == kind]
        if chosen:
            return kind, list(dict.fromkeys(chosen))
    return None, []


# ---------------------------------------------------------------- photos

def image_info(path):
    """{'fmt': jpeg/png/gif/bmp/None, 'w', 'h', 'orientation'} from the file's header (no decoding)."""
    info = {"fmt": None, "w": 0, "h": 0, "orientation": 1}
    try:
        with open(path, "rb") as f:
            head = f.read(32)
            if head[:3] == b"\xff\xd8\xff":
                info["fmt"] = "jpeg"
                f.seek(2)
                _jpeg_info(f, info)
            elif head[:8] == b"\x89PNG\r\n\x1a\n" and head[12:16] == b"IHDR":
                info.update(fmt="png", w=struct.unpack(">I", head[16:20])[0], h=struct.unpack(">I", head[20:24])[0])
            elif head[:6] in (b"GIF87a", b"GIF89a"):
                info.update(fmt="gif", w=struct.unpack("<H", head[6:8])[0], h=struct.unpack("<H", head[8:10])[0])
            elif head[:2] == b"BM" and len(head) >= 26:
                info.update(fmt="bmp", w=abs(struct.unpack("<i", head[18:22])[0]),
                            h=abs(struct.unpack("<i", head[22:26])[0]))
    except (OSError, struct.error):
        pass
    return info


def _jpeg_info(f, info):
    while True:
        marker = f.read(2)
        if len(marker) < 2 or marker[0] != 0xFF:
            return
        while marker[1] == 0xFF:  # fill bytes
            marker = marker[1:] + f.read(1)
        code = marker[1]
        if code in (0xD8, 0x01) or 0xD0 <= code <= 0xD7:
            continue
        if code in (0xD9, 0xDA):  # end, or the picture data starts: no size marker before it
            return
        length = struct.unpack(">H", f.read(2))[0]
        if code == 0xE1:
            data = f.read(length - 2)
            if data[:6] == b"Exif\x00\x00":
                info["orientation"] = _exif_orientation(data[6:])
            continue
        if 0xC0 <= code <= 0xCF and code not in (0xC4, 0xC8, 0xCC):
            data = f.read(5)
            info["h"], info["w"] = struct.unpack(">HH", data[1:5])
            return
        f.seek(length - 2, 1)


def _exif_orientation(tiff):
    try:
        order = "<" if tiff[:2] == b"II" else ">"
        ifd = struct.unpack(order + "I", tiff[4:8])[0]
        count = struct.unpack(order + "H", tiff[ifd:ifd + 2])[0]
        for i in range(count):
            entry = tiff[ifd + 2 + 12 * i: ifd + 14 + 12 * i]
            if struct.unpack(order + "H", entry[:2])[0] == 0x0112:
                value = struct.unpack(order + "H", entry[8:10])[0]
                return value if 1 <= value <= 8 else 1
    except struct.error:
        pass
    return 1


def photo_features(mime, w=0, h=0, converted=False):
    """DLNA's description of a photo: its profile by size, no seeking, shown at once."""
    pn = ""
    if mime == "image/jpeg":
        pn = "JPEG_SM" if w and h and w <= 640 and h <= 480 else "JPEG_MED" if w and h and w <= 1024 and h <= 768 \
            else "JPEG_LRG"
    elif mime == "image/png":
        pn = "PNG_LRG"
    return (f"DLNA.ORG_PN={pn};" if pn else "") + \
        f"DLNA.ORG_OP=00;DLNA.ORG_CI={1 if converted else 0};DLNA.ORG_FLAGS=00f00000000000000000000000000000"


_photo_cache, _photo_lock = {}, threading.Lock()


def prepare_photo(path, tv=None):
    """What to send the TV for a photo: {'file' or 'data', 'mime', 'features', 'w', 'h', 'converted'}.

    JPEGs (and PNGs the TV lists) go as they are when they're upright and not huge; everything else is
    turned upright, shrunk to fit 4K if bigger, and sent as JPEG. Results are kept, so going back and
    forth in a slideshow is instant."""
    st = os.stat(path)
    accepts_png = tv.accepts("image/png") if tv else None
    key = (path, st.st_mtime_ns, st.st_size, accepts_png is not False)
    with _photo_lock:
        if key in _photo_cache:
            return _photo_cache[key]
    info = image_info(path)
    w, h, upright = info["w"], info["h"], info["orientation"] == 1
    small = 0 < w <= MAX_SIDE and 0 < h <= MAX_SIDE
    if info["fmt"] == "jpeg" and upright and small:
        out = {"file": path, "mime": "image/jpeg", "w": w, "h": h, "converted": False}
    elif info["fmt"] == "png" and small and accepts_png is not False:
        out = {"file": path, "mime": "image/png", "w": w, "h": h, "converted": False}
    else:
        data, w, h = to_jpeg(path, PHOTO_BOX)
        if data is None:  # nothing here can read it: let the TV try the original
            mime = core.IMAGE_MIME.get(os.path.splitext(path)[1].lower(), "image/jpeg")
            out = {"file": path, "mime": mime, "w": info["w"], "h": info["h"], "converted": False,
                   "unconverted": info["fmt"] not in ("jpeg", "png")}
        else:
            out = {"data": data, "mime": "image/jpeg", "w": w, "h": h, "converted": True}
    out["features"] = photo_features(out["mime"], out["w"], out["h"], out["converted"])
    with _photo_lock:
        if len(_photo_cache) > 12:
            _photo_cache.pop(next(iter(_photo_cache)))
        _photo_cache[key] = out
    return out


def to_jpeg(path, box, quality=90):
    """(JPEG bytes, width, height) - upright, fitted into box (never enlarged) - or (None, 0, 0)."""
    try:
        return _pixbuf_jpeg(path, box, quality)
    except Exception:  # noqa: BLE001 - no GdkPixbuf, or no loader for this format (HEIC, AVIF...)
        pass
    return _ffmpeg_jpeg(path, box)


def _pixbuf_jpeg(path, box, quality):
    import gi
    gi.require_version("GdkPixbuf", "2.0")
    from gi.repository import GdkPixbuf
    pb = GdkPixbuf.Pixbuf.new_from_file(path)
    pb = pb.apply_embedded_orientation() or pb
    w, h = pb.get_width(), pb.get_height()
    scale = min(1.0, box[0] / w, box[1] / h)
    tw, th = max(1, round(w * scale)), max(1, round(h * scale))
    if pb.get_has_alpha():  # JPEG has no transparency: put it on black, like the TV's background
        pb = pb.composite_color_simple(tw, th, GdkPixbuf.InterpType.HYPER, 255, 1 << 30, 0, 0)
    elif (tw, th) != (w, h):
        pb = pb.scale_simple(tw, th, GdkPixbuf.InterpType.HYPER)
    ok, data = pb.save_to_bufferv("jpeg", ["quality"], [str(quality)])
    if not ok:
        raise ValueError("JPEG encoding failed")
    return bytes(data), tw, th


def _ffmpeg_jpeg(path, box):
    if not core.have("ffmpeg"):
        return None, 0, 0
    fit = (f"scale=w='min({box[0]},iw)':h='min({box[1]},ih)':force_original_aspect_ratio=decrease,"
           "format=yuvj420p")
    try:
        r = subprocess.run(["ffmpeg", "-v", "error", "-nostdin", "-i", path, "-frames:v", "1", "-vf", fit,
                            "-q:v", "2", "-f", "image2pipe", "-c:v", "mjpeg", "pipe:1"],
                           capture_output=True, timeout=60)
    except (OSError, subprocess.SubprocessError):
        return None, 0, 0
    if r.returncode != 0 or not r.stdout.startswith(b"\xff\xd8"):
        return None, 0, 0
    tmp = {"orientation": 1, "w": 0, "h": 0}
    import io
    _jpeg_info(io.BytesIO(r.stdout[2:]), tmp)
    return r.stdout, tmp["w"], tmp["h"]


# ---------------------------------------------------------------- music: tags and covers

def audio_tags(path, cover=True):
    """{'title', 'artist', 'album', 'track', 'disc', 'duration' (s), 'cover': (mime, bytes)} - whatever
    the file says. Missing titles come from the file name ('03 - Artist - Song.mp3')."""
    tags = {}
    try:
        with open(path, "rb") as f:
            head = f.read(12)
            f.seek(0)
            if head[:3] == b"ID3":
                _id3(f, tags, cover)
            elif head[:4] == b"fLaC":
                f.seek(4)
                _flac_blocks(f, tags, cover)
            elif head[4:8] == b"ftyp":
                _mp4(f, tags, cover)
            elif head[:4] == b"OggS":
                _ogg(f, tags, cover)
            elif head[:4] == b"RIFF" and head[8:12] == b"WAVE":
                _wav(f, tags)
    except (OSError, struct.error, ValueError, IndexError):
        pass
    _from_file_name(path, tags)
    return tags


def _from_file_name(path, tags):
    stem = os.path.splitext(os.path.basename(path))[0]
    m = re.match(r"^\s*(?:(\d{1,2})[-.](\d{1,3})|(\d{1,3}))\s*[-._)]?\s+(.*)$", stem)
    if m:
        if not tags.get("track"):
            tags["track"] = int(m.group(2) or m.group(3))
        stem = m.group(4) or stem
    if not tags.get("title"):
        artist, sep, title = stem.partition(" - ")
        if sep and title.strip() and not tags.get("artist"):
            tags["artist"], tags["title"] = artist.strip(), title.strip()
        else:
            tags["title"] = stem.strip() or os.path.basename(path)


def _number(text):
    m = re.match(r"\s*(\d+)", text or "")
    return int(m.group(1)) if m else None


def _set_text(tags, key, value):
    value = (value or "").strip().strip("\x00").strip()
    if not value or tags.get(key):
        return
    if key in ("track", "disc"):
        tags[key] = _number(value)
    else:
        tags[key] = value


def _syncsafe(b):
    return (b[0] << 21) | (b[1] << 14) | (b[2] << 7) | b[3]


def _id3_text(data):
    enc, body = data[0], data[1:]
    codec = {0: "latin-1", 1: "utf-16", 2: "utf-16-be", 3: "utf-8"}.get(enc, "latin-1")
    text = body.decode(codec, "replace")
    return text.replace("\x00", "/").strip("/")  # ID3v2.4 separates several values with NUL


def _id3_split(enc, data):
    """(text, rest) of a NUL-terminated string in an ID3 frame."""
    term = b"\x00\x00" if enc in (1, 2) else b"\x00"
    i = 0
    while True:
        i = data.find(term, i)
        if i < 0:
            return data, b""
        if len(term) == 1 or i % 2 == 0:
            return data[:i], data[i + len(term):]
        i += 1


ID3_KEYS = {"TIT2": "title", "TT2": "title", "TPE1": "artist", "TP1": "artist", "TPE2": "albumartist",
            "TP2": "albumartist", "TALB": "album", "TAL": "album", "TRCK": "track", "TRK": "track",
            "TPOS": "disc", "TPA": "disc"}


def _id3(f, tags, cover):
    header = f.read(10)
    major, flags, size = header[3], header[5], _syncsafe(header[6:10])
    data = f.read(size)
    if flags & 0x80 and major < 4:  # unsynchronised tag (v2.3 and older)
        data = data.replace(b"\xff\x00", b"\xff")
    pos = 0
    if flags & 0x40 and major >= 3:  # extended header
        ext = _syncsafe(data[:4]) if major == 4 else struct.unpack(">I", data[:4])[0] + 4
        pos = ext
    id_len, head_len = (3, 6) if major == 2 else (4, 10)
    while pos + head_len <= len(data):
        fid = data[pos:pos + id_len].decode("latin-1", "replace")
        if not fid.strip("\x00") or not fid.isalnum():
            break
        if major == 2:
            fsize = int.from_bytes(data[pos + 3:pos + 6], "big")
        elif major == 4:
            fsize = _syncsafe(data[pos + 4:pos + 8])
        else:
            fsize = struct.unpack(">I", data[pos + 4:pos + 8])[0]
        fflags = data[pos + 9] if major >= 3 else 0
        body = data[pos + head_len:pos + head_len + fsize]
        pos += head_len + fsize
        if (major == 4 and fflags & 0x0C) or (major == 3 and fflags & 0xC0):
            continue  # compressed or encrypted frame
        if major == 4 and fflags & 0x02:
            body = body.replace(b"\xff\x00", b"\xff")
        if major == 4 and fflags & 0x01:  # a data length field comes first
            body = body[4:]
        if fid in ID3_KEYS and body:
            _set_text(tags, ID3_KEYS[fid], _id3_text(body))
        elif fid in ("APIC", "PIC") and cover and body and not tags.get("cover"):
            enc = body[0]
            if fid == "APIC":
                mime, rest = body[1:].split(b"\x00", 1)
                mime = mime.decode("latin-1").lower()
            else:
                mime, rest = {b"PNG": "image/png"}.get(body[1:4].upper(), "image/jpeg"), body[4:]
            _desc, picture = _id3_split(enc, rest[1:])
            _set_cover(tags, picture, mime)


def _set_cover(tags, data, mime=""):
    if data[:3] == b"\xff\xd8\xff":
        tags["cover"] = ("image/jpeg", data)
    elif data[:8] == b"\x89PNG\r\n\x1a\n":
        tags["cover"] = ("image/png", data)
    elif mime.startswith("image/") and data:
        tags["cover"] = (mime, data)


def _vorbis_comments(data, tags, cover):
    vendor = struct.unpack("<I", data[:4])[0]
    pos = 4 + vendor
    count = struct.unpack("<I", data[pos:pos + 4])[0]
    pos += 4
    keys = {"TITLE": "title", "ARTIST": "artist", "ALBUMARTIST": "albumartist", "ALBUM": "album",
            "TRACKNUMBER": "track", "DISCNUMBER": "disc"}
    for _i in range(count):
        n = struct.unpack("<I", data[pos:pos + 4])[0]
        entry = data[pos + 4:pos + 4 + n].decode("utf-8", "replace")
        pos += 4 + n
        key, _sep, value = entry.partition("=")
        key = key.upper()
        if key in keys:
            _set_text(tags, keys[key], value)
        elif key == "METADATA_BLOCK_PICTURE" and cover and not tags.get("cover"):
            try:
                _flac_picture(base64.b64decode(value), tags)
            except (ValueError, struct.error):
                pass


def _flac_picture(data, tags):
    mlen = struct.unpack(">I", data[4:8])[0]
    mime = data[8:8 + mlen].decode("latin-1", "replace").lower()
    pos = 8 + mlen
    dlen = struct.unpack(">I", data[pos:pos + 4])[0]
    pos += 4 + dlen + 16
    size = struct.unpack(">I", data[pos:pos + 4])[0]
    _set_cover(tags, data[pos + 4:pos + 4 + size], mime)


def _flac_blocks(f, tags, cover):
    while True:
        head = f.read(4)
        if len(head) < 4:
            return
        last, kind, size = head[0] & 0x80, head[0] & 0x7F, int.from_bytes(head[1:4], "big")
        if kind == 0:
            info = f.read(size)
            rate = int.from_bytes(info[10:13], "big") >> 4
            total = ((info[13] & 0x0F) << 32) | struct.unpack(">I", info[14:18])[0]
            if rate and total:
                tags["duration"] = total / rate
        elif kind == 4:
            _vorbis_comments(f.read(size), tags, cover)
        elif kind == 6 and cover and not tags.get("cover"):
            _flac_picture(f.read(size), tags)
        else:
            f.seek(size, 1)
        if last:
            return


def _mp4_boxes(f, end):
    while f.tell() + 8 <= end:
        start = f.tell()
        size, kind = struct.unpack(">I4s", f.read(8))
        head = 8
        if size == 1:
            size, head = struct.unpack(">Q", f.read(8))[0], 16
        elif size == 0:
            size = end - start
        if size < head:
            return
        yield kind, start + head, start + size
        f.seek(start + size)


def _mp4(f, tags, cover):
    f.seek(0, 2)
    end = f.tell()
    f.seek(0)
    for kind, body, box_end in _mp4_boxes(f, end):
        if kind == b"moov":
            _mp4_moov(f, body, box_end, tags, cover)
            return


def _mp4_moov(f, start, end, tags, cover):
    keys = {b"\xa9nam": "title", b"\xa9ART": "artist", b"aART": "albumartist", b"\xa9alb": "album"}
    f.seek(start)
    for kind, body, box_end in list(_mp4_boxes(f, end)):
        if kind == b"mvhd":
            f.seek(body)
            version = f.read(4)[0]
            f.seek(body + (20 if version == 1 else 12))
            if version == 1:
                scale, dur = struct.unpack(">IQ", f.read(12))
            else:
                scale, dur = struct.unpack(">II", f.read(8))
            if scale:
                tags["duration"] = dur / scale
        elif kind == b"udta":
            f.seek(body)
            for k2, b2, e2 in list(_mp4_boxes(f, box_end)):
                if k2 != b"meta":
                    continue
                f.seek(b2 + 4)  # a full box: version and flags first
                for k3, b3, e3 in list(_mp4_boxes(f, e2)):
                    if k3 != b"ilst":
                        continue
                    f.seek(b3)
                    for item, b4, e4 in list(_mp4_boxes(f, e3)):
                        f.seek(b4)
                        for k5, b5, e5 in list(_mp4_boxes(f, e4)):
                            if k5 != b"data":
                                continue
                            f.seek(b5)
                            dtype = struct.unpack(">I", f.read(4))[0] & 0xFFFFFF
                            f.seek(b5 + 8)
                            value = f.read(e5 - b5 - 8)
                            if item in keys:
                                _set_text(tags, keys[item], value.decode("utf-8", "replace"))
                            elif item in (b"trkn", b"disk") and len(value) >= 4:
                                tags.setdefault("track" if item == b"trkn" else "disc",
                                                struct.unpack(">H", value[2:4])[0] or None)
                            elif item == b"covr" and cover and not tags.get("cover"):
                                _set_cover(tags, value, "image/png" if dtype == 14 else "image/jpeg")
                            break


def _ogg_packets(f, limit=8 << 20):
    """The first packets of an Ogg stream (comment headers can span pages)."""
    packet, read = b"", 0
    while read < limit:
        head = f.read(27)
        if len(head) < 27 or head[:4] != b"OggS":
            return
        segments = f.read(head[26])
        body = f.read(sum(segments))
        read += 27 + len(segments) + len(body)
        pos = 0
        for lacing in segments:
            packet += body[pos:pos + lacing]
            pos += lacing
            if lacing < 255:
                yield packet
                packet = b""


def _ogg(f, tags, cover):
    rate, pre_skip = 0, 0
    for n, packet in enumerate(_ogg_packets(f)):
        if packet[:8] == b"OpusHead":
            rate, pre_skip = 48000, struct.unpack("<H", packet[10:12])[0]
        elif packet[:7] == b"\x01vorbis":
            rate = struct.unpack("<I", packet[12:16])[0]
        elif packet[:8] == b"OpusTags":
            _vorbis_comments(packet[8:], tags, cover)
            break
        elif packet[:7] == b"\x03vorbis":
            _vorbis_comments(packet[7:], tags, cover)
            break
        if n > 4:
            break
    if rate:  # the last page's granule position is the length in samples
        f.seek(0, 2)
        size = f.tell()
        f.seek(max(0, size - 65536))
        tail = f.read()
        i = tail.rfind(b"OggS")
        if i >= 0 and len(tail) >= i + 14:
            granule = struct.unpack("<q", tail[i + 6:i + 14])[0]
            if granule > 0:
                tags["duration"] = max(0, granule - pre_skip) / rate


def _wav(f, tags):
    f.seek(12)
    rate_bytes = 0
    while True:
        head = f.read(8)
        if len(head) < 8:
            return
        kind, size = head[:4], struct.unpack("<I", head[4:])[0]
        if kind == b"fmt ":
            fmt = f.read(size)
            rate_bytes = struct.unpack("<I", fmt[8:12])[0]
        elif kind == b"data":
            if rate_bytes:
                tags["duration"] = size / rate_bytes
            f.seek(size + (size & 1), 1)
        elif kind == b"LIST":
            body = f.read(size)
            if body[:4] == b"INFO":
                pos = 4
                keys = {b"INAM": "title", b"IART": "artist", b"IPRD": "album", b"ITRK": "track"}
                while pos + 8 <= len(body):
                    k, n = body[pos:pos + 4], struct.unpack("<I", body[pos + 4:pos + 8])[0]
                    if k in keys:
                        _set_text(tags, keys[k], body[pos + 8:pos + 8 + n].decode("latin-1", "replace"))
                    pos += 8 + n + (n & 1)
        else:
            f.seek(size + (size & 1), 1)


def folder_cover(path):
    """cover.jpg / folder.jpg / front.png ... next to the song, as (mime, bytes)."""
    folder = os.path.dirname(os.path.abspath(path))
    try:
        names = os.listdir(folder)
    except OSError:
        return None
    best = None
    for n in names:
        stem, ext = os.path.splitext(n.lower())
        if ext in (".jpg", ".jpeg", ".png") and (stem in COVER_NAMES or stem.startswith("albumart")):
            rank = COVER_NAMES.index(stem) if stem in COVER_NAMES else len(COVER_NAMES)
            if best is None or rank < best[0]:
                best = (rank, os.path.join(folder, n))
    if not best:
        return None
    try:
        with open(best[1], "rb") as f:
            data = f.read(20 << 20)
    except OSError:
        return None
    return ("image/png" if best[1].lower().endswith(".png") else "image/jpeg"), data


def song_cover(path, tags):
    """The song's cover for the TV, as JPEG when it can be converted (what every TV shows)."""
    cover = tags.get("cover") or folder_cover(path)
    if not cover:
        return None
    mime, data = cover
    if mime == "image/jpeg" and len(data) <= 2 << 20:
        return cover
    import tempfile
    with tempfile.NamedTemporaryFile(suffix=".png" if mime == "image/png" else ".jpg") as tmp:
        tmp.write(data)
        tmp.flush()
        jpeg, _w, _h = to_jpeg(tmp.name, (1200, 1200), quality=88)
    return ("image/jpeg", jpeg) if jpeg else cover


def song_line(tags):
    """'Artist · Album' for the window."""
    return " · ".join(x for x in (tags.get("artist") or tags.get("albumartist"), tags.get("album")) if x)


# ---------------------------------------------------------------- music the TV doesn't play

_convert_lock = threading.Lock()


def needs_mp3(tv, mime):
    """True when the TV lists the formats it plays and this one isn't among them (and ffmpeg can help)."""
    return bool(tv) and tv.accepts(mime) is False and mime != "audio/mpeg" and core.have("ffmpeg")


def to_mp3(path, cancel=None):
    """Convert a song to MP3 (320 kbit/s) in the cache; returns the MP3's path. Kept for next time."""
    st = os.stat(path)
    key = hashlib.sha1(f"{path}|{st.st_size}|{st.st_mtime_ns}".encode()).hexdigest()[:16]
    folder = os.path.join(core.CACHE_DIR, "music")
    out = os.path.join(folder, key + ".mp3")
    with _convert_lock:
        if os.path.isfile(out):
            return out
        os.makedirs(folder, exist_ok=True)
        _trim_cache(folder)
        tmp = out + ".part"
        proc = subprocess.Popen(["ffmpeg", "-v", "error", "-nostdin", "-y", "-i", path, "-map", "0:a:0", "-vn",
                                 "-c:a", "libmp3lame", "-b:a", "320k", "-ar", "44100", "-f", "mp3", tmp],
                                stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        while proc.poll() is None:
            try:
                proc.wait(0.3)
            except subprocess.TimeoutExpired:
                if cancel is not None and cancel.is_set():
                    proc.kill()
                    proc.wait()
                    _remove(tmp)
                    raise core.TVError(_("cancelled"))
        if proc.returncode != 0:
            err = (proc.stderr.read() or b"").decode(errors="replace").strip().splitlines()
            _remove(tmp)
            raise core.TVError(_("couldn't convert the song ({error})").format(
                error=err[-1] if err else proc.returncode))
        os.replace(tmp, out)
        return out


def _remove(path):
    try:
        os.remove(path)
    except OSError:
        pass


def _trim_cache(folder, keep=1 << 30):
    """Keep the converted songs under about 1 GB, newest first."""
    try:
        files = [os.path.join(folder, n) for n in os.listdir(folder)]
        files = sorted((f for f in files if os.path.isfile(f)), key=os.path.getmtime, reverse=True)
    except OSError:
        return
    total = 0
    for f in files:
        try:
            total += os.path.getsize(f)
        except OSError:
            continue
        if total > keep:
            _remove(f)

