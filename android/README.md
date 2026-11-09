# OpusCast for Android

Play videos, music and photos from your phone on your smart TV, or show the phone's screen on it.
It's the same idea as OpusCast on Linux: no app on the TV, no server to set up. The phone hands the
file to the TV over your Wi‑Fi (DLNA / UPnP), so the **TV remote works** for pause, rewind and volume.

- **Choose a video, music or photos**, or use *Share → OpusCast* / *Open with → OpusCast* from a file
  manager, gallery or music app.
- **Picks up where you left off.** Recent videos remember their position and their subtitles.
- **Subtitles:**
  - Text subtitles inside MKV, WebM and MP4 videos are read straight from the file.
  - Subtitle files next to the video, or in a `Subs` / `Subtitles` / `Titulky` folder, are found
    once you allow that folder (see below).
  - Any other subtitle file: *Subtitles → Choose a subtitle file…*
  - SRT, ASS/SSA, WebVTT, SAMI and MicroDVD/SubViewer `.sub` files are converted for the TV.
    UTF‑8, UTF‑16 and Windows‑1250 (the usual encoding of Slovak and Czech subtitles) are handled.
  - On Samsung TVs subtitles switch instantly; other TVs restart the video at the same spot.
- **Show on phone:** a muted copy of the video on the phone, kept in step with the TV. Drag the seek
  bar and you see where you're jumping before you let go.
- **Music:** pick one song or several; they play one after another with the album cover, artist and
  album on the TV and on the lock screen. Next / Previous on the phone, in the notification and on
  Bluetooth headphones. Speakers that play music over DLNA (Sonos, Denon/HEOS, Yamaha MusicCast…) can
  be picked for music too.
- **Photos:** pick one or many and flip through them on the TV - swipe or tap Next - or let the
  slideshow run (3 to 30 seconds a photo). HEIC photos from the camera (Android 9+), WebP and photos that are
  only turned by their EXIF tag are converted to an upright JPEG the TV can show; very large photos
  are scaled to 4K.
- **Cast phone screen:** the TV shows what's on the phone's screen, with the sound apps play
  (Android 10+, after you allow recording audio - it's only used for the apps' sound). Android asks
  every time whether to share the screen. Expect the TV to be a couple of seconds behind; choose
  720p in the options bar if your Wi‑Fi struggles.
- **Left the phone's screen on the TV by mistake?** It stays shared, and the TV remote brings it back:
  while you share, the phone is among the TV's sources as “OpusCast on *your phone*” (Samsung:
  *Source*; LG, Philips and others: their media player), with the screen in it. Or tap *Show on the
  TV again* in the app or the notification. With *Bring it back if the TV leaves* it comes back by
  itself; leave it again right away and it stays off. Only the TV you share to sees your screen
  there, and nobody watching for 30 minutes ends sharing.
- **The phone's volume buttons set the TV's volume** while something plays. Lock-screen and
  Bluetooth media controls work too.
- **Device list:** shows which devices on your network can play video, which play music only, and
  which can't play anything. If your TV isn't listed, type its address.
- **Skynet intro (fun mode):** the same boot animation as on Linux. The phone draws and encodes it
  the first time you switch it on (this takes a little while), then keeps it.
- English and Slovak, following the phone's language (Android 13+: per-app language in Settings).

Needs Android 8.0 or newer. Tested with a Samsung QLED (Tizen). OpusCast reads each TV's list of the
formats it plays and names them the way that TV does, so LG, Sony, Philips, Panasonic, Hisense, TCL and
other DLNA TVs (also ones with Android TV / Google TV built in) should work as well.

- **Philips:** the TV's DLNA player must be on: *All Settings → Wireless and Networks → Wired or
  Wi‑Fi → Digital Media Renderer - DMR* (it's on unless someone switched it off).
- **Chromecast, Google TV Streamer, Nvidia Shield** and other streaming sticks and boxes speak only
  Google Cast, which OpusCast doesn't use, so they don't show up as TVs.

## Install

Download `OpusCast-vX.Y.apk` from the [latest release](https://github.com/m1omg/OpusCast/releases/latest)
and open it on the phone. Android asks you to allow installing apps from your browser or file manager.
Builds of every change are also under [Actions](https://github.com/m1omg/OpusCast/actions/workflows/android.yml)
(artifact *OpusCast-android*, a zip with the APK).

Until the repository has its own signing key (see the comments in `.github/workflows/android.yml`),
each build is signed with a throwaway key, so a newer APK won't install over an older one: uninstall
the old version first.

To publish a new release, raise `versionCode` / `versionName` in `app/build.gradle.kts`, then either
draft a release on GitHub (*Releases → Draft a new release*, new tag `v1.1` on `main`) or push a version
tag whose message is the release notes (`git tag -a v1.1 -m "…" && git push origin v1.1`). Either way CI
builds the APK and attaches it to the release.

## Subtitle files next to the video

Android shows an app only the files you pick. To let OpusCast find subtitle files by itself:

- Open *Subtitles* while the video plays and tap **Allow this folder**. The folder picker opens at
  the video's folder: tap *Use this folder*. This covers every video in that folder and its subfolders,
  so allowing e.g. `Movies` once is enough.
- Android doesn't hand out the `Download` folder itself (or the top of the storage). For videos
  there, use **Allow access to all files**, pick a subtitle file by hand, or move the video into a
  subfolder.

## Good to know

- Keep the phone on the same Wi‑Fi while you watch: the TV reads the video from the phone as it plays.
  OpusCast keeps running with the screen off and shows a notification while it serves a video.
- If the TV doesn't start loading, the phone and the TV can't reach each other. Guest networks and
  some routers keep Wi‑Fi devices apart, and a VPN on the phone can get in the way.
- The TV does the decoding, so what plays depends on the TV. Samsung TVs don't support Dolby Vision;
  such files may not play. Picture-based subtitles (Blu‑ray PGS, DVD VobSub) can't be sent to a TV.
  Some TVs don't play every music format (Opus and Ogg especially); MP3, AAC/M4A and FLAC are safe.
- Screen casting: apps can forbid having their sound recorded (some streaming apps do); their sound
  then stays on the phone. Notifications and other private content may be hidden by Android while
  the screen is shared.

## Build

The app needs the Android SDK (`./gradlew assembleRelease` in this folder, or open it in Android Studio).
The part that talks to the TV, serves the file, handles subtitles and muxes the screen stream into
MPEG-TS is plain Kotlin in `core/`, with tests that run on any JDK 17+ (with ffmpeg installed, the
muxer's output is also decoded by ffmpeg as a check):

```sh
./gradlew -p core test
```

## License

GPL-3.0, see [LICENSE](../LICENSE).
