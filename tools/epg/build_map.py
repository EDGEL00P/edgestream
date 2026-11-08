"""Map every English channel in the EdgeStream database onto a free online XMLTV guide.

Inputs: the app database (channels) and guides.json from fetch_guides.py.
Output: epg_map.json, shipped as an app asset:
  {"guides": [url, ...],
   "channels": {channel_key: [[guide_index, guide_channel_id, country, logoUrl|null], ...]}}
The 4th element is the curated <icon> of the mapped guide channel (null when the guide
carries none). Older 3-element entries (no logo) are still understood by the app.

Also writes *_report.json next to the output listing every English channel row (name,
country, epg status, guide, guide id, map logo) and foreign channels separately as
provider-guide-only, plus a coverage summary.

The provider's own ids are ignored when building this map: they are routinely copied from a
different channel ("US| AHC" tagged as AMC, "UK| BBC 1" pointed at the Irish feed). A channel is
matched by its name, and only against guides of the channel's own country, so a UK channel can
never land on a US or Irish listing.

One key can hold several entries, one per country, because names repeat across countries
("Food Network" exists in both the US and the UK). EpgMap.lookup picks the entry for the
playlist channel's country.

channel_key is the channel name with the country prefix, quality tags and [tags] removed (see
key()), so the map survives the provider renumbering streams. Also prints a coverage report.
"""
import collections
import json
import re
import sqlite3
import sys

db_path, guides_path, out_path = sys.argv[1:4]
allow_provider_id = "--allow-provider-id" in sys.argv[4:]
guides = json.load(open(guides_path, encoding="utf-8"))


def guide_names(val):
    """Display names from a guides.json value: new dict shape or legacy list shape."""
    if isinstance(val, dict):
        return val.get("names") or []
    return val or []


def guide_icon(val):
    """Curated icon from a guides.json value: new dict shape only; legacy lists have none."""
    if isinstance(val, dict):
        return val.get("icon") or None
    return None

# Optional programme counts (count_guides.py). Guides declare feeds they never populate:
# UK1 lists both "Channel.5.uk" (0 programmes) and "Channel.5.HD.uk" (170). Without counts the
# shorter id wins and the channel ends up with an empty guide.
counts_path = None
if "--counts" in sys.argv:
    counts_path = sys.argv[sys.argv.index("--counts") + 1]
counts = json.load(open(counts_path, encoding="utf-8")) if counts_path else {}


def progs(url, cid):
    """Programme count for a guide id, or -1 when that guide was not counted."""
    return counts.get(url, {}).get(cid, -1)


EN = {"US", "USA", "UK", "GB", "CA", "CAN", "AU", "AUS", "NZ", "IE", "EN", "ENG"}
NEU = {"PPV", "VIP", "HD", "FHD", "UHD", "4K", "24/7", "247", "LIVE", "EVENT", "NEW"}
PREFIX = re.compile(r"^\s*\|?\s*([A-Z0-9/]{2,5})\s*[|:]", re.I)
EG = re.compile(r"\b(USA|US|UK|CANADA|AUSTRALIA|NEW ZEALAND|IRELAND|IRISH|ENGLISH|BRITISH)\b", re.I)
FG = re.compile(r"FRAN[CÇ]AIS|FRENCH|QU[EÉ]BEC|LATINO|ESPA[NÑ]OL|SPANISH|VIX|TELEMUNDO|UNIVISION|INDIA|HINDI|PUNJABI|URDU|TAMIL|ARAB|PORTUGU|ITALIAN|GREEK|POLISH|CHINESE|KOREAN|FILIPINO|VIETNAM", re.I)

KNOWN = {"US", "UK", "GB", "CA", "AU", "NZ", "IE"}
ALIAS = {"USA": "US", "GB": "UK", "CAN": "CA", "AUS": "AU"}


def english(n, g):
    f = bool(FG.search(g)); m = PREFIX.match(n); p = m.group(1).upper() if m else None
    if p and p not in NEU:
        return p in EN and not f
    return not f and bool(EG.search(g))


