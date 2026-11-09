"""End-to-end check of the command-line OpusCast against a fake TV (tests/faketv.py), on any system
with Python and ffmpeg:  python tests/smoke.py [--desktop]

Songs (one in a format this TV doesn't list, so it's converted), a photo slideshow, OpusCast among the
TV's sources and, with --desktop, casting the screen: the TV leaves it and comes back (from its sources,
and with Enter). Exit status 0 when the TV got what it should have."""
import json
import os
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
sys.path.insert(0, ROOT)
from faketv import FakeTV  # noqa: E402

import opuscast_core as core  # noqa: E402

failures = []


def check(ok, what):
    print(("  ok    " if ok else "  FAIL  ") + what, flush=True)
    if not ok:
        failures.append(what)


def ffmpeg(*args):
    subprocess.run(["ffmpeg", "-v", "error", "-y", *args], check=True)


def run_cli(env, args, seconds=None):
    """The CLI until it ends (or, for what runs forever, for seconds)."""
    proc = subprocess.Popen([sys.executable, os.path.join(ROOT, "opuscast"), *args], env=env,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8",
                            errors="replace")
    try:
        out, _err = proc.communicate(timeout=seconds or 120)
    except subprocess.TimeoutExpired:
        proc.terminate()
        out, _err = proc.communicate(timeout=15)
    print("    | " + "\n    | ".join(out.strip().splitlines()[-8:]), flush=True)
    return proc.returncode, out


class Cli:
    """The command line running in the background: its output as it comes, Enter on its keyboard."""

    def __init__(self, env, args):
        env = {k: v for k, v in env.items() if k != "PYTHONUNBUFFERED"}  # it has to show its lines by itself
        self.proc = subprocess.Popen([sys.executable, os.path.join(ROOT, "opuscast"), *args], env=env,
                                     stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                     text=True, encoding="utf-8", errors="replace", bufsize=1)
        self.lines = []
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self):
        for line in self.proc.stdout:
            self.lines.append(line.rstrip("\n"))

    def wait_for(self, text, timeout, after=0):
        """True once a line (from line number after on) contains text."""
        end = time.time() + timeout
        while time.time() < end:
            if any(text in line for line in self.lines[after:]):
                return True
            if self.proc.poll() is not None:
                time.sleep(0.3)
                return any(text in line for line in self.lines[after:])
            time.sleep(0.2)
        return False

    def enter(self):
        self.proc.stdin.write("\n")
        self.proc.stdin.flush()

    def stop(self):
        if self.proc.poll() is None:
            self.proc.terminate()
        try:
            self.proc.wait(timeout=15)
        except subprocess.TimeoutExpired:
            self.proc.kill()
        print("    | " + "\n    | ".join(self.lines[-12:]), flush=True)
        return "\n".join(self.lines)


def fallback_location(tv):
    """Where OpusCast's media server is on this PC, for a TV whose search nobody answers (port 1900 can be
    taken by the system's own SSDP service)."""
    port = next((d["port"] for d in tv.served_from if d.get("port")), core.DEFAULT_PORT)
    return f"http://127.0.0.1:{port}/dms/{core.source_uuid()}/description.xml"


