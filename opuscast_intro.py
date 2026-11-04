"""Fun mode: a Skynet-style boot intro played on the TV before the movie.

Drawn from scratch with pycairo (no image assets), sound synthesized here,
encoded with ffmpeg. Rendered once and cached in ~/.cache/opuscast/.
"""
import array
import math
import os
import random
import shutil
import subprocess
import sys
import threading
import wave

sys.path.insert(0, os.path.dirname(os.path.realpath(__file__)))
from opuscast_i18n import _  # noqa: E402

VERSION = 1
W, H, FPS, DURATION = 1920, 1080, 30, 9.0
_render_lock = threading.Lock()
CACHE_DIR = os.path.join(os.environ.get("XDG_CACHE_HOME") or os.path.expanduser("~/.cache"), "opuscast")

SUBTITLE = "NEURAL NET-BASED ARTIFICIAL INTELLIGENCE"
FOOTER = "• CYBERDYNE SYSTEMS CORPORATION •"
RED = (0.89, 0.06, 0.09)

# timeline, seconds
STRIPES_START, STRIPES_END = 0.6, 2.4
BOOM = 2.45
LOGO_START, LOGO_END = 2.6, 3.4
TYPE_START, TYPE_END = 3.6, 5.2
FOOTER_START, FOOTER_END = 5.6, 6.4
GLITCH_START, GLITCH_END = 8.0, 8.6

# triangle geometry: apex at (CX, TOP), 45-degree sides, height SIZE
CX, TOP, SIZE = 960, 100, 540
BASE = TOP + SIZE
STRIPE_PERIOD, STRIPE_RED = 20, 13
N_STRIPES = SIZE // STRIPE_PERIOD
# Y cut: diamond depth, arm gap, stem width (as fractions of SIZE)
Y_DEPTH, Y_GAP, Y_STEM = 0.65, 0.085, 0.09
# flicker pattern for the SKYNET word: (start offset, on?)
FLICKER = [(0.0, 1), (0.05, 0), (0.12, 1), (0.16, 0), (0.30, 1), (0.34, 0), (0.38, 1), (0.52, 0), (0.56, 1)]


def available():
    """(ok, reason) - fun mode needs pycairo and ffmpeg."""
    try:
        import cairo  # noqa: F401
    except ImportError:
        return False, _("needs pycairo (python3-cairo on Debian/Mint, python-cairo on Arch/CachyOS)")
    if not shutil.which("ffmpeg"):
        return False, _("needs ffmpeg")
    return True, ""


def _encoder():
    out = subprocess.run(["ffmpeg", "-hide_banner", "-encoders"], capture_output=True, text=True).stdout
    if " libx264 " in out:
        return ".mp4", ["-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p",
                        "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart"]
    if " libopenh264 " in out:
        return ".mp4", ["-c:v", "libopenh264", "-b:v", "8M", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k"]
    return ".webm", ["-c:v", "libvpx-vp9", "-b:v", "6M", "-pix_fmt", "yuv420p", "-c:a", "libopus", "-b:a", "160k"]


def cached_path():
    for ext in (".mp4", ".webm"):
        p = os.path.join(CACHE_DIR, f"skynet-intro-v{VERSION}{ext}")
        if os.path.exists(p):
            return p
    return None


def ensure_intro(progress=None):
    """Return the path of the intro video, rendering it first if needed."""
    with _render_lock:
        return _ensure_intro(progress)


def _ensure_intro(progress):
    path = cached_path()
    if path:
        return path
    ok, reason = available()
    if not ok:
        raise RuntimeError(_("Skynet intro {reason}").format(reason=reason))
    os.makedirs(CACHE_DIR, exist_ok=True)
    ext, codec = _encoder()
    out = os.path.join(CACHE_DIR, f"skynet-intro-v{VERSION}{ext}")
    tmp_out, wav = out + ".part" + ext, os.path.join(CACHE_DIR, "skynet-intro.wav")
    _write_audio(wav)
    pix = "bgra" if sys.byteorder == "little" else "argb"
    cmd = ["ffmpeg", "-loglevel", "error", "-y", "-f", "rawvideo", "-pix_fmt", pix, "-s", f"{W}x{H}",
           "-r", str(FPS), "-i", "-", "-i", wav, *codec, "-shortest", tmp_out]
    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stderr=subprocess.PIPE)
    r = _Renderer()
    frames = int(DURATION * FPS)
    try:
        for i in range(frames):
            proc.stdin.write(r.frame(i / FPS))
            if progress:
                progress((i + 1) / frames)
        proc.stdin.close()
    except BrokenPipeError:
        pass
    err = proc.stderr.read().decode(errors="ignore")
    if proc.wait() != 0:
        raise RuntimeError(f"ffmpeg failed: {err.strip()[-300:]}")
    os.replace(tmp_out, out)
    os.remove(wav)
    return out