HEADER = re.compile(r"✦|●|^\s*[#=*-]{3,}")
EMPTY_SLOT = re.compile(r"\[\s*EVENT ONLY\s*\]|\(2098-|\[\s*\]|NO EVENT", re.I)
EVENT2 = re.compile(r"\[[^\]]*(?:@\s*[A-Z][a-z]{2} \d{1,2} \d{1,2}:\d{2}\s*[AP]M|\d{1,2}(?::\d{2})?\s*[AP]M)[^\]]*\]", re.I)
LOOP_GROUP = re.compile(r"CINEMANIA|PLAY KIDZ|24/7|ON-DEMAND|SEREIS|SERIES", re.I)
EVENT = re.compile(r"\[(?:live:\s*)?(.+?)\s*\((\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\)\s*\]", re.I)
ALWAYS_ON = re.compile(r"^\s*24/7", re.I)
QUALITY = re.compile(r"\b(UHD|FHD|HD|SD|HEVC|H265|4K|1080P?|720P?|50 ?FPS|60 ?FPS|BACKUP|RAW|VIP|NEW|ᴴᴰ|ᴿᴬᵂ)\b", re.I)

# Regional variants of the same national channel. "BBC One Lon HD" is a BBC One feed;
# its listing is still a valid fallback for a playlist channel that just says "BBC 1".
REGION = re.compile(
    r"\b(lon|london|west|east|south|south west|south east|s w|s e|sw|se|north|north west|"
    r"n west|ne|c|central|midlands|east midlands|e mid|wm|yorks|y and l|y&l|sth|"
    r"ni|n ireland|wal|wales|scot|scotland|ci)\b",
    re.I,
)
INITIAL_STOP = {"and", "canada", "canadian", "us", "usa", "uk", "gb", "australia",
                "ireland", "irish", "british", "english", "new", "zealand"}

NUMBERS = {
    "one": "1", "two": "2", "three": "3", "four": "4", "five": "5", "six": "6",
    "seven": "7", "eight": "8", "nine": "9", "ten": "10", "eleven": "11", "twelve": "12",
}
NUMBER_RE = re.compile(r"\b(" + "|".join(NUMBERS) + r")\b", re.I)


def key(name):
    """Stable channel key: no country prefix, quality tags or [BK]-style tags; case-folded."""
    n = PREFIX.sub("", name)
    n = re.sub(r"\[[^\]]*\]", " ", n)
    n = QUALITY.sub(" ", n)
    return re.sub(r"\s+", " ", n).strip().lower()


def norm(s):
    """Name comparator: "BBC 1" == "BBC One", "A&E" == "A and E", "E4 +1" stays distinct.

    "channel" and "network" are deliberately kept: dropping them collapses "Channel 5"
    into "5" and lets it collide with "5 USA".
    """
    s = s.lower().replace("&", " and ").replace("+1", " plus1 ").replace("+ 1", " plus1 ").replace("+", " plus ")
    s = re.sub(r"\(.*?\)|\[.*?\]", " ", s)
    s = QUALITY.sub(" ", s)
    s = NUMBER_RE.sub(lambda m: " %s " % NUMBERS[m.group(1).lower()], s)
    # A country code is only a feed tag at the END of a name ("Discovery Channel US",
    # "BBC One UK"). Stripping it everywhere collapsed "5 USA +1" into "5 +1" and put
    # Channel 5's timeshift on the American channel, so timeshift names keep it.
    s = re.sub(r"\b(east|eastern|feed|television|the|dt|hdtv)\b", " ", s)
    if "plus1" in s:
        s = re.sub(r"\b(us|usa|uk|ca|au|nz|ie)\b\s*$", " ", s)
    else:
        s = re.sub(r"\b(us|usa|uk|ca|au|nz|ie)\b", " ", s)
    return "".join(stem(w) for w in re.findall(r"[a-z0-9]+", s))


