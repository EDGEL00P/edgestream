"""Count programmes per channel id in one XMLTV guide, streaming so memory stays flat."""
import gzip
import sys
import urllib.request
import xml.etree.ElementTree as ET

URL = sys.argv[1]
WANT = set(sys.argv[2:])
counts = {}
current = None
n = 0
req = urllib.request.Request(URL, headers={"User-Agent": "Mozilla/5.0 (EdgeStream EPG check)"})
with urllib.request.urlopen(req, timeout=240) as resp:
    raw = resp
    if URL.endswith(".gz"):
        raw = gzip.GzipFile(fileobj=resp)
    for event, elem in ET.iterparse(raw, events=("start", "end")):
        if event == "end" and elem.tag == "programme":
            cid = elem.get("channel")
            if not WANT or cid in WANT:
                counts[cid] = counts.get(cid, 0) + 1
            current = cid
            n += 1
            elem.clear()
print("total programmes", n)
for k in sorted(counts):
    print(k, counts[k])
