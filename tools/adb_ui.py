#!/usr/bin/env python3
"""Small adb UI helper for manual/E2E checks on an emulator or phone.

  adb_ui.py shot NAME        -> screenshot to $OUT/NAME.png (half size)
  adb_ui.py tap "text" [n]   -> tap the first node whose text/content-desc contains text (retries n times)
  adb_ui.py has "text"       -> exit 0 if visible
  adb_ui.py dump             -> list visible texts with bounds
"""
import os, re, subprocess, sys, time, xml.etree.ElementTree as ET

OUT = os.environ.get("OUT", "/tmp/famichibi-e2e")
os.makedirs(OUT, exist_ok=True)


def sh(*a):
    return subprocess.run(["adb", "shell", *a], capture_output=True, text=True).stdout


def nodes():
    sh("uiautomator", "dump", "/sdcard/ui.xml")
    x = subprocess.run(["adb", "exec-out", "cat", "/sdcard/ui.xml"], capture_output=True, text=True).stdout
    return list(ET.fromstring(x[x.index("<?xml"):]).iter("node")) if "<?xml" in x else []


def center(n):
    a = list(map(int, re.findall(r"\d+", n.get("bounds"))))
    return (a[0] + a[2]) // 2, (a[1] + a[3]) // 2


cmd = sys.argv[1]
if cmd == "shot":
    out = f"{OUT}/{sys.argv[2]}.png"
    with open(out, "wb") as f:
        f.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout)
    from PIL import Image
    im = Image.open(out); im.thumbnail((540, 1212)); im.save(out)
    print(out)
elif cmd in ("tap", "has"):
    want = sys.argv[2]
    for _ in range(int(sys.argv[3]) if len(sys.argv) > 3 else 8):
        for n in nodes():
            if want in (n.get("text") or "") + "|" + (n.get("content-desc") or ""):
                if cmd == "has":
                    print("yes"); sys.exit(0)
                x, y = center(n)
                sh("input", "tap", str(x), str(y))
                print("tapped", want, x, y); sys.exit(0)
        time.sleep(1.5)
    print("NOT FOUND", want); sys.exit(1)
elif cmd == "dump":
    for n in nodes():
        t = n.get("text") or n.get("content-desc")
        if t:
            print(repr(t), n.get("bounds"))