def initials(s):
    """AHC -> American Heroes Channel, BBC -> British Broadcasting Corporation."""
    s = re.sub(r"\(.*?\)|\[.*?\]", " ", s)
    s = QUALITY.sub(" ", s)
    words = [w for w in re.findall(r"[A-Za-z0-9]+", s) if w.lower() not in INITIAL_STOP]
    return "".join(w[0] for w in words).lower()


GENERIC = {"news", "sport", "sports", "live", "movie", "movies", "music", "kids",
           "channel", "network", "plus", "extra", "events", "event", "radio", "world",
           "american", "america", "english", "fight", "fights", "ppv", "preview",
           "east", "west", "north", "south", "local", "main"}


def acronyms(s):
    """Acronyms a short channel name could stand for, e.g. "AHC FHD" -> {"ahc"}.

    Deliberately conservative. Long names and generic words ("HD", "TV", "NEWS")
    produce initials that collide with dozens of unrelated channels - the old map
    put a Bundesliga feed on beIN Sports that way.
    """
    s = re.sub(r"\(.*?\)|\[.*?\]", " ", s)
    s = QUALITY.sub(" ", s)
    words = [w for w in re.findall(r"[A-Za-z0-9]+", s) if w.lower() not in INITIAL_STOP]
    # Only a name that *is* an acronym ("AHC", "GSN"): "NBC 04 (WNBC) NEW YORK"
    # also contains "NBC", but it is a local station, not the national feed.
    if len(words) != 1:
        return set()
    w = words[0]
    if w.isupper() and w.isalpha() and 3 <= len(w) <= 5 and w.lower() not in GENERIC:
        return {w.lower()}
    return set()


def region_penalty(cid):
    """Prefer a national listing, then London, then any regional variant."""
    low = re.sub(r"[._-]+", " ", cid.lower())
    if not REGION.search(low):
        return 0
    return 1 if re.search(r"\b(lon|london)\b", low) else 2


def variant(name):
    """West/Pacific feeds and +1 timeshifts must never fall back onto the east/live feed."""
    n = name.lower()
    return ("west" if re.search(r"\b(west|pacific)\b", n) else "") + ("+1" if re.search(r"\+\s*1\b", n) else "")


def country_of(name):
    m = PREFIX.match(name)
    if not m:
        return None
    cc = m.group(1).upper()
    if cc not in EN or cc in NEU:
        return None
    return ALIAS.get(cc, cc)


def id_country(epg):
    """Country a guide id belongs to: "bbc1.uk" -> uk, "9Gem.au@Sydney" -> au."""
    if not epg:
        return None
    m = re.search(r"\.([a-z]{2})(?:[0-9_][a-z0-9_]*)?(?:@.*)?$", epg, re.I)
    if m and m.group(1).lower() in {"uk", "gb", "us", "ca", "au", "nz", "ie"}:
        return ALIAS.get(m.group(1).upper(), m.group(1).upper())
    return None


def guide_countries(url):
    """Countries a guide may serve. A UK channel is never matched against a US guide."""
    u = url.lower()
    if "us_locals1" in u or "_us2" in u or "epg_us" in u or "plex1" in u or "distrotv1" in u or "fanduel1" in u:
        return frozenset({"US"})
    if "_uk1" in u or "epg_gb" in u:
        return frozenset({"UK"})
    if "_ca2" in u or "epg_ca" in u:
        return frozenset({"CA"})
    if "_au1" in u or "epg_au" in u or "/au/" in u:
        return frozenset({"AU"})
    if "_nz1" in u or "epg_nz" in u or "/nz/" in u:
        return frozenset({"NZ"})
    if "_ie1" in u:
        return frozenset({"IE"})
    if "epgtalk.com" in u:
        # Schedules Direct carries US and Canadian lineups plus a few UK-only channels
        # (Sky Sports, Sky Cinema) that no other free UK guide lists.
        return frozenset({"US", "CA", "UK"})
    return frozenset()


