"""Single-command orchestrator for the EdgeStream EPG grabber + curation pipeline.

Usage:
  python tools/epg/grab.py [--db PATH] [--work DIR] [--refresh-guides] [--publish] [--counts]

Steps:
  1. Optionally refresh guides.json via fetch_guides.py (network); otherwise reuse it.
  2. Optionally count programmes per guide channel via count_guides.py (--counts).
  3. Build the curated map via build_map.py -> <work>/epg_map.json (+ *_report.json).
  4. Verify cross-country safety, entry count and icon coverage from the built map.
  5. Optionally copy the map into app/src/main/assets/epg_map.json (--publish).

Defaults: db = live5.db in the work dir, work = the edgestream_db temp dir.
Prints a one-line summary and the report path.
"""
import argparse
import os
import tempfile
import json
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
# Work files (downloaded guides, the provider snapshot) live outside the repo:
# EDGE_EPG_WORK, else the system temp folder.
DEFAULT_WORK = Path(os.environ.get("EDGE_EPG_WORK") or Path(tempfile.gettempdir()) / "edgestream_epg")
DEFAULT_DB = DEFAULT_WORK / "live5.db"


def run(cmd, cwd=None):
    print("+", " ".join(str(c) for c in cmd), flush=True)
    subprocess.run([str(c) for c in cmd], check=True, cwd=cwd)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--db", default=str(DEFAULT_DB))
    ap.add_argument("--work", default=str(DEFAULT_WORK))
    ap.add_argument("--refresh-guides", action="store_true", help="re-download guides.json (network)")
    ap.add_argument("--counts", action="store_true", help="also run count_guides.py (network, slow)")
    ap.add_argument("--publish", action="store_true", help="copy epg_map.json into app assets")
    args = ap.parse_args()

    work = Path(args.work)
    work.mkdir(parents=True, exist_ok=True)
    db, guides_p, counts_p = Path(args.db), work / "guides.json", work / "guide_counts.json"
    map_p, report_p = work / "epg_map.json", work / "epg_map_report.json"

    if args.refresh_guides:
        run([sys.executable, str(HERE / "fetch_guides.py"), str(guides_p)])
    if not guides_p.exists():
        print(f"ERROR: {guides_p} not found; pass --refresh-guides or place guides.json in --work", file=sys.stderr)
        return 2
    if args.counts:
        run([sys.executable, str(HERE / "count_guides.py"), str(guides_p), str(counts_p)])

    cmd = [sys.executable, str(HERE / "build_map.py"), str(db), str(guides_p), str(map_p)]
    if counts_p.exists():
        cmd += ["--counts", str(counts_p)]
    run(cmd)

    # Verify: cross-country check is printed by build_map; here re-check entries/icons.
    m = json.load(open(map_p, encoding="utf-8"))
    entries = [e for v in m["channels"].values() for e in v]
    logos = sum(1 for e in entries if len(e) > 3 and e[3])
    assert m["guides"] and entries, "map is empty"
    assert all(len(e) in (3, 4) for e in entries), "entry shape must be 3 or 4 elements"

    published = False
    if args.publish:
        dest = REPO / "app" / "src" / "main" / "assets" / "epg_map.json"
        shutil.copyfile(map_p, dest)
        published = True

    pct = round(100.0 * logos / len(entries), 1) if entries else 0.0
    rep = json.load(open(report_p, encoding="utf-8")) if report_p.exists() else {}
    cov = rep.get("coverage", {})
    print(f"SUMMARY keys={len(m['channels'])} entries={len(entries)} guides={len(m['guides'])} "
          f"icons={logos} ({pct}%) epg={cov.get('epg_covered_pct')}% logo={cov.get('logo_covered_pct')}% "
          f"published={published}")
    print(f"report: {report_p}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
