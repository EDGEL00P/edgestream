"""Count <programme> elements per channel id for every guide in guides.json.

Streams each gzip so memory stays flat. Writes counts.json:
  {url: {channel_id: programme_count}}
Used by build_map.py to prefer the populated feed when a guide declares both an SD and
an HD id ("Channel.5.uk" has no programmes, "Channel.5.HD.uk" has 170).
"""
import concurrent.futures as cf
import gzip
import json
import sys
import urllib.request
import xml.etree.ElementTree as ET

UA = {"User-Agent": "Mozilla/5.0 (EdgeStream EPG mapper)"}
guides_path, out_path = sys.argv[1], sys.argv[2]
guides = json.load(open(guides_path, encoding="utf-8"))

# Only count the guides the shipped map can use, plus any extra given on the command line.
WANTED = {
    "epg_AU", "epg_GB", "epg_US", "AU1", "CA2", "FANDUEL1", "IE1", "NZ1", "PLEX1",
    "UK1", "US2", "US_LOCALS1", "guide.xml", "mjh.nz/au", "mjh.nz/nz",
}


def wanted(url):
    return any(w in url for w in WANTED)


def count(url):
    counts = {}
    try:
        resp = urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=300)
        stream = gzip.GzipFile(fileobj=resp) if url.endswith(".gz") else resp
        for ev, el in ET.iterparse(stream, events=("end",)):
            if el.tag == "programme":
                cid = el.get("channel")
                if cid:
                    counts[cid] = counts.get(cid, 0) + 1
                el.clear()
        resp.close()
    except Exception as e:  # noqa: BLE001
        print("FAIL", url, type(e).__name__, e, file=sys.stderr)
    return url, counts


if __name__ == "__main__":
    urls = [u for u in guides if wanted(u)]
    result = {}
    with cf.ThreadPoolExecutor(4) as pool:
        for url, counts in pool.map(count, urls):
            print(f"{len(counts):6d} ids  {sum(counts.values()):8d} progs  {url}", file=sys.stderr)
            if counts:
                result[url] = counts
    json.dump(result, open(out_path, "w", encoding="utf-8"), ensure_ascii=False)
    print("wrote", out_path, len(result), "guides", file=sys.stderr)