def mixed(url):
    """A guide that carries several countries' lineups: exact names only, never acronyms."""
    return "epgtalk.com" in url


COUNTRY_GUIDES = {
    "US": ["US_LOCALS1", "US2", "FANDUEL1", "PLEX1", "DISTROTV1", "epg_US", "guide.xml"],
    "CA": ["CA2", "epg_CA", "guide.xml"],
    "UK": ["UK1", "epg_GB"],
    "IE": ["IE1"],
    "AU": ["mjh.nz/au", "AU1", "epg_AU"],
    "NZ": ["mjh.nz/nz", "NZ1", "epg_NZ"],
}
urls = list(guides)


def guide_rank(cc):
    order = COUNTRY_GUIDES.get(cc, [])
    def rank(url):
        for i, tag in enumerate(order):
            if tag in url:
                return i
        return 99
    return rank


# Indexes over every guide, bucketed by country so a lookalike name in another country is invisible.
by_name = collections.defaultdict(list)      # (country, normalised name) -> [(url, id, variant)]
by_initials = collections.defaultdict(list)  # (country, initials) -> [(url, id)]
by_token = collections.defaultdict(list)     # (country, token) -> [(url, id, tokens, variant)]
by_call = collections.defaultdict(list)      # (country, call sign) -> [(url, id)]
by_id = {}                                   # exact guide id -> (url, id)
CALL = re.compile(r"\b([KWC][A-Z]{2,3})(?:-(?:DT|TV|HD|LD|CD)\d*)?\b")
# Words that look like call signs inside parentheses but name a region or a brand.
CALL_STOP = {"WEST", "EAST", "NORTH", "SOUTH", "NEWS", "SPORT", "SPORTS", "LIVE", "MAIN",
             "MORE", "PLUS", "KIDS", "CITY", "HDTV", "CBS", "NBC", "ABC", "FOX", "CTV",
             "CBC", "TNT", "USA", "WWE", "UFC", "NFL", "NBA", "NHL", "MLB", "PPV", "TV", "HD"}
STOP = {"hd", "fhd", "sd", "uhd", "4k", "tv", "channel", "network", "the", "us", "usa", "uk", "ca", "au", "nz", "ie", "east", "eastern", "dt", "hdtv", "hevc", "and", "feed"}


def tokens(label):
    # Split camelCase brands the guide uses ("NewsNation", "SportsGrid") into words
    # so they compare equal to the playlist's spaced forms ("News Nation").
    # Only split when the second fragment is a real word (3+ trailing letters):
    # splitting "SportsNet" into sport+net would break the "SPORTSNET" lexeme.
    split = re.sub(r"([a-z]+)([A-Z][a-z]{3,})", r"\1 \2", label)
    t = re.sub(r"\(.*?\)|\[.*?\]", " ", split.lower().replace("&", " and ").replace("+", " plus "))
    # A trailing "National" is a feed word, not a brand word ("SBS World Movies
    # National" == "SBS World Movies"). Leading "National" stays ("National
    # Geographic"). A trailing quality suffix may follow it ("Viceland National HD").
    t = re.sub(r"\bnational(?:\s+(?:uhd|fhd|hd|sd|4k|hevc|h265|1080p?|720p?|50\s*fps|60\s*fps))*"
               r"\s*$", "", t)
    t = NUMBER_RE.sub(lambda m: " %s " % NUMBERS[m.group(1).lower()], t)
    return {stem(w) for w in re.findall(r"[a-z0-9]+", t) if w not in STOP}


def stem(w):
    """Cheap plural folding: "Sports" and "Sport" must compare equal."""
    if len(w) > 3 and w.endswith("s") and not w.endswith("ss"):
        return w[:-1]
    return w


def nums(ts):
    return {t for t in ts if t.isdigit()}


