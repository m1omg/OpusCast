#!/usr/bin/env bash
# OpusCast installer - installs for the current user only, no root needed.
#   ./install.sh              install or update
#   ./install.sh --uninstall  remove (keeps settings and the cached intro)
set -euo pipefail

APP_ID=io.github.m1omg.OpusCast
SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATA="${XDG_DATA_HOME:-$HOME/.local/share}"
APPDIR="$DATA/opuscast"
BIN="$HOME/.local/bin"
DESKTOP="$DATA/applications/$APP_ID.desktop"
ICON="$DATA/icons/hicolor/scalable/apps/$APP_ID.svg"

refresh() {
  if command -v update-desktop-database >/dev/null; then update-desktop-database -q "$DATA/applications" || true; fi
  if command -v gtk-update-icon-cache >/dev/null; then gtk-update-icon-cache -q -t -f "$DATA/icons/hicolor" 2>/dev/null || true; fi
}

if [ "${1:-}" = "--uninstall" ]; then
  rm -rf "$APPDIR"
  rm -f "$BIN/opuscast" "$BIN/opuscast-gui" "$DESKTOP" "$ICON"
  refresh
  echo "OpusCast removed. Settings are still in ~/.config/opuscast, the intro video in ~/.cache/opuscast."
  exit 0
fi

command -v python3 >/dev/null || { echo "OpusCast needs Python 3 - install it with your package manager."; exit 1; }

mkdir -p "$APPDIR" "$BIN" "$(dirname "$DESKTOP")" "$(dirname "$ICON")"
rm -rf "$APPDIR/__pycache__"
install -m 644 "$SRC/opuscast_core.py" "$SRC/opuscast_intro.py" "$SRC/opuscast_i18n.py" "$APPDIR/"
install -m 755 "$SRC/opuscast" "$SRC/opuscast-gui" "$APPDIR/"
install -m 644 "$SRC/data/$APP_ID.svg" "$ICON"
ln -sf "$APPDIR/opuscast" "$BIN/opuscast"
ln -sf "$APPDIR/opuscast-gui" "$BIN/opuscast-gui"

cat > "$DESKTOP" <<EOF
[Desktop Entry]
Type=Application
Name=OpusCast
GenericName=Play on TV
GenericName[sk]=Prehrať v televízore
Comment=Play videos from this PC on your smart TV
Comment[sk]=Prehráva videá z tohto počítača v televízore
Exec="$APPDIR/opuscast-gui" %F
Icon=$APP_ID
Terminal=false
Categories=AudioVideo;Video;Player;
MimeType=video/mp4;video/x-matroska;video/webm;video/quicktime;video/x-msvideo;video/vnd.avi;video/mpeg;video/mp2t;video/x-flv;video/x-ms-wmv;video/3gpp;video/x-m4v;
Keywords=tv;cast;dlna;upnp;stream;samsung;lg;
StartupNotify=true
StartupWMClass=opuscast
EOF
refresh

echo "OpusCast installed:"
echo "  • App menu: OpusCast - and right-click a video → Open With → OpusCast"
echo "  • Terminal: opuscast-gui, or opuscast VIDEO (opuscast --help)"
case ":$PATH:" in *":$BIN:"*) ;; *) echo "  (add $BIN to your PATH for the terminal commands)";; esac
echo
echo "Checking what's available:"
python3 -I "$APPDIR/opuscast_core.py" --check-deps || true
