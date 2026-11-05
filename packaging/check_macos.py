"""Fail unless every program and library in the app runs on both Intel and Apple silicon Macs and on
macOS 12 Monterey:  python3 packaging/check_macos.py dist/OpusCast.app"""
import os
import re
import subprocess
import sys

LIMIT = (12, 0)
app = sys.argv[1]
bad, count = [], 0
for root, _dirs, files in os.walk(app):
    for name in files:
        path = os.path.join(root, name)
        if os.path.islink(path):
            continue
        with open(path, "rb") as f:
            magic = f.read(4)
        if magic not in (b"\xca\xfe\xba\xbe", b"\xcf\xfa\xed\xfe", b"\xfe\xed\xfa\xcf", b"\xbe\xba\xfe\xca"):
            continue  # not a Mach-O program or library
        count += 1
        archs = subprocess.run(["lipo", "-archs", path], capture_output=True, text=True).stdout.split()
        if not {"x86_64", "arm64"} <= set(archs):
            bad.append(f"{path}: only {' '.join(archs) or '?'}")
        for arch in ("x86_64", "arm64"):
            if arch not in archs:
                continue
            out = subprocess.run(["otool", "-arch", arch, "-l", path], capture_output=True, text=True).stdout
            found = re.findall(r"(?:minos|version) (\d+)\.(\d+)", out.split("LC_BUILD_VERSION", 1)[-1]
                               if "LC_BUILD_VERSION" in out else out.split("LC_VERSION_MIN_MACOSX", 1)[-1])
            if found:
                need = (int(found[0][0]), int(found[0][1]))
                if need > LIMIT:
                    bad.append(f"{path} ({arch}): needs macOS {need[0]}.{need[1]}")
print(f"checked {count} programs and libraries")
for b in bad:
    print("  " + b)
sys.exit(1 if bad or not count else 0)