def tv_source_checks(tv):
    """OpusCast among the TV's sources, without screen capture: a synthetic live stream, the fake TV
    finding the media server, browsing it and playing what it lists; another device seeing nothing."""
    print("tv source", flush=True)
    live = core.LiveStream()
    enc = subprocess.Popen(["ffmpeg", "-v", "error", "-re", "-f", "lavfi", "-i", "testsrc2=s=640x360:r=25",
                            "-c:v", "mpeg2video", "-g", "12", "-b:v", "2M", "-f", "mpegts", "pipe:1"],
                           stdout=subprocess.PIPE)

    def pump():
        for chunk in iter(lambda: enc.stdout.read(4096), b""):
            live.feed(chunk)
    threading.Thread(target=pump, daemon=True).start()
    server = core.MediaServer()
    try:
        check(live.wait_started(15), "a live stream to share")
        media = server.publish(live=live, title="Desktop")
        source = core.TVSource(server, "127.0.0.1", media)
        tv.served_from = [{"port": server.port}]
        seen = tv.pick_from_sources(fallback=fallback_location(tv))
        if seen["found_by"] == "address":
            print("  note  the search wasn't answered here (port 1900 taken?) - TVs still hear OpusCast's "
                  "announcements", flush=True)
        check(seen["name"] == source.name, f"the TV found “{source.name}” among its sources ({seen['found_by']})")
        check(seen["items"] == ["Desktop"], f"its folder lists the desktop ({seen['items']})")
        check(seen["played"], "the TV played the desktop from there (MPEG-TS)")
        check(live.watched(), "OpusCast sees the TV watching")
        tv.leave()
        end = time.time() + 8
        while time.time() < end and live.watched():
            time.sleep(0.2)
        check(not live.watched(), "... and sees it leave")
        try:
            stranger = core.TVSource(server, "127.0.0.2", media)  # requests from 127.0.0.1 aren't that TV's
        except OSError:
            stranger = None
        if stranger is not None:
            source.close()
            seen = tv.pick_from_sources(fallback=stranger.location, timeout=1.0)
            check(seen["name"] == stranger.name and seen["items"] == [], "another device sees an empty folder")
            stranger.close()
        source.close()
        try:
            urllib.request.urlopen(source.location, timeout=3)
            gone = False
        except urllib.error.HTTPError as e:
            gone = e.code == 404
        except OSError:
            gone = True
        check(gone, "when sharing ends, OpusCast is gone from the TV's sources")
    finally:
        enc.kill()
        server.close()
    # the decisions behind it, on a made-up timeline
    media = {"url_path": "/media/x.ts", "live": None}
    watch, events = core.ShareWatch(media, bring_back=True), []
    for t, state in ((0, "PLAYING"), (60, "STOPPED"), (63, "STOPPED"), (66, "PLAYING"), (70, "STOPPED"),
                     (73, "STOPPED"), (1900, "STOPPED")):
        ev = watch.update(state, "http://pc/media/x.ts", now=t)
        if ev:
            events.append(ev)
    check(events == ["shown", "send", "back", "left", "idle"],
          f"left by mistake: brought back; left again at once: stays off; 30 min unwatched: stops ({events})")


def desktop_checks(env, tv):
    """Casting the screen; the TV leaves it, picks it from its sources, leaves again, Enter brings it back."""
    print("desktop", flush=True)
    before, sets = len(tv.fetched), len(tv.didl)
    cli = Cli(env, ["--desktop", "--size", "720"])
    started = cli.wait_for("Showing the desktop", 60)
    if not started or any("Screen Recording" in line for line in cli.lines):
        out = cli.stop()
        if "Screen Recording" in out:
            print("  skip  macOS hasn't allowed screen recording on this machine (expected on CI)", flush=True)
        else:
            check(False, "the TV got a live MPEG-TS stream of the screen")
        return
    end = time.time() + 20
    while time.time() < end and not any("ts" in f for f in tv.fetched[before:]):
        time.sleep(0.2)
    live = [f for f in tv.fetched[before:] if "ts" in f]
    check(bool(live) and live[0]["ts"], "the TV got a live MPEG-TS stream of the screen")
    hint = cli.wait_for("in the TV's sources to go back", 5)
    check(hint, "OpusCast says how to come back from the TV")
    tv.served_from = [{"port": int(d["url"].split(":")[2].split("/")[0])} for d in tv.didl[sets:] if d.get("url")]

    n = len(cli.lines)
    tv.leave()
    check(cli.wait_for("The TV left the desktop", 15, n) and cli.proc.poll() is None,
          "the TV left it: still shared, and OpusCast says so")
    n = len(cli.lines)
    seen = tv.pick_from_sources(fallback=fallback_location(tv))
    check(seen["played"], f"the TV picked “{seen['name']}” from its sources and shows the desktop again "
                          f"({seen['found_by']})")
    check(cli.wait_for("Back on the TV", 10, n), "OpusCast noticed it's back on the TV")

    n = len(cli.lines)
    tv.leave()
    check(cli.wait_for("The TV left the desktop", 15, n), "left again")
    n, sets = len(cli.lines), len(tv.didl)
    cli.enter()
    check(cli.wait_for("Back on the TV", 20, n) and len(tv.didl) > sets and tv.didl[-1]["mime"] in ("video/mpeg",),
          "Enter sent it to the TV again, and it's back")
    cli.stop()


