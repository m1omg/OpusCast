"""End-to-end check of the command-line OpusCast against a fake TV (tests/faketv.py), on any system
with Python and ffmpeg:  python tests/smoke.py [--desktop]

Songs (one in a format this TV doesn't list, so it's converted), a photo slideshow and, with --desktop,
casting the screen. Exit status 0 when the TV got what it should have."""
import json
import os
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
from faketv import FakeTV  # noqa: E402

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

    if "--desktop" in sys.argv:
        print("desktop", flush=True)
        before = len(tv.fetched)
        code, out = run_cli(env, ["--desktop", "--size", "720"], seconds=12)
        live = [f for f in tv.fetched[before:] if "ts" in f]
        if "Screen Recording" in out:
            print("  skip  macOS hasn't allowed screen recording on this machine (expected on CI)", flush=True)
        else:
            check(bool(live) and live[0]["ts"], "the TV got a live MPEG-TS stream of the screen")
    tv.close()
    print("FAILED: " + "; ".join(failures) if failures else "all good", flush=True)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
