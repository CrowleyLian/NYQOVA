"""Import pinned Lucide SVGs as API-23-compatible Android VectorDrawables.

No rasterization or icon font: paths are copied; basic SVG shapes become equivalent paths.
Run with Python 3.8+: python scripts/import-lucide.py
"""
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
from urllib.request import Request, urlopen
from xml.etree import ElementTree as ET
from xml.sax.saxutils import quoteattr
import hashlib
import json

REVISION = "6657faa96ae10efe1509203c62b60d913006ab82"
NAMES = "house heart clock disc mic-vocal shapes library folder settings list-music eighth-notes search ellipsis skip-back skip-forward play pause repeat repeat-1 shuffle chevron-down maximize x trash panel-left-close panel-left-open plus circle".split()
ROOT = Path(__file__).resolve().parents[1]
VENDOR = ROOT / "third-party/lucide"
DRAWABLE = ROOT / "app/src/main/res/drawable"

def fetch(name):
    request = Request("https://raw.githubusercontent.com/lucide-icons/lucide/{}/{}".format(REVISION, name), headers={"User-Agent": "NYQOVA-icon-import"})
    with urlopen(request, timeout=30) as response:
        return response.read()

def path_data(node):
    tag = node.tag.rsplit("}", 1)[-1]
    a = node.attrib
    def n(key, default=0): return float(a.get(key, default))
    if "transform" in a: raise ValueError("Unsupported transform")
    if tag == "path": return a["d"]
    if tag == "line": return "M{} {}L{} {}".format(n("x1"), n("y1"), n("x2"), n("y2"))
    if tag in ("polyline", "polygon"):
        return "M" + a["points"].replace(",", " ") + ("Z" if tag == "polygon" else "")
    if tag == "circle":
        x, y, r = n("cx"), n("cy"), n("r")
        return "M{} {}a{} {} 0 1 0 {} 0a{} {} 0 1 0 {} 0Z".format(x-r, y, r, r, 2*r, r, r, -2*r)
    if tag == "rect":
        x, y, w, h = n("x"), n("y"), n("width"), n("height")
        rx, ry = min(n("rx", a.get("ry", 0)), w/2), min(n("ry", a.get("rx", 0)), h/2)
        if not rx or not ry: return "M{} {}h{}v{}h{}Z".format(x, y, w, h, -w)
        return "M{} {}H{}a{} {} 0 0 1 {} {}V{}a{} {} 0 0 1 {} {}H{}a{} {} 0 0 1 {} {}V{}a{} {} 0 0 1 {} {}Z".format(x+rx,y,x+w-rx,rx,ry,rx,ry,y+h-ry,rx,ry,-rx,ry,x+rx,rx,ry,-rx,-ry,y+ry,rx,ry,rx,-ry)
    raise ValueError("Unsupported SVG element: " + tag)

def vector(svg, filled=False):
    root = ET.fromstring(svg)
    assert root.attrib["viewBox"] == "0 0 24 24"
    rows = ['<!-- Lucide, pinned revision {}. See assets/licenses/lucide.txt. -->'.format(REVISION),
            '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">']
    for node in root:
        if node.tag.rsplit("}",1)[-1] in ("title", "desc"): continue
        d = path_data(node)
        rows.append('    <path android:pathData={} android:fillColor="{}" android:strokeColor="#FFFFFFFF" android:strokeWidth="2" android:strokeLineCap="round" android:strokeLineJoin="round" />'.format(quoteattr(d), "#FFFFFFFF" if filled else "#00000000"))
    return "\n".join(rows + ["</vector>", ""])

def main():
    (VENDOR / "icons").mkdir(parents=True, exist_ok=True)
    DRAWABLE.mkdir(parents=True, exist_ok=True)
    with ThreadPoolExecutor(max_workers=6) as pool:
        data = dict(zip(NAMES, pool.map(lambda name: fetch("icons/" + name + ".svg"), NAMES)))
    manifest = {"upstream": "https://github.com/lucide-icons/lucide", "revision": REVISION, "sha256": {}}
    for name, svg in data.items():
        (VENDOR / "icons" / (name + ".svg")).write_bytes(svg)
        manifest["sha256"][name] = hashlib.sha256(svg).hexdigest()
        (DRAWABLE / ("ic_lucide_" + name.replace("-", "_") + ".xml")).write_text(vector(svg), encoding="utf-8")
    for name in ("heart", "circle"):
        (DRAWABLE / ("ic_lucide_" + name + "_filled.xml")).write_text(vector(data[name], filled=True), encoding="utf-8")
    license_data = fetch("LICENSE")
    (VENDOR / "LICENSE").write_bytes(license_data)
    assets = ROOT / "app/src/main/assets/licenses"
    assets.mkdir(parents=True, exist_ok=True)
    (assets / "lucide.txt").write_bytes(license_data)
    (VENDOR / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print("Imported {} Lucide icons and 2 filled variants at {}".format(len(data), REVISION))

if __name__ == "__main__": main()
