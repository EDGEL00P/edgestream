"""Download the <channel> list (ids + display names + icons) of every free English XMLTV guide.

Streams each gzip and stops at the first <programme>, so even 200 MB guides cost little.
Writes guides.json: {url: {channel_id: {"names": [display names], "icon": "url|null"}}}.
The icon is the first https <icon src="..."> seen for that channel, or null. Older
consumers that expect the plain list shape are handled by build_map.py's compat reader.
"""
import gzip, json, sys, urllib.request, xml.etree.ElementTree as ET, concurrent.futures as cf

UA = {"User-Agent": "Mozilla/5.0 (EdgeStream EPG mapper)"}
GUIDES = []
for cc in ["US1", "US2", "US_LOCALS1", "US_LOCALS2", "UK1", "UK2", "CA1", "CA2", "AU1", "NZ1", "IE1", "PLEX1", "SAMSUNG1", "PLUTO1", "DISTROTV1", "FANDUEL1"]:
    GUIDES.append(f"https://epgshare01.online/epgshare01/epg_ripper_{cc}.xml.gz")
for cc in ["US", "GB", "CA", "AU", "NZ", "IE"]:
    GUIDES.append(f"https://epg.pw/xmltv/epg_{cc}.xml.gz")
for c in ["Brisbane", "Sydney", "Melbourne", "Perth", "Adelaide", "Canberra", "Hobart", "Darwin"]:
    GUIDES.append(f"https://i.mjh.nz/au/{c}/epg.xml.gz")
GUIDES += ["https://i.mjh.nz/nz/epg.xml.gz", "https://epgtalk.com/guide.xml.gz"]
# UK community guides the TV already used by name (measured +9 channels each).
GUIDES += ["https://raw.githubusercontent.com/tv1973/xmltv_uk/refs/heads/master/epg.xml",
           "https://raw.githubusercontent.com/farleyflex/epg-guide/refs/heads/main/epg.xml"]


def channels(url):
    out = {}
    try:
        resp = urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60)
        stream = gzip.GzipFile(fileobj=resp) if url.endswith(".gz") else resp
        for ev, el in ET.iterparse(stream, events=("start", "end")):
            if ev == "start" and el.tag == "programme":
                break
            if ev == "end" and el.tag == "channel":
                cid = el.get("id")
                if cid:
                    icon = None
                    for ic in el.findall("icon"):
                        src = (ic.get("src") or "").strip()
                        if src.startswith("https://"):
                            icon = src
                            break
                    out[cid] = {
                        "names": [d.text.strip() for d in el.findall("display-name") if d.text and d.text.strip()],
                        "icon": icon,
                    }
                el.clear()
        resp.close()
    except Exception as e:  # noqa: BLE001 - report and keep going
        print("FAIL", url, type(e).__name__, e, file=sys.stderr)
    return url, out


if __name__ == "__main__":
    result = {}
    with cf.ThreadPoolExecutor(8) as pool:
        for url, ch in pool.map(channels, GUIDES):
            with_icon = sum(1 for v in ch.values() if v.get("icon"))
            print(f"{len(ch):6d}  icons {with_icon:6d} ({(100*with_icon//len(ch) if ch else 0)}%)  {url}", file=sys.stderr)
            if ch:
                result[url] = ch
    out_path = sys.argv[1] if len(sys.argv) > 1 else "guides.json"
    json.dump(result, open(out_path, "w", encoding="utf-8"), ensure_ascii=False)