icon_by_id = {}  # (url, guide channel id) -> curated https icon or None
for url, chans in guides.items():
    countries0 = guide_countries(url)
    for cid0, val0 in chans.items():
        icon_by_id[(url, cid0)] = guide_icon(val0)
    if not countries0:
        continue
    countries = countries0
    for cid, raw_val in chans.items():
        names = guide_names(raw_val)
        by_id.setdefault(cid, (url, cid))
        # Only the guide's primary display name. Extra <display-name> tags are often broad
        # aliases ("Discovery Channel" on a Latin American feed) that would steal a channel
        # from the right country's guide.
        labels = [names[0]] if names else [re.sub(r"\.(uk|us2|ca2|us_locals1|au|nz|ie)$", "", cid).replace(".", " ")]
        for label in labels:
            # A guide may carry a French/Spanish sibling of an English channel ("Cable Public
            # Affairs HD Channel (French)"). Never index it: a bilingual acronym would otherwise
            # land the English channel on the foreign schedule.
            if FG.search(label):
                continue
            k = norm(label)
            ini = initials(label)
            ts = tokens(label)
            v = variant(label)
            rpen = region_penalty(label)
            for cc in countries:
                if k:
                    by_name[(cc, k)].append((url, cid, v, rpen))
                    # A region-suffixed feed ("BBC One Lon HD") also answers to the
                    # national name, but ranks behind a truly national listing.
                    if cc == "UK":
                        base = REGION.sub(" ", label)
                        bk = norm(base)
                        if bk and bk != k:
                            by_name[(cc, bk)].append((url, cid, v, rpen))
                if 2 <= len(ini) <= 5:
                    by_initials[(cc, ini)].append((url, cid, v, rpen, k))
                for t in ts:
                    by_token[(cc, t)].append((url, cid, ts, v, k))
            for m in CALL.finditer(label.upper()):
                for cc in countries:
                    by_call[(cc, m.group(1))].append((url, cid))

db = None if db_path.endswith(".json") else sqlite3.connect(db_path)
if db_path.endswith(".json"):
    # A debug-build lineup export (adb pull /sdcard/Android/data/com.edgestream.tv/files/lineup.json):
    # the TV's database is encrypted, so this is how the lineup reaches the tools now.
    rows = [(c["name"], c["groupName"], c.get("epgId"), c.get("tvgId")) for c in json.load(open(db_path, encoding="utf-8"))]
else:
    rows = db.execute("select name, groupName, epgId, tvgId from channels").fetchall()
mapping = collections.defaultdict(dict)  # key -> {country: (url, id)}
mapping_how = {}  # (key, country) -> how it was matched
stats, unmatched = collections.Counter(), collections.Counter()
detail = collections.defaultdict(list)
report_english = []  # one dict per English channel row for the curated report
report_foreign = []  # foreign channels: provider-guide-only


def choose(cands, cc, want_variant, prefer=None):
    """Best candidate for one country: populated feed, matching feed, national, preferred guide."""
    rank = guide_rank(cc)
    same = [c for c in cands if c[2] == want_variant] or cands
    if prefer:
        same = [c for c in same if c[0] == prefer] or same
    # A guide can declare an id it never fills (Channel.5.uk next to Channel.5.HD.uk). Prefer the
    # feed that actually carries programmes; fall back to uncounted guides, never guess silently
    # among known-empty ids when a populated one exists.
    positive = [c for c in same if progs(c[0], c[1]) > 0]
    if positive:
        same = positive
    else:
        unknown = [c for c in same if progs(c[0], c[1]) < 0]
        if unknown:
            same = unknown
    return min(same, key=lambda c: (c[3], rank(c[0]), len(c[1]), c[1]))


def report_row(name, group, cc, status, guide=None, gid=None, logo=None):
    report_english.append({
        "name": name, "group": group, "country": cc,
        "epg_status": status, "guide": guide, "guide_id": gid, "logo": logo,
    })


