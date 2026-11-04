# OpusCast

Play videos from your Linux PC on your smart TV. No apps on the TV, no servers to set up.
OpusCast hands the file to the TV over your home network (DLNA / UPnP), so the **TV remote works**:
pause, rewind and volume all work as usual.

- **Drop a video in the window** (or pick one, or right-click a video → *Open With → OpusCast*)
- **Picks up where you left off.** Recent videos remember their position and their subtitles.
- **Subtitles from the PC:** subtitle files next to the video and text subtitles inside it.
  Switch them or turn them off while the movie plays; on Samsung TVs this happens instantly.
- **Show on PC:** an optional muted copy of the video in the window, kept in step with the TV.
  Drag the seek bar and you see where you're jumping before you let go.
- **Device picker:** scans your network and shows which devices can play video (TVs), which
  are audio only (speakers), and which can't play anything (router, phones, …)
- **Skynet intro (fun mode):** a Skynet-style boot animation before the movie 🤖
- **English and Slovak.** OpusCast follows the system language; `OPUSCAST_LANG=en` or `sk` overrides it.
- Also a command-line version: `opuscast VIDEO`, `opuscast VIDEO --sub auto`, `opuscast --list`,
  `opuscast --stop` (`opuscast --help` lists everything)

Tested with a Samsung QLED (Tizen). Most smart TVs that can play videos from the network
(Samsung, LG, Sony, Philips, Hisense, Android TV with a DLNA app, …) should work.

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

Works on X11 and Wayland, with any desktop (Cinnamon, KDE, GNOME, …).

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

## Keys

Space play/pause · ←/→ 10 s · Shift+←/→ 30 s · ↑/↓ TV volume · S subtitles on/off · P show on PC · Ctrl+O open

## Notes

- Keep OpusCast open while you watch. The TV reads the file from your PC as it plays.
- The TV does the decoding, so what plays depends on the TV. Samsung TVs don't support
  Dolby Vision; such files may not play.
- Settings: `~/.config/opuscast/`. The rendered intro and extracted subtitles: `~/.cache/opuscast/`.

## License

GPL-3.0, see [LICENSE](LICENSE).
