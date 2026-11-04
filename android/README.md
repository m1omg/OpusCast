# OpusCast for Android

Play videos from your phone on your smart TV. It's the same idea as OpusCast on Linux: no app on the TV,
no server to set up. The phone hands the file to the TV over your Wi‑Fi (DLNA / UPnP), so the
**TV remote works** for pause, rewind and volume.

- **Choose a video**, or use *Share → OpusCast* / *Open with → OpusCast* from a file manager or gallery.
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
- **The phone's volume buttons set the TV's volume** while a video plays. Lock-screen and
  Bluetooth media controls work too.
- **Device list:** shows which devices on your network can play video, which are audio only, and
  which can't play anything. If your TV isn't listed, type its address.
- **Skynet intro (fun mode):** the same boot animation as on Linux. The phone draws and encodes it
  the first time you switch it on (this takes a little while), then keeps it.
- English and Slovak, following the phone's language (Android 13+: per-app language in Settings).

Needs Android 8.0 or newer. Tested with a Samsung QLED (Tizen). Most smart TVs that can play videos
from the network should work.

## Install

Download the APK from the latest **Android** run under the repository's
[Actions](https://github.com/m1omg/OpusCast/actions/workflows/android.yml) tab (artifact *OpusCast-android*), unzip it, and
open the `.apk` on the phone. Android asks you to allow installing apps from your browser or file
manager.

Builds signed with the CI runner's throwaway key can't update each other: uninstall the old version
first, or set up your own signing key (see the comments in `.github/workflows/android.yml`).

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

## Build

The app needs the Android SDK (`./gradlew assembleRelease` in this folder, or open it in Android Studio).
The part that talks to the TV, serves the file and handles subtitles is plain Kotlin in `core/`, with
tests that run on any JDK 17+:

```sh
./gradlew -p core test
```

## License

GPL-3.0, see [LICENSE](../LICENSE).