# ---------------------------------------------------------------- picture

def _pt(u, v):
    return CX + u * SIZE, TOP + v * SIZE


def _pieces():
    """The three red pieces of the triangle, split by the Y."""
    d, c, w = Y_DEPTH, Y_DEPTH + Y_GAP * math.sqrt(2), Y_STEM
    top = [(0, 0), (-d / 2, d / 2), (0, d), (d / 2, d / 2)]
    left = [(-c / 2, c / 2), (-w / 2, c - w / 2), (-w / 2, 1), (-1, 1)]
    right = [(-x, y) for x, y in left]
    return [[_pt(u, v) for u, v in poly] for poly in (top, left, right)]


def _path_pieces(ctx):
    for poly in _pieces():
        ctx.move_to(*poly[0])
        for p in poly[1:]:
            ctx.line_to(*p)
        ctx.close_path()


def _glyphs(ctx, x, y, h, t):
    """Draw SKYNET in a wide geometric face, as stroked centrelines. Returns total width."""
    import cairo
    a = t / 2
    hh = h / 2 - a
    r = hh * 0.45

    def S(w):
        ctx.move_to(w, a)
        ctx.line_to(a + r, a)
        ctx.arc_negative(a + r, a + r, r, -math.pi / 2, -math.pi)
        ctx.line_to(a, h / 2 - r)
        ctx.arc_negative(a + r, h / 2 - r, r, math.pi, math.pi / 2)
        ctx.line_to(w - a - r, h / 2)
        ctx.arc(w - a - r, h / 2 + r, r, -math.pi / 2, 0)
        ctx.line_to(w - a, h - a - r)
        ctx.arc(w - a - r, h - a - r, r, 0, math.pi / 2)
        ctx.line_to(0, h - a)

    def K(w):
        ctx.move_to(a, 0); ctx.line_to(a, h)
        px, py, qx, qy = a, h * 0.64, w - a + t, -t
        ctx.move_to(px, py); ctx.line_to(qx, qy)
        mx, my = px + (qx - px) * 0.36, py + (qy - py) * 0.36
        ctx.move_to(mx, my); ctx.line_to(w - a + t * 0.7, h + t)

    def Y(w):
        ctx.move_to(a - t, -t); ctx.line_to(w / 2, h * 0.52)
        ctx.move_to(w - a + t, -t); ctx.line_to(w / 2, h * 0.52)
        ctx.move_to(w / 2, h * 0.45); ctx.line_to(w / 2, h)

    def N(w):
        ctx.move_to(a, h); ctx.line_to(a, a * 0.2)
        ctx.line_to(w - a, h - a * 0.2); ctx.line_to(w - a, 0)

    def E(w):
        ctx.move_to(w, a)
        ctx.line_to(a + r, a)
        ctx.arc_negative(a + r, a + r, r, -math.pi / 2, -math.pi)
        ctx.line_to(a, h - a - r)
        ctx.arc_negative(a + r, h - a - r, r, math.pi, math.pi / 2)
        ctx.line_to(w, h - a)
        ctx.move_to(a, h / 2); ctx.line_to(w * 0.86, h / 2)

    def T(w):
        ctx.move_to(0, a); ctx.line_to(w, a)
        ctx.move_to(w / 2, 0); ctx.line_to(w / 2, h)

    letters = [(S, 152), (K, 150), (Y, 158), (N, 158), (E, 142), (T, 150)]
    gap = 30
    total = sum(w for _, w in letters) + gap * (len(letters) - 1)
    ctx.save()
    ctx.translate(x - total / 2, y)
    ctx.set_line_width(t)
    ctx.set_line_cap(cairo.LINE_CAP_BUTT)
    ctx.set_line_join(cairo.LINE_JOIN_MITER)
    for draw, w in letters:
        ctx.save()
        ctx.rectangle(0, 0, w, h)
        ctx.clip()
        draw(w)
        ctx.stroke()
        ctx.restore()
        ctx.translate(w + gap, 0)
    ctx.restore()
    return total


