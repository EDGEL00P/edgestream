"""Which guide channel ids the lineup actually uses, per public guide: tools/epg/wanted.json.

Inputs: a lineup export (debug build: adb pull
/sdcard/Android/data/com.edgestream.tv/files/lineup.json) and guides.json from fetch_guides.py.
A guide channel is wanted when the TV resolved a channel to its id, or the shipped map points at
it. The output holds guide ids only (no channel names, no logins), so it can live in the public
repo, where the weekly merged-guide job reads it.

Usage:
  python tools/epg/build_wanted.py LINEUP.json GUIDES.json [--map app/src/main/assets/epg_map.json]
"""
import argparse
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
DEFAULT_MAP = HERE.parents[1] / "app" / "src" / "main" / "assets" / "epg_map.json"
OUT = HERE / "wanted.json"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("lineup")
    ap.add_argument("guides")
    ap.add_argument("--map", default=str(DEFAULT_MAP))
    args = ap.parse_args()
    lineup = json.load(open(args.lineup, encoding="utf-8"))
    guides = json.load(open(args.guides, encoding="utf-8"))
    guide_map = json.load(open(args.map, encoding="utf-8"))

    resolved = {c["epgId"] for c in lineup if c.get("epgId")}
    wanted = {}
    for url, channels in guides.items():
        ids = resolved & set(channels)
        if ids:
            wanted[url] = ids
    for entries in guide_map["channels"].values():
        for idx, cid, *_ in entries:
            wanted.setdefault(guide_map["guides"][idx], set()).add(cid)

    out = {url: sorted(ids) for url, ids in sorted(wanted.items(), key=lambda kv: -len(kv[1]))}
    OUT.write_text(json.dumps(out, indent=1) + "\n", encoding="utf-8")
    print(f"wanted: {sum(map(len, out.values()))} guide channels from {len(out)} guides -> {OUT}")


if __name__ == "__main__":
    main()
