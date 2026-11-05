"""A strict fake DLNA TV for tests (not a Samsung): it lists formats under other names (audio/x-flac,
video/x-matroska), no PNG and no Ogg/Opus; answers the first Play after each new URL with UPnP error 701
(busy); takes Seek only as ABS_TIME; reports STOPPED while it shows a photo; ends each song by itself
after SONG_SECONDS; and fetches what it's given like a TV does (photos whole, songs and videos in part,
a live stream for a few seconds).

    tv = FakeTV(); tv.location -> URL for the config; tv.didl, tv.fetched, tv.log -> what happened
"""
import html
import re
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SINK = ",".join(f"http-get:*:{m}:*" for m in (
    "video/mp4", "video/x-matroska", "video/mpeg", "video/x-msvideo", "audio/mpeg", "audio/x-flac", "audio/mp4",
    "audio/x-wav", "image/jpeg"))
DESC = """<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><device>
<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>Fake TV</friendlyName>
<manufacturer>TP Vision</manufacturer><modelName>55PUS8</modelName><serviceList>
<service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/AVTransport/ctl</controlURL><eventSubURL>/AVTransport/evt</eventSubURL><SCPDURL>/av.xml</SCPDURL></service>
<service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/RenderingControl/ctl</controlURL><eventSubURL>/RenderingControl/evt</eventSubURL><SCPDURL>/rc.xml</SCPDURL></service>
<service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType><controlURL>/ConnectionManager/ctl</controlURL><SCPDURL>/cm.xml</SCPDURL></service>
</serviceList></device></root>"""


class FakeTV:
    def __init__(self, song_seconds=3):
        self.song_seconds = song_seconds
        self.st = {"uri": "", "state": "NO_MEDIA_PRESENT", "since": 0.0, "dur": 0, "busy": False, "kind": None}
        self.didl, self.fetched, self.log = [], [], []
        self.t0 = time.monotonic()
        tv = self

        class Handler(_Handler):
            owner = tv
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.location = f"http://127.0.0.1:{self.server.server_address[1]}/dmr"

    def t(self):
        return round(time.monotonic() - self.t0, 1)

    def fetch(self, uri, kind):
        """What a TV does with a URL: a photo is read whole, a song or video a bit, a live stream for a while."""
        try:
            req = urllib.request.Request(uri, headers={
                "transferMode.dlna.org": "Interactive" if kind == "image" else "Streaming"})
            with urllib.request.urlopen(req, timeout=10) as r:
                ctype = r.headers.get("Content-Type")
                if kind == "live":
                    data, end = b"", time.time() + 3
                    while time.time() < end:
                        data += r.read(4096)
                else:
                    data = r.read()
            entry = {"t": self.t(), "name": uri.rsplit("/", 1)[-1], "type": ctype, "bytes": len(data),
                     "head": data[:4].hex()}
            if kind == "live":
                entry["ts"] = len(data) > 1000 and all(data[i] == 0x47 for i in range(0, len(data) - 188, 188))
            self.fetched.append(entry)
        except OSError as e:
            self.fetched.append({"t": self.t(), "name": uri.rsplit("/", 1)[-1], "error": str(e)})

    def close(self):
        self.server.shutdown()


class _Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    owner = None

    def log_message(self, *a):
        pass

    def reply(self, code, body="", headers=()):
        data = body.encode()
        self.send_response(code)
        for k, v in headers:
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        self.reply(200, DESC if self.path == "/dmr" else "<scpd><actionList/></scpd>")

    def do_SUBSCRIBE(self):
        self.reply(200, headers=[("SID", "uuid:1"), ("TIMEOUT", "Second-300")])

    def do_UNSUBSCRIBE(self):
        self.reply(200)

    def fault(self, code, text):
        self.reply(500, f"<s:Envelope><s:Body><s:Fault><detail><UPnPError><errorCode>{code}</errorCode>"
                        f"<errorDescription>{text}</errorDescription></UPnPError></detail></s:Fault></s:Body>"
                        "</s:Envelope>")

    def do_POST(self):
        tv, st = self.owner, self.owner.st
        body = self.rfile.read(int(self.headers.get("Content-Length") or 0)).decode()
        action = self.headers.get("SOAPACTION", "").split("#")[-1].strip('"')
        out = ""
        if action not in ("GetTransportInfo", "GetPositionInfo", "GetVolume", "GetProtocolInfo"):
            tv.log.append((tv.t(), action))
        if st["state"] == "PLAYING" and st["kind"] == "audio" and time.monotonic() - st["since"] >= st["dur"]:
            st["state"] = "STOPPED"  # the song ended
        if action == "SetAVTransportURI":
            uri = html.unescape(re.search(r"<CurrentURI>([^<]*)</CurrentURI>", body).group(1))
            meta = html.unescape(re.search(r"<CurrentURIMetaData>(.*)</CurrentURIMetaData>", body, re.S).group(1))
            cls = re.search(r"<upnp:class>([^<]*)", meta).group(1)
            kind = {"object.item.audioItem.musicTrack": "audio", "object.item.imageItem.photo": "image"}.get(cls, "video")
            if uri.endswith(".ts"):
                kind = "live"
            st.update(uri=uri, state="STOPPED", busy=True, kind=kind, dur=tv.song_seconds)
            tv.didl.append({"t": tv.t(), "class": cls, "mime": re.search(r'protocolInfo="[^:]*:[^:]*:([^:]*):', meta).group(1),
                            "protocolInfo": re.search(r'protocolInfo="([^"]*)"', meta).group(1),
                            "title": (re.search(r"<dc:title>([^<]*)", meta) or [None, None])[1],
                            "artist": (re.search(r"<upnp:artist>([^<]*)", meta) or [None, None])[1],
                            "cover": bool(re.search(r"<upnp:albumArtURI", meta))})
            threading.Thread(target=tv.fetch, args=(uri, kind), daemon=True).start()
            art = re.search(r"<upnp:albumArtURI[^>]*>([^<]*)<", meta)
            if art:
                threading.Thread(target=tv.fetch, args=(art.group(1), "image"), daemon=True).start()
        elif action == "Play":
            if st["busy"]:  # still taking the new URL in
                st["busy"] = False
                return self.fault(701, "Transition not available")
            st["state"] = "STOPPED" if st["kind"] == "image" else "PLAYING"  # shows the photo, says STOPPED
            st["since"] = time.monotonic()
        elif action == "Seek":
            if "REL_TIME" in body:
                return self.fault(710, "Seek mode not supported")
        elif action == "Stop":
            st["state"] = "STOPPED"
        elif action == "GetProtocolInfo":
            out = f"<Source></Source><Sink>{SINK}</Sink>"
        elif action == "GetTransportInfo":
            out = f"<CurrentTransportState>{st['state']}</CurrentTransportState>"
        elif action == "GetPositionInfo":
            pos = int(time.monotonic() - st["since"]) if st["state"] == "PLAYING" else 0
            dur = f"0:00:{st['dur']:02d}" if st["kind"] == "audio" else "0:00:00"
            out = (f"<TrackDuration>{dur}</TrackDuration><RelTime>0:00:{pos:02d}</RelTime>"
                   f"<TrackURI>{html.escape(st['uri'])}</TrackURI>")
        elif action == "GetVolume":
            out = "<CurrentVolume>20</CurrentVolume>"
        self.reply(200, f"<s:Envelope><s:Body><u:{action}Response>{out}</u:{action}Response></s:Body></s:Envelope>")