class _Renderer:
    def __init__(self):
        import cairo
        self.cairo = cairo
        self.surface = cairo.ImageSurface(cairo.FORMAT_ARGB32, W, H)
        self.logo = self._layer(self._draw_logo)
        self.glow = self._layer(self._draw_glow)
        self.word = self._layer(lambda c: (c.set_source_rgb(1, 1, 1), _glyphs(c, CX, 672, 118, 30)))
        self.footer = self._layer(self._draw_footer)
        self.overlay = self._layer(self._draw_overlay)
        self.sub_font = None

    def _layer(self, draw):
        s = self.cairo.ImageSurface(self.cairo.FORMAT_ARGB32, W, H)
        c = self.cairo.Context(s)
        draw(c)
        return s

    def _draw_logo(self, c):
        _path_pieces(c)
        c.clip()
        grad = self.cairo.LinearGradient(0, TOP, 0, BASE)
        grad.add_color_stop_rgb(0, 1.0, 0.16, 0.14)
        grad.add_color_stop_rgb(1, 0.80, 0.02, 0.06)
        c.set_source(grad)
        for k in range(N_STRIPES):
            y = BASE - (k + 1) * STRIPE_PERIOD + (STRIPE_PERIOD - STRIPE_RED)
            c.rectangle(CX - SIZE, y, 2 * SIZE, STRIPE_RED)
        c.fill()

    def _draw_glow(self, c):
        def triangle():
            c.move_to(*_pt(0, 0)); c.line_to(*_pt(-1, 1)); c.line_to(*_pt(1, 1)); c.close_path()
        c.rectangle(0, 0, W, H)  # glow only outside the triangle, so the Y gaps stay black
        triangle()
        c.set_fill_rule(self.cairo.FILL_RULE_EVEN_ODD)
        c.clip()
        for width, alpha in ((110, 0.035), (70, 0.045), (36, 0.06)):
            triangle()
            c.set_source_rgba(*RED, alpha)
            c.set_line_width(width)
            c.set_line_join(self.cairo.LINE_JOIN_ROUND)
            c.stroke()

    def _draw_footer(self, c):
        c.select_font_face("Sans", self.cairo.FONT_SLANT_NORMAL, self.cairo.FONT_WEIGHT_NORMAL)
        c.set_font_size(28)
        spacing = 5
        widths = [c.text_extents(ch).x_advance + spacing for ch in FOOTER]
        x = CX - (sum(widths) - spacing) / 2
        c.set_source_rgb(*RED)
        for ch, w in zip(FOOTER, widths):
            c.move_to(x, 985)
            c.show_text(ch)
            x += w

    def _draw_overlay(self, c):
        c.set_source_rgba(0, 0, 0, 0.22)
        for y in range(0, H, 3):
            c.rectangle(0, y, W, 1)
        c.fill()
        vig = self.cairo.RadialGradient(CX, H / 2, H * 0.35, CX, H / 2, H * 1.05)
        vig.add_color_stop_rgba(0, 0, 0, 0, 0)
        vig.add_color_stop_rgba(1, 0, 0, 0, 0.6)
        c.set_source(vig)
        c.paint()

    def _subtitle_font(self, c):
        c.select_font_face("Sans", self.cairo.FONT_SLANT_NORMAL, self.cairo.FONT_WEIGHT_BOLD)
        size = 46
        c.set_font_size(size)
        squeeze = min(1.0, 990 / c.text_extents(SUBTITLE).x_advance)
        c.set_font_matrix(self.cairo.Matrix(xx=size * squeeze, yy=size))

    def frame(self, t):
        cairo = self.cairo
        c = cairo.Context(self.surface)
        c.set_source_rgb(0, 0, 0)
        c.paint()
        if t < GLITCH_END:
            self._content(c, t)
            if t >= GLITCH_START:
                self._glitch(c, t)
            c.set_source_surface(self.overlay)
            c.paint()
        self.surface.flush()
        return bytes(self.surface.get_data())

    def _content(self, c, t):
        # boom: shake + flash
        shake = max(0.0, 1 - (t - BOOM) / 0.3) if t >= BOOM else 0
        rnd = random.Random(int(t * FPS))
        dx, dy = (rnd.uniform(-1, 1) * 14 * shake, rnd.uniform(-1, 1) * 10 * shake)
        c.save()
        c.translate(dx, dy)

        # stripes revealed bottom-up, each flashing as it appears
        step = (STRIPES_END - STRIPES_START) / N_STRIPES
        shown = min(N_STRIPES, int((t - STRIPES_START) / step) + 1) if t >= STRIPES_START else 0
        if shown:
            front = BASE - shown * STRIPE_PERIOD
            c.set_source_surface(self.glow)
            c.paint_with_alpha(shown / N_STRIPES)
            c.save()
            c.rectangle(0, front, W, H)
            c.clip()
            c.set_source_surface(self.logo)
            c.paint()
            c.restore()
            c.save()
            _path_pieces(c)
            c.clip()
            for k in range(shown):
                age = t - (STRIPES_START + k * step)
                if age < 0.2:
                    y = BASE - (k + 1) * STRIPE_PERIOD + (STRIPE_PERIOD - STRIPE_RED)
                    c.set_source_rgba(1, 0.85, 0.8, 0.85 * (1 - age / 0.2))
                    c.rectangle(0, y, W, STRIPE_RED)
                    c.fill()
            if shake:
                c.set_source_rgba(1, 0.9, 0.9, 0.45 * shake)
                c.paint()
            c.restore()
            if t < STRIPES_END + 0.1:  # scanning line at the reveal front
                c.set_source_rgba(1, 0.4, 0.35, 0.9)
                half = (BASE - front) + 40
                c.rectangle(CX - half, front - 2, 2 * half, 3)
                c.fill()

        # SKYNET flickers on
        if t >= LOGO_START:
            on = 0
            for start, state in FLICKER:
                if t - LOGO_START >= start:
                    on = state
            if on:
                c.set_source_surface(self.word)
                c.paint()
        c.restore()

        # subtitle typed out, then cursor drops to the next line and blinks
        if t >= TYPE_START - 0.4:
            self._subtitle_font(c)
            full = c.text_extents(SUBTITLE).x_advance
            x0 = CX - full / 2
            n = max(0, min(len(SUBTITLE), int((t - TYPE_START) / (TYPE_END - TYPE_START) * len(SUBTITLE)) + 1)) \
                if t >= TYPE_START else 0
            c.set_source_rgb(1, 1, 1)
            c.move_to(x0, 850)
            c.show_text(SUBTITLE[:n])
            if t < TYPE_END + 0.15:
                cx, cy = x0 + c.text_extents(SUBTITLE[:n]).x_advance + 6, 815
                blink = True if n < len(SUBTITLE) and t >= TYPE_START else int(t * 4) % 2 == 0
            else:
                cx, cy = CX - SIZE, 900
                blink = int((t - TYPE_END) * 2) % 2 == 0
            if blink:
                c.rectangle(cx, cy, 6, 36)
                c.fill()

        if t >= FOOTER_START:
            c.set_source_surface(self.footer)
            c.paint_with_alpha(min(1.0, (t - FOOTER_START) / (FOOTER_END - FOOTER_START)))

    def _glitch(self, c, t):
        cairo = self.cairo
        rnd = random.Random(1000 + int(t * FPS))
        progress = (t - GLITCH_START) / (GLITCH_END - GLITCH_START)
        snap = cairo.ImageSurface(cairo.FORMAT_ARGB32, W, H)
        sc = cairo.Context(snap)
        sc.set_source_surface(self.surface)
        sc.paint()
        for _ in range(int(6 + 14 * progress)):
            y, h = rnd.randrange(0, H), rnd.randrange(8, 90)
            shift = rnd.choice((-1, 1)) * rnd.uniform(20, 260) * (0.4 + progress)
            c.save()
            c.rectangle(0, y, W, h)
            c.clip()
            c.set_source_rgb(0, 0, 0)
            c.paint()
            c.set_source_surface(snap, shift, 0)
            c.paint()
            if rnd.random() < 0.35:
                c.set_source_rgba(*rnd.choice(((0, 1, 1), (1, 0, 0.2), (1, 1, 1))), 0.25)
                c.paint()
            c.restore()
        if progress > 0.6 or rnd.random() < 0.15:
            c.set_source_rgba(0, 0, 0, min(1.0, max(0.5, (progress - 0.6) / 0.4)))
            c.paint()


