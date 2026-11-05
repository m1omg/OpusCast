# PyInstaller recipe for the Windows and macOS apps:  pyinstaller packaging/opuscast.spec
# (the Desktop workflow runs it; it works the same on a PC with Python, PySide6 and PyInstaller).
# Optional extras, picked up from build-extra/ in the repository:
#   ffmpeg/                  ffmpeg and ffprobe (+ their DLLs on Windows), carried as the app's own
#   skynet-intro-v<N>.mp4    the Skynet intro, rendered once on Linux (pycairo isn't available everywhere)
import glob
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(SPECPATH, ".."))  # noqa: F821 - provided by PyInstaller
EXTRA = os.path.join(ROOT, "build-extra")
APP = "OpusCast"
VERSION = re.search(r'APP_VERSION = "([^"]+)"', open(os.path.join(ROOT, "opuscast_core.py"), encoding="utf-8").read()).group(1)
MAC = sys.platform == "darwin"

datas = [(os.path.join(ROOT, "data", "io.github.m1omg.OpusCast.svg"), ".")]
datas += [(f, ".") for f in glob.glob(os.path.join(EXTRA, "skynet-intro-v*.mp4"))]
binaries = []
ffmpeg_dir = os.path.join(EXTRA, "ffmpeg")
if os.path.isdir(ffmpeg_dir):
    for name in os.listdir(ffmpeg_dir):
        # Windows: copied as they are, so PyInstaller doesn't mix ffmpeg's DLLs with the ones Qt's
        # preview uses; macOS: static programs, signed with the rest of the app
        (binaries if MAC else datas).append((os.path.join(ffmpeg_dir, name), "ffmpeg"))

a = Analysis(  # noqa: F821
    [os.path.join(ROOT, "opuscast_qt.py")],
    pathex=[ROOT],
    binaries=binaries,
    datas=datas,
    hiddenimports=["pyaudiowpatch"] if sys.platform == "win32" else [],
    excludes=["tkinter", "gi", "cairo", "numpy", "PySide6.QtWebEngineCore", "PySide6.QtWebEngineWidgets",
              "PySide6.QtQuick", "PySide6.QtQml", "PySide6.Qt3DCore", "PySide6.QtCharts", "PySide6.QtDataVisualization",
              "PySide6.QtPdf", "PySide6.QtBluetooth", "PySide6.QtSql", "PySide6.QtTest", "PySide6.QtDesigner"],
)
pyz = PYZ(a.pure)  # noqa: F821
icon = os.path.join(ROOT, "data", "opuscast.icns" if MAC else "opuscast.ico")
exe = EXE(  # noqa: F821
    pyz, a.scripts, [], exclude_binaries=True, name=APP, console=False, icon=icon,
    target_arch="universal2" if MAC else None,
)
coll = COLLECT(exe, a.binaries, a.datas, name=APP)  # noqa: F821
if MAC:
    app = BUNDLE(  # noqa: F821
        coll, name=f"{APP}.app", icon=icon, bundle_identifier="io.github.m1omg.OpusCast", version=VERSION,
        info_plist={
            "CFBundleName": APP, "CFBundleDisplayName": APP,
            "CFBundleShortVersionString": VERSION, "CFBundleVersion": VERSION,
            "LSMinimumSystemVersion": "12.0",  # Monterey, Intel and Apple silicon
            "NSHighResolutionCapable": True,
            "NSRequiresAquaSystemAppearance": False,  # follow dark mode
            "NSLocalNetworkUsageDescription": "OpusCast finds your TV on the home network and sends it videos, "
                                              "music and photos.",
            "NSMicrophoneUsageDescription": "To send the Mac's sound to the TV while it shows your screen, "
                                            "OpusCast records a loopback device such as BlackHole.",
            "CFBundleDocumentTypes": [{
                "CFBundleTypeName": "Videos, music and photos", "CFBundleTypeRole": "Viewer",
                "LSHandlerRank": "Alternate",
                "LSItemContentTypes": ["public.movie", "public.audio", "public.image"],
            }],
        },
    )
