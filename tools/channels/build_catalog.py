"""Builds app/src/main/assets/channel_catalog.json from the iptv-org database (CC0, keyless).

    python tools/channels/build_catalog.py <dir with channels.json logos.json> <out.json>

Only live channels of the English-speaking markets the app keeps (US UK CA AU NZ IE). Each row:
[id, name, [alt names], country, [categories], logo|null, network|null]; plus every non-main feed.
"""
import json, sys

COUNTRIES = {"US", "UK", "CA", "AU", "NZ", "IE"}  # iptv-org files the UK as "UK"
src, out = sys.argv[1], sys.argv[2]
load = lambda n: json.load(open(f"{src}/{n}.json", encoding="utf8"))
channels, logos, feeds = load("channels"), load("logos"), load("feeds")

def logo_rank(l):
    # The channel's own logo (not a feed's), in use, raster, widest.
    return (l["feed"] is None, l["in_use"], l["format"] in ("PNG", "JPEG", "WebP"), l["width"] or 0)

best = {}
for l in logos:
    if l["format"] == "SVG":
        continue  # Coil on the TV has no SVG decoder configured.
    cur = best.get(l["channel"])
    if cur is None or logo_rank(l) > logo_rank(cur):
        best[l["channel"]] = l

rows = []
for c in channels:
    if c["country"] not in COUNTRIES or c["closed"] or c["replaced_by"] or c["is_nsfw"]:
        continue
    alts = sorted(set(c["alt_names"]))
    logo = best.get(c["id"])
    rows.append([c["id"], c["name"], alts, c["country"], c["categories"], logo["url"] if logo else None, c["network"]])
kept = {r[0] for r in rows}
# Regional and local feeds ("CTV North Bay", NBC's WTLV): [channel id, feed id, feed name, alt names].
feed_rows = [[f["channel"], f["id"], f["name"], f["alt_names"]] for f in feeds if f["channel"] in kept and not f["is_main"]]
json.dump({"source": "https://github.com/iptv-org/database", "channels": rows, "feeds": feed_rows}, open(out, "w", encoding="utf8"), ensure_ascii=False, separators=(",", ":"))
print(len(rows), "channels,", sum(1 for r in rows if r[5]), "with logos,", len(feed_rows), "feeds")