def main():
    work = tempfile.mkdtemp(prefix="opuscast-smoke-")
    media = os.path.join(work, "media")
    os.makedirs(media)
    ffmpeg("-f", "lavfi", "-i", "testsrc2=s=320x320", "-frames:v", "1", os.path.join(media, "cover.jpg"))
    ffmpeg("-f", "lavfi", "-i", "sine=f=440:d=4", "-c:a", "libmp3lame", "-metadata", "title=Song One",
           "-metadata", "artist=Smoke", "-metadata", "album=Test", os.path.join(media, "01 one.mp3"))
    ffmpeg("-f", "lavfi", "-i", "sine=f=660:d=4", "-c:a", "libopus", "-metadata", "title=Song Two",
           os.path.join(media, "02 two.opus"))
    ffmpeg("-f", "lavfi", "-i", "testsrc2=s=800x600", "-frames:v", "1", os.path.join(media, "photo1.png"))
    ffmpeg("-f", "lavfi", "-i", "testsrc2=s=1024x768", "-frames:v", "1", os.path.join(media, "photo2.jpg"))

    tv = FakeTV(song_seconds=3)
    cfg = os.path.join(work, "config")
    os.makedirs(os.path.join(cfg, "opuscast"))
    with open(os.path.join(cfg, "opuscast", "config.json"), "w") as f:
        json.dump({"tv": {"location": tv.location, "name": "Fake TV"}}, f)
    env = dict(os.environ, XDG_CONFIG_HOME=cfg, XDG_CACHE_HOME=os.path.join(work, "cache"), OPUSCAST_LANG="en",
               PYTHONIOENCODING="utf-8")

    print("songs", flush=True)
    code, _out = run_cli(env, [os.path.join(media, "01 one.mp3"), os.path.join(media, "02 two.opus")])
    songs = [d for d in tv.didl if d["class"].endswith("musicTrack")]
    check(code == 0, "the command finished")
    check(len(songs) == 2, "both songs went to the TV, one after the other")
    check(songs[:1] and songs[0]["artist"] == "Smoke" and songs[0]["cover"], "title, artist and cover went along")
    check(len(songs) > 1 and songs[1]["mime"] == "audio/mpeg", "the Opus song was converted to MP3 for this TV")
    check(any(f.get("type") == "audio/mpeg" and f.get("bytes", 0) > 10000 for f in tv.fetched),
          "the TV could fetch the songs")

    print("photos", flush=True)
    before = len(tv.didl)
    run_cli(env, ["--every", "2", os.path.join(media, "photo1.png"), os.path.join(media, "photo2.jpg")], seconds=7)
    photos = [d for d in tv.didl[before:] if d["class"].endswith("photo")]
    check(len(photos) >= 2, "the slideshow moved on")
    check(all(d["mime"] == "image/jpeg" for d in photos), "the PNG was sent as JPEG (this TV lists no PNG)")
    check(any(f.get("type") == "image/jpeg" and f.get("head", "").startswith("ffd8") for f in tv.fetched),
          "the TV could fetch the photos")

    tv_source_checks(tv)
    if "--desktop" in sys.argv:
        desktop_checks(env, tv)
    tv.close()
    print("FAILED: " + "; ".join(failures) if failures else "all good", flush=True)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
