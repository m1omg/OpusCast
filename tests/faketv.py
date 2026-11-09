"""A strict fake DLNA TV for tests (not a Samsung): it lists formats under other names (audio/x-flac,
video/x-matroska), no PNG and no Ogg/Opus; answers the first Play after each new URL with UPnP error 701
(busy); takes Seek only as ABS_TIME; reports STOPPED while it shows a photo; ends each song by itself
after SONG_SECONDS; and fetches what it's given like a TV does (photos whole, songs and videos in part,
a live stream as long as it shows it).

    tv = FakeTV(); tv.location -> URL for the config; tv.didl, tv.fetched, tv.log -> what happened
    tv.leave()                -> the viewer pressed Back: the TV stops (and drops the live stream)
    tv.pick_from_sources()    -> the viewer chose a media server among the TV's sources: search, browse,
                                 play its first item (the TV's own player: its renderer knows nothing of it)
"""
import html
import http.client
import re
import socket
import socketserver
import threading
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
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
        self.st = {"uri": "", "state": "NO_MEDIA_PRESENT", "since": 0.0, "dur": 0, "busy": False, "kind": None,
                   "gen": 0}
        self.didl, self.fetched, self.log = [], [], []
        self.pulling = None  # the media server item the TV's own player shows
        self.served_from = []  # where the PC's media server is (for pick_from_sources' fallback)
        self.t0 = time.monotonic()
        tv = self

        class Handler(_Handler):
            owner = tv

        class Server(ThreadingHTTPServer):
            def server_bind(self):  # (no reverse DNS lookup of this machine's name: slow where it's unknown)
                socketserver.TCPServer.server_bind(self)
                self.server_name, self.server_port = self.server_address[:2]
        self.server = Server(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.location = f"http://127.0.0.1:{self.server.server_address[1]}/dmr"

    def t(self):
        return round(time.monotonic() - self.t0, 1)

    def fetch(self, uri, kind, showing=None):
        """What a TV does with a URL: a photo is read whole, a song or video a bit, a live stream for as long
        as it's shown (showing() says; the first 3 seconds are checked)."""
        gen = self.st["gen"]
        if showing is None:
            def showing():
                return self.st["gen"] == gen and self.st["state"] in ("PLAYING", "STOPPED") and \
                    not (self.st["state"] == "STOPPED" and self.st.get("left"))
        try:
            req = urllib.request.Request(uri, headers={
                "transferMode.dlna.org": "Interactive" if kind == "image" else "Streaming",
                "getcontentFeatures.dlna.org": "1"})
            with urllib.request.urlopen(req, timeout=10) as r:
                ctype = r.headers.get("Content-Type")
                if kind == "live":
                    data, end = b"", time.time() + 3
                    while time.time() < end and showing():
                        data += r.read(4096)
                else:
                    data = r.read()
                entry = {"t": self.t(), "name": uri.rsplit("/", 1)[-1], "type": ctype, "bytes": len(data),
                         "head": data[:4].hex()}
                if kind == "live":
                    entry["ts"] = len(data) > 1000 and all(data[i] == 0x47 for i in range(0, len(data) - 188, 188))
                self.fetched.append(entry)
                while kind == "live" and showing() and r.read(65536):
                    pass  # watching on
        except (OSError, http.client.HTTPException) as e:  # (the PC went away mid-stream)
            self.fetched.append({"t": self.t(), "name": uri.rsplit("/", 1)[-1], "error": str(e)})

    def leave(self):
        """The viewer pressed Back (or chose another source): the TV stops showing what it shows."""
        self.st.update(state="STOPPED", left=True)
        self.pulling = None
        self.log.append((self.t(), "left"))

    def pick_from_sources(self, fallback=None, timeout=3.0):
        """The viewer opens the TV's sources and chooses the first media server there: found by an SSDP
        search sent to the PC (UPnP 1.1, straight to its port 1900) - or at fallback, a description URL,
        when nothing answers - browsed, and its first item played by the TV's own player. Returns what
        the TV saw: {"found_by", "name", "items", "played"}."""
        seen = {"found_by": None, "name": None, "items": [], "played": False}
        location = self._search_server(timeout)
        seen["found_by"] = "search" if location else None
        if not location and fallback:
            location, seen["found_by"] = fallback, "address"
        if not location:
            return seen
        try:
            with urllib.request.urlopen(location, timeout=5) as r:
                desc = ET.fromstring(r.read())
            seen["name"] = desc.findtext(".//{*}friendlyName")
            ctl = next(urllib.parse.urljoin(location, s.findtext("{*}controlURL"))
                       for s in desc.iterfind(".//{*}service") if "ContentDirectory" in s.findtext("{*}serviceType"))
            env = ('<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>'
                   '<u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1"><ObjectID>0</ObjectID>'
                   '<BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter><StartingIndex>0</StartingIndex>'
                   '<RequestedCount>0</RequestedCount><SortCriteria></SortCriteria></u:Browse></s:Body></s:Envelope>')
            req = urllib.request.Request(ctl, data=env.encode(), headers={
                "Content-Type": 'text/xml; charset="utf-8"',
                "SOAPACTION": '"urn:schemas-upnp-org:service:ContentDirectory:1#Browse"'})
            with urllib.request.urlopen(req, timeout=5) as r:
                result = ET.fromstring(r.read()).findtext(".//{*}Result")
            didl = ET.fromstring(result)
            items = list(didl.iterfind("{*}item"))
            seen["items"] = [i.findtext("{*}title") for i in items]
            if not items:
                return seen
            url = items[0].findtext("{*}res")
        except (OSError, ET.ParseError, StopIteration, AttributeError, TypeError) as e:
            seen["error"] = str(e)
            return seen
        token = object()
        self.pulling = token
        self.st.update(state="STOPPED", left=False, uri="", kind=None, gen=self.st["gen"] + 1)  # its renderer: nothing
        self.log.append((self.t(), "picked from sources"))
        before = len(self.fetched)
        threading.Thread(target=self.fetch, args=(url, "live", lambda: self.pulling is token), daemon=True).start()
        end = time.time() + 6
        while time.time() < end and len(self.fetched) == before:
            time.sleep(0.1)
        seen["played"] = any(f.get("ts") for f in self.fetched[before:])
        return seen

    def _search_server(self, timeout):
        msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 127.0.0.1:1900\r\nMAN: \"ssdp:discover\"\r\n"
               "ST: urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n").encode()
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
                s.bind(("127.0.0.1", 0))
                s.settimeout(0.3)
                end = time.time() + timeout
                s.sendto(msg, ("127.0.0.1", 1900))
                while time.time() < end:
                    try:
                        data, _addr = s.recvfrom(65507)
                    except socket.timeout:
                        s.sendto(msg, ("127.0.0.1", 1900))
                        continue
                    m = re.search(r"^location:\s*(\S+)", data.decode(errors="ignore"), re.I | re.M)
                    if m:
                        return m.group(1)
        except OSError:
            pass
        return None

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
            st.update(uri=uri, state="STOPPED", busy=True, kind=kind, dur=tv.song_seconds, gen=st["gen"] + 1,
                      left=False)
            tv.pulling = None
            tv.didl.append({"t": tv.t(), "class": cls, "url": uri, "mime": re.search(r'protocolInfo="[^:]*:[^:]*:([^:]*):', meta).group(1),
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
            st["since"], st["left"] = time.monotonic(), False
        elif action == "Seek":
            if "REL_TIME" in body:
                return self.fault(710, "Seek mode not supported")
        elif action == "Stop":
            st["state"], st["left"] = "STOPPED", True
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
