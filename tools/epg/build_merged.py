"""Build one merged XMLTV guide holding only the guide channels EdgeStream's lineup uses.

The TV used to download ~37 guide files every update (15-20 min on a SHIELD). This keeps just
the channels listed in tools/epg/wanted.json (see build_wanted.py), within the window the app
shows, and writes one file the TV downloads instead. Channel ids and names stay as the source
guides wrote them, so the TV's own matching (by id or by name) works on the merged file unchanged;
when two guides use the same id, the first listed wins, as it did on the TV.

Usage:
  python tools/epg/build_merged.py OUT.xml.gz [--wanted tools/epg/wanted.json]
"""
import argparse
import datetime as dt
import gzip
import io
import json
import sys
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent
BACK = dt.timedelta(hours=12)  # just-finished shows; catch-up history comes from the provider guide
AHEAD = dt.timedelta(hours=48)  # the app keeps 48 h ahead (GUIDE_AHEAD_MS)
UA = "EdgeStream-guide-builder/1.0"


def xmltv_time(s):
    """'20261109073000 +1000' -> aware datetime (None if unreadable)."""
    try:
        s = s.strip()
        base = dt.datetime.strptime(s[:14], "%Y%m%d%H%M%S")
        tz = s[14:].strip()
        if tz:
            sign = 1 if tz[0] == "+" else -1
            return base.replace(tzinfo=dt.timezone(sign * dt.timedelta(hours=int(tz[1:3]), minutes=int(tz[3:5]))))
        return base.replace(tzinfo=dt.timezone.utc)
    except (ValueError, IndexError):
        return None


def open_guide(url):
    data = urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=180).read()
    return io.BytesIO(gzip.decompress(data) if data[:2] == b"\x1f\x8b" else data)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--wanted", default=str(HERE / "wanted.json"))
    args = ap.parse_args()

    wanted = {url: set(ids) for url, ids in json.load(open(args.wanted, encoding="utf-8")).items()}
    now = dt.datetime.now(dt.timezone.utc)
    lo, hi = now - BACK, now + AHEAD

    root = ET.Element("tv", {"generator-info-name": "EdgeStream merged guide"})
    taken = set()  # channel ids already supplied by an earlier guide
    channels, programmes = [], []
    for url, ids in wanted.items():
        ids = ids - taken
        if not ids:
            continue
        kept_ch = kept_pr = 0
        try:
            for _, el in ET.iterparse(open_guide(url), events=("end",)):
                if el.tag == "channel":
                    if el.get("id") in ids:
                        channels.append(el)
                        kept_ch += 1
                elif el.tag == "programme":
                    if el.get("channel") in ids:
                        start, stop = xmltv_time(el.get("start", "")), xmltv_time(el.get("stop", ""))
                        if start and stop and stop > lo and start < hi:
                            programmes.append(el)
                            kept_pr += 1
                            continue
                    el.clear()
        except Exception as e:  # one unreachable guide must not sink the others
            print(f"  ! {url}: {e}", file=sys.stderr)
            continue
        taken |= ids
        print(f"  {url.split('://')[-1][:70]}: {kept_ch} channels, {kept_pr} programmes")

    root.extend(channels)
    root.extend(programmes)
    with gzip.open(args.out, "wb", compresslevel=9) as f:
        f.write(ET.tostring(root, encoding="utf-8", xml_declaration=True))
    print(f"merged: {len(channels)} channels, {len(programmes)} programmes, "
          f"{Path(args.out).stat().st_size / 1e6:.1f} MB gz -> {args.out}")


if __name__ == "__main__":
    main()