for name, group, epg, tvg in rows:
    if not english(name, group):
        report_foreign.append({
            "name": name, "group": group, "country": country_of(name),
            "epg_status": "provider-only", "guide": None, "guide_id": None, "logo": None,
        })
        continue
    _cc0 = country_of(name)
    if HEADER.search(name):
        stats["header (removed)"] += 1; report_row(name, group, _cc0, "unmatched"); continue
    if EMPTY_SLOT.search(name):
        stats["event slot, nothing on"] += 1; report_row(name, group, _cc0, "name-listing(idle)"); continue
    if EVENT.search(name) or EVENT2.search(name):
        stats["event (guide from name)"] += 1; report_row(name, group, _cc0, "name-listing(event)"); continue
    if ALWAYS_ON.match(name) or "24/7" in name or LOOP_GROUP.search(group):
        stats["24/7 (guide from name)"] += 1; report_row(name, group, _cc0, "name-listing(24/7)"); continue
    cc = _cc0
    if cc is None:
        stats["no country, not mapped"] += 1; report_row(name, group, None, "unmatched"); continue
    k = key(name)
    if cc in mapping.get(k, {}):
        _u, _i = mapping[k][cc]
        stats["matched"] += 1
        report_row(name, group, cc, f"mapped via {mapping_how.get((k, cc), 'name')}",
                   guide=_u, gid=_i, logo=icon_by_id.get((_u, _i)))
        continue

    v = variant(name)
    hit = None
    how = None
    ac_used = ""

    # 1. The channel's own name against the guide's display names, same country only.
    cands = by_name.get((cc, norm(name)), [])
    # The mixed Schedules Direct feed also carries US/Canadian lineups. A UK channel may
    # only borrow it when the name is not a known US/Canadian channel, otherwise
    # "UK| Discovery Channel" would get the US Discovery schedule and never reach the
    # correct UK "Discovery HD" match below.
    if cands and cc == "UK":
        nk = norm(name)
        rivals = by_name.get(("US", nk), []) + by_name.get(("CA", nk), [])
        if any(not mixed(c[0]) for c in rivals):
            cands = [c for c in cands if not mixed(c[0])]
    if cands:
        hit = choose(cands, cc, v); how = "name"

    # 2. Acronyms: "AHC" -> American Heroes Channel, "BBC" -> BBC One.
    if not hit:
        bare = PREFIX.sub("", name)
        for ac in sorted(acronyms(bare), key=len, reverse=True):
            if not (2 <= len(ac) <= 5):
                continue
            cands = by_initials.get((cc, ac), [])
            # Every candidate must be the same underlying channel, otherwise the acronym
            # is ambiguous ("ITV" could be ITV1/2/3/4) and guessing would put the wrong
            # schedule on screen - exactly the bug this map exists to prevent.
            cands = [c for c in cands if not mixed(c[0])]
            if cands and len({c[4] for c in cands}) == 1:
                hit = choose(cands, cc, v); how = "initials"; ac_used = ac; break

    # 3. US call signs: "US| CBS (WBBM)" -> WBBM-DT, not WBBM-DT2.
    if not hit and cc == "US":
        calls = [c for c in re.findall(r"\(([KWC][A-Z]{2,3})\)", name.upper()) if c not in CALL_STOP]
        cands = [c for call in calls for c in by_call.get((cc, call), [])]
        if cands:
            def sub(cid):
                m2 = re.search(r"-(?:DT|TV|LD|CD)(\d*)", cid.upper())
                return int(m2.group(1) or 1) if m2 else 1
            ranked = sorted(cands, key=lambda c: (sub(c[1]), guide_rank(cc)(c[0])))
            hit = ranked[0]; how = "call sign"

    # 4. A looser name with region and "TV/Channel" words dropped.
    if not hit:
        toks = tokens(name)
        one = len(toks) == 1 and not next(iter(toks)).isdigit() and len(next(iter(toks))) >= 4
        # 4a. A channel that is only a brand word plus TV/Channel/Network suffixes
        # ("COZI HD", "GET TV", "NHL CHANNEL FHD") may match a guide whose tokens
        # are exactly that word ("COZI TV" -> {cozi}). The plural/qualifier guard
        # below still requires every top candidate to share one guide norm.
        _bare = re.sub(r"\(.*?\)|\[.*?\]", " ", PREFIX.sub("", name))
        _bare = QUALITY.sub(" ", _bare)
        _w = re.findall(r"[A-Za-z0-9]+", _bare)
        while _w and _w[-1].lower() in {"us", "usa", "uk", "gb", "ca", "can", "au", "aus",
                                        "nz", "ie", "tv", "channel", "network", "television",
                                        "hdtv", "dt"}:
            _w.pop()
        if _w and _w[0].lower() == "the":
            _w = _w[1:]
        single_ok = len(_w) == 1 and _w[0].isalpha() and len(_w[0]) >= 3
        one_new = len(toks) == 1 and not next(iter(toks)).isdigit() \
            and len(next(iter(toks))) >= 3 and single_ok
        cn = norm(name)
        if len(toks) >= 2 or one or one_new:
            best, score, best_nks = None, 0.0, set()
            for u, i, ctoks, vv, nk in by_token.get((cc, min(toks, key=lambda t: len(by_token.get((cc, t), ())))), ()):  # rarest token
                if mixed(u) or vv != v or nums(toks) != nums(ctoks):
                    continue
                # A single leftover word ("West", "Classic") is only safe when the guide
                # channel is that exact word - never "West TV" or "Classic Rock".
                # 4a also accepts an exact token-set match for a bare-brand channel.
                if (one or one_new) and nk != next(iter(toks)) and not (one_new and ctoks == toks):
                    continue
                # "TNT Sports Event 10" is a pop-up event feed, not "TNT Sports 10".
                if ("event" in toks) != ("event" in ctoks) or ("ppv" in toks) != ("ppv" in ctoks):
                    continue
                j = len(toks & ctoks) / len(toks | ctoks)
                if j > score:
                    best, score, best_nks = (u, i, vv), j, {nk}
                elif j == score and best:
                    best_nks.add(nk)
                    if guide_rank(cc)(u) < guide_rank(cc)(best[0]):
                        best = (u, i, vv)
            # Several regional feeds with the same tokens ("Spectrum News 1 Evansville" /
            # "... Louisville") cannot be told apart from the channel name: leave it blank.
            if best and score >= 0.67 and (len(best_nks) == 1 or cn in best_nks):
                hit = best; how = "fuzzy name"
        # 4b. The rarest token can be a playlist-only word whose bucket is empty
        # ("RELIGIOUS 3ABN DARE TO DREAM": 'religious' appears in no guide). Retry
        # the other buckets in rarity order, but only accept near-exact j>=0.8
        # matches - looser names stay blank.
        if not hit and len(toks) >= 2:
            for tok in sorted(toks, key=lambda t: len(by_token.get((cc, t), ()))):
                fb, fscore, fb_nks = None, 0.0, set()
                for u, i, ctoks, vv, nk in by_token.get((cc, tok), ()):
                    if mixed(u) or vv != v or nums(toks) != nums(ctoks):
                        continue
                    if ("event" in toks) != ("event" in ctoks) or ("ppv" in toks) != ("ppv" in ctoks):
                        continue
                    j = len(toks & ctoks) / len(toks | ctoks)
                    if j > fscore:
                        fb, fscore, fb_nks = (u, i, vv), j, {nk}
                    elif j == fscore and fb:
                        fb_nks.add(nk)
                        if guide_rank(cc)(u) < guide_rank(cc)(fb[0]):
                            fb = (u, i, vv)
                if fb and fscore >= 0.8 and len(fb_nks) == 1:
                    hit = fb; how = "fuzzy name strict"
                    break

    # 5. The provider id only as an opt-in last resort, and only when it agrees on country.
    if not hit and allow_provider_id and epg and id_country(epg) == cc and epg in by_id:
        hit = by_id[epg] + (v,); how = "provider id"

    if hit:
        mapping[k][cc] = (hit[0], hit[1])
        mapping_how[(k, cc)] = how
        stats["matched"] += 1; stats["via " + how] += 1
        detail[how].append((name, cc, hit[1], ac_used))
        report_row(name, group, cc, f"mapped via {how}",
                   guide=hit[0], gid=hit[1], logo=icon_by_id.get((hit[0], hit[1])))
    else:
        stats["no online guide found"] += 1
        unmatched[group] += 1
        report_row(name, group, cc, "unmatched")

