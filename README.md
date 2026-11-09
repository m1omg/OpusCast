# OpusCast

Play videos, music and photos from your Linux PC on your smart TV, or show it your desktop.
No apps on the TV, no servers to set up. OpusCast hands the file to the TV over your home network
(DLNA / UPnP), so the **TV remote works**: pause, rewind and volume all work as usual.

- **Drop a video in the window** (or pick one, or right-click a video → *Open With → OpusCast*)
- **Music:** pick or drop songs (or a folder) and they play one after another, with title, artist,
  album and cover on the TV. MP3, FLAC, M4A/AAC, Ogg, Opus, WAV, WMA. A song in a format the TV doesn't
  list (often Opus or Ogg) is converted to MP3 first (needs ffmpeg). DLNA speakers play music too.
- **Photos:** a slideshow on the TV: pick one photo to flip through its whole folder, or pick several.
  JPEG, PNG, HEIC, WebP, AVIF, GIF, BMP, TIFF. Photos are turned upright, and ones the TV can't
  show (HEIC from phones, huge ones) are converted to JPEG on the way.
- **Cast the desktop:** the PC's screen and sound live on the TV, a second or two behind, so it's
  for slides, photos and videos rather than games. Works on X11 (Cinnamon, MATE, Xfce, …) by grabbing the
  screen directly, and on Wayland (GNOME, KDE Plasma, …) through the desktop's screen-sharing dialog.
  Choose 720p or 1080p, 30 or 60 frames a second, and which screen. Uses the graphics card's H.264
  encoder when x264 isn't available.
- **Left the desktop on the TV by mistake?** It stays shared, and the TV remote brings it back: while
  you share, OpusCast is among the TV's sources as “OpusCast on *your PC*” (Samsung: *Source*; LG,
  Philips and others: their media player), with the desktop in it. Or click *Show on the TV again* in
  the window (Enter in the command line). With *Bring it back if the TV leaves* (desktop options,
  `--bring-back`) it comes back by itself; leave it again right away and it stays off. Only the TV you
  share to sees your desktop there, and nobody watching for 30 minutes ends sharing.
- **Picks up where you left off.** Recent videos remember their position and their subtitles.
- **Subtitles from the PC:** subtitle files next to the video and text subtitles inside it.
  Switch them or turn them off while the movie plays; on Samsung TVs this happens instantly.
- **Show on PC:** an optional muted copy of the video in the window, kept in step with the TV.
  Drag the seek bar and you see where you're jumping before you let go.
- **Device picker:** scans your network and shows which devices can play video (TVs), which
  are audio only (speakers), and which can't play anything (router, phones, …)
- **Skynet intro (fun mode):** a Skynet-style boot animation before the movie 🤖
- **English and Slovak.** OpusCast follows the system language; `OPUSCAST_LANG=en` or `sk` overrides it.
- Also a command-line version: `opuscast VIDEO`, `opuscast VIDEO --sub auto`, `opuscast SONGS…`,
  `opuscast PHOTOS… --every 8`, `opuscast --desktop`, `opuscast --list`, `opuscast --stop`
  (`opuscast --help` lists everything)

Tested with a Samsung QLED (Tizen). Other TVs with a DLNA player (LG, Sony, Philips, Hisense,
Panasonic, Android TV sets) should work too: OpusCast reads which formats each TV lists and uses
that TV's own names for them, retries when a TV is still busy, and seeks the way the TV accepts.

- **Philips:** turn on *Digital Media Renderer – DMR* (All Settings → Wireless and Networks →
  Wired or Wi-Fi). It's on from the factory, but if your TV isn't found, check it.
- **Chromecast, Google TV streamers, Nvidia Shield** only speak Google Cast, not DLNA, so OpusCast
  can't use them. An Android TV *set* (Sony, Philips, …) usually has DLNA as well.

**Android:** there's an Android version too, in [android/](android/README.md).