# ---------------------------------------------------------------- sound

def _write_audio(path, rate=48000):
    n = int(DURATION * rate)
    buf = [0.0] * n
    rnd = random.Random(7)

    def add(start, length, fn):
        i0 = int(start * rate)
        for i in range(max(0, i0), min(n, i0 + int(length * rate))):
            buf[i] += fn((i - i0) / rate)

    # low drone with a slow wobble
    def drone(x):
        env = min(1.0, x / 1.2) * (1.0 if x < GLITCH_START else max(0.0, 1 - (x - GLITCH_START) / 0.8))
        saw = 2 * ((82.41 * x) % 1.0) - 1
        wob = 0.75 + 0.25 * math.sin(2 * math.pi * 0.35 * x)
        return env * wob * (0.16 * math.sin(2 * math.pi * 55 * x) + 0.08 * math.sin(2 * math.pi * 110.4 * x)
                            + 0.035 * saw)
    add(0, DURATION, drone)

    # rising blip per stripe
    step = (STRIPES_END - STRIPES_START) / N_STRIPES
    for k in range(N_STRIPES):
        f = 420 + 26 * k
        add(STRIPES_START + k * step, 0.05, lambda x, f=f: 0.07 * math.sin(2 * math.pi * f * x) * math.exp(-x / 0.015))

    # boom
    add(BOOM, 1.6, lambda x: 0.55 * math.sin(2 * math.pi * (42 + 40 * math.exp(-6 * x)) * x) * math.exp(-2.6 * x)
        + 0.22 * rnd.uniform(-1, 1) * math.exp(-x / 0.03))

    # electric buzz while SKYNET flickers
    def buzz(x):
        on = 0
        for start, state in FLICKER:
            if x >= start:
                on = state
        sq = 1.0 if (120 * x) % 1.0 < 0.5 else -1.0
        return on * 0.05 * sq * math.exp(-max(0.0, x - 0.56) * 3)
    add(LOGO_START, 1.2, buzz)

    # typing ticks
    per = (TYPE_END - TYPE_START) / len(SUBTITLE)
    for i, ch in enumerate(SUBTITLE):
        if ch != " ":
            add(TYPE_START + i * per, 0.03, lambda x: (0.25 * rnd.uniform(-1, 1) * math.exp(-x / 0.002)
                                                      + 0.1 * math.sin(2 * math.pi * 2300 * x) * math.exp(-x / 0.004)))

    # soft chime for the footer
    add(FOOTER_START, 2.0, lambda x: 0.09 * (math.sin(2 * math.pi * 880 * x) + 0.6 * math.sin(2 * math.pi * 1318.5 * x))
        * math.exp(-x * 1.8) * min(1.0, x / 0.01))

    # glitch crackle
    for j in range(10):
        add(GLITCH_START + j * 0.06, 0.04, lambda x: 0.2 * rnd.uniform(-1, 1) * math.exp(-x / 0.012))

    peak = max(1e-9, max(abs(v) for v in buf))
    gain = min(1.0, 0.92 / peak)
    pcm = array.array("h", (int(max(-1.0, min(1.0, v * gain)) * 32767) for v in buf))
    if sys.byteorder == "big":
        pcm.byteswap()
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(pcm.tobytes())


if __name__ == "__main__":
    if len(sys.argv) > 2 and sys.argv[1] == "--frames":  # debug: dump PNG stills
        import cairo
        r = _Renderer()
        os.makedirs(sys.argv[2], exist_ok=True)
        for ts in sys.argv[3:]:
            r.frame(float(ts))
            r.surface.write_to_png(os.path.join(sys.argv[2], f"frame-{ts}.png"))
    else:
        print(ensure_intro(lambda p: print(f"\rrendering {p:4.0%}", end="", flush=True)))