used = sorted({u for entries in mapping.values() for (u, _) in entries.values()})
remap = {old: new for new, old in enumerate(used)}
channels = {}
for k, entries in sorted(mapping.items()):
    channels[k] = [[remap[u], i, cc, icon_by_id.get((u, i))] for cc, (u, i) in sorted(entries.items())]
out = {"guides": used, "channels": channels}
json.dump(out, open(out_path, "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))

# Curated report next to the map: every English row + foreign rows as provider-only + coverage.
report_path = out_path[:-5] + "_report.json" if out_path.endswith(".json") else out_path + "_report.json"
n_entries = sum(len(v) for v in channels.values())
n_logos = sum(1 for v in channels.values() for e in v if e[3])
n_mapped_rows = sum(1 for r in report_english if r["epg_status"].startswith("mapped"))
n_listing_rows = sum(1 for r in report_english if r["epg_status"].startswith("name-listing"))
n_english = len(report_english)
epg_covered_pct = round(100.0 * (n_mapped_rows + n_listing_rows) / n_english, 1) if n_english else 0.0
logo_covered_pct = round(100.0 * n_logos / n_entries, 1) if n_entries else 0.0
logo_rows = sum(1 for r in report_english if r["logo"])
logo_covered_pct_rows = round(100.0 * logo_rows / n_english, 1) if n_english else 0.0
coverage = {
    "english_channels": n_english,
    "mapped": n_mapped_rows,
    "name_listing": n_listing_rows,
    "unmatched": n_english - n_mapped_rows - n_listing_rows,
    "foreign_provider_only": len(report_foreign),
    "epg_covered_pct": epg_covered_pct,
    "logo_covered_pct": logo_covered_pct,
    "logo_covered_pct_of_english": logo_covered_pct_rows,
    "map_keys": len(channels),
    "map_entries": n_entries,
    "map_logos": n_logos,
    "guides_used": len(used),
    "top_unmatched_groups": [{"group": g, "count": c} for g, c in unmatched.most_common(25)],
}
with open(report_path, "w", encoding="utf-8") as fh:
    json.dump({"coverage": coverage, "english": report_english, "foreign": report_foreign},
              fh, ensure_ascii=False, indent=1)

# Verify no channel was mapped into a guide from another country.
bad = 0
for k, entries in channels.items():
    for gi, gid, cc, _logo in entries:
        if cc not in guide_countries(used[gi]):
            bad += 1
            if bad <= 20:
                print("CROSS-COUNTRY", k, cc, used[gi], gid)

total = sum(v for k, v in stats.items() if not k.startswith("via"))
print("English channels:", total)
for k, v in stats.most_common():
    print(f"  {k:28s} {v}")
print("distinct mapped names:", len(channels), "entries:", n_entries, "guides needed:", len(used))
print(f"EPG covered: {epg_covered_pct}% ({n_mapped_rows + n_listing_rows}/{n_english} english rows; mapped {n_mapped_rows}, name-listing {n_listing_rows})")
print(f"logo covered: {logo_covered_pct}% of map entries ({n_logos}/{n_entries}; {logo_covered_pct_rows}% of english rows)")
print(f"foreign provider-only: {len(report_foreign)}")
print("report:", report_path)
print("cross-country entries:", bad)
print("unmatched by group:", unmatched.most_common(25))
if "--report" in sys.argv:
    for how in ("initials", "fuzzy name", "call sign"):
        print("\nvia " + how + ":")
        for name, cc, gid, ac in detail[how]:
            print("  %-40s %-2s %-6s -> %s" % (name, cc, ac, gid))