**Windows and macOS:** the same app for Windows 10/11 and for Macs with macOS 12 Monterey or newer
(Intel and Apple silicon), see [below](#windows-and-macos).

## Install

```sh
git clone https://github.com/m1omg/OpusCast.git
cd OpusCast
./install.sh            # per-user install into ~/.local, no root
```

Update with `git pull && ./install.sh`, remove with `./install.sh --uninstall`.

The installer checks what's missing and prints the right command for your distro.

| Part | Debian / Ubuntu / Mint / LMDE | Arch / CachyOS / Manjaro | Fedora |
|---|---|---|---|
| CLI | `python3` | `python` | `python3` |
| GUI | `python3-gi gir1.2-gtk-3.0` | `python-gobject gtk3` | `python3-gobject gtk3` |
| Show on PC | `gir1.2-gstreamer-1.0 gstreamer1.0-plugins-good gstreamer1.0-gtk3 gstreamer1.0-gl gstreamer1.0-libav` | `gst-plugins-good gst-plugin-gtk gst-libav` | `gstreamer1-plugins-good gstreamer1-plugins-good-gtk gstreamer1-plugin-libav` |
| Smooth 4K preview | `gstreamer1.0-plugins-bad` | `gst-plugin-va` | `gstreamer1-plugins-bad-free` |
| Subtitles inside videos, non-SRT files | `ffmpeg` | `ffmpeg` | `ffmpeg-free` |
| Skynet intro | `python3-cairo ffmpeg` | `python-cairo ffmpeg` | `python3-cairo ffmpeg-free` |
| Cast the desktop | `ffmpeg` | `ffmpeg` | `ffmpeg-free` |
| … on Wayland, also | `gir1.2-gstreamer-1.0 gstreamer1.0-pipewire` | `gst-plugin-pipewire` | `pipewire-gstreamer` |

Works on X11 and Wayland, with any desktop (Cinnamon, KDE, GNOME, …). Casting the desktop on Wayland
also needs the desktop's screen-sharing portal (`xdg-desktop-portal-kde` / `-gnome` / `-wlr`), which
KDE, GNOME and Sway install already. The PC's sound comes from PipeWire or PulseAudio.

## Windows and macOS

Download from [Releases](https://github.com/m1omg/OpusCast/releases): `…-Windows-Setup.exe` (or the
`…-Windows.zip` to run without installing) and `…-macOS.dmg`. Everything is inside, ffmpeg included:
videos with subtitles, *Show on PC*, music, photos, casting the desktop, the Skynet intro.

The apps aren't signed by Microsoft or Apple (that costs a yearly fee), so the first start needs a click more:

- **Windows:** SmartScreen says "Windows protected your PC" → *More info* → *Run anyway*. When Windows
  asks whether OpusCast may use the network, allow it on *Private networks*, or the TV can't fetch anything.
- **macOS 12–14:** drag OpusCast to Applications, then right-click it → *Open* → *Open*.
  **macOS 15:** open it once, then System Settings → Privacy & Security → *Open Anyway*.
- **Casting the desktop on a Mac** needs *Screen Recording* allowed for OpusCast (macOS asks the first
  time; then start OpusCast again). macOS doesn't let apps record the Mac's sound by itself: install
  [BlackHole](https://existential.audio/blackhole/) (free) and choose it as the output (or a Multi-Output
  Device with BlackHole and your speakers, in Audio MIDI Setup), and the sound goes to the TV too.
- On Windows the PC's sound goes along by itself.

To run it from the source instead: Python 3.9+, `pip install PySide6 PyAudioWPatch` (the latter on Windows),
ffmpeg on the PATH, then `python opuscast_qt.py`. It runs on Linux too.

## Subtitles

- OpusCast finds `.srt`, `.ass`, `.ssa`, `.vtt`, `.sub` and `.smi` files next to the video or in a
  `Subs`, `Subtitles` or `Titulky` folder. In a folder with several videos it takes only the files named
  after the video. The language comes from the file name, e.g. `Movie.sk.srt` or `Movie.Slovak.srt`.
- Text subtitles inside MKV/MP4 files work too (needs ffmpeg). Picture-based ones (Blu-ray PGS,
  DVD VobSub) can't be sent to the TV.
- Any other file: drop it on the window, or *Subtitles → Choose a subtitle file…*
- UTF-8, UTF-16 and Windows-1250 (the usual encoding of Slovak and Czech subtitles) are converted automatically.
- Samsung TVs switch subtitles while the movie plays. Other TVs take subtitles only with the video,
  so OpusCast restarts it at the same spot.
- **Subtitles don't show on a Samsung although OpusCast says they're on?** The TV's own subtitle setting
  is off. Turn subtitles on once with the TV remote. OpusCast never turns that setting off: its *Off*
  shows an empty subtitle file instead.
- The TV doesn't tell the PC what you pick in its own subtitle menu, so OpusCast then shows
  *Subtitles: set on the TV*.

## Firewall

The TV fetches the video from your PC on TCP port **8765** (or the next free port up to 8774).
If your distro's firewall blocks it (CachyOS and Fedora have one turned on by default),
OpusCast notices and offers a button that allows just your TV:

```sh
sudo ufw allow from <TV-IP> to any port 8765 proto tcp                     # ufw (CachyOS, Ubuntu, Mint)
sudo firewall-cmd --permanent --add-rich-rule='rule family=ipv4 source address=<TV-IP> port port=8765 protocol=tcp accept' \
  && sudo firewall-cmd --reload                                              # firewalld (Fedora)
```

While you share the desktop, OpusCast announces itself to the TV's sources every 50 seconds, and the TV
reaches it on the same port. A TV lists it right away when it may also ask on UDP port 1900 (optional):
`sudo ufw allow from <TV-IP> to any port 1900 proto udp`.

## Keys

Space play/pause · ←/→ 10 s · Shift+←/→ 30 s · ↑/↓ TV volume · S subtitles on/off · P show on PC · Ctrl+O open
· Ctrl+D cast the desktop · Page Up/Down previous/next song or photo · photos: ←/→ previous/next,
Space slideshow on/off · desktop: Space shows it on the TV again after the TV left it

## Notes

- Keep OpusCast open while you watch. The TV reads the file from your PC as it plays.
- The TV does the decoding, so what plays depends on the TV. Samsung TVs don't support
  Dolby Vision; such files may not play.
- Music and photos need OpusCast open too: it hands the TV one song or photo at a time.
- Settings: `~/.config/opuscast/`. The rendered intro, extracted subtitles and songs converted to MP3
  (kept under 1 GB): `~/.cache/opuscast/`.

## License

GPL-3.0, see [LICENSE](LICENSE).
