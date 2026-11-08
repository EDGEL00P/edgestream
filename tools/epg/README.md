# EPG grabber + curation

Free English XMLTV guides only: epgshare01, epg.pw, mjh.nz, epgtalk.
English-only curation scope: foreign channels are reported as provider-guide-only.

## Pipeline

```
fetch_guides.py  -> guides.json        {url: {cid: {"names": [...], "icon": url|null}}}
count_guides.py  -> guide_counts.json  {url: {cid: programme_count}}   (optional, --counts)
build_map.py     -> epg_map.json + epg_map_report.json
grab.py          -> orchestrates all of the above + verify + optional --publish
```

One command (reuses `guides.json` unless `--refresh-guides`):

```
python tools/epg/grab.py --publish
python tools/epg/grab.py --refresh-guides --counts --publish
python tools/epg/grab.py --db <path> --work <dir> --publish
```

Defaults: `--db` = `live5.db` in the work dir, `--work` = `$EDGE_EPG_WORK`, else
`<system temp>/edgestream_epg`. The report lands at
`<work>/epg_map_report.json` (every English row + foreign rows + coverage summary).

Manual steps (what grab.py runs):

```
python tools/epg/fetch_guides.py <work>/guides.json
python tools/epg/count_guides.py <work>/guides.json <work>/guide_counts.json
python tools/epg/build_map.py <db> <work>/guides.json <work>/epg_map.json --counts <work>/guide_counts.json
```

## Asset format (`app/src/main/assets/epg_map.json`)

```json
{"guides": ["https://.../epg_US.xml.gz", "..."],
 "channels": {"bbc one": [[0, "bbc1.uk", "UK", "https://.../logo.png"]]}}
```

Entry: `[guideIndex, guideChannelId, country, logoUrl|null]`. The app still parses
old 2/3-element entries (`logo = null`). `key()` must stay identical between
`build_map.py` and `EpgMap.key()` in Kotlin.

## Logo precedence

1. Curated map logo (`EpgMap.logo(...)`) — **always wins**, even when "fill logos" is off.
2. Runtime guide `<icon>` captured during a guide refresh (`XmltvParser` channel icons).
3. Provider logo (iptv-org fill only when the fill-logos setting is on).

Foreign channels keep provider logos only; no curated entry is built for them.

## Matcher rules (`build_map.py`)

A channel is matched by its name only — never by the provider id (routine
provider ids point at the wrong channel or the wrong country). Matching is
bucketed by country, so a UK channel can never land on a US/IE/CA/AU listing,
and a `west`/Pacific or `+1` feed can never fall back to the east/live feed.
Cross-country count must stay `0` (build_map verifies it).

In order:

1. **Exact normalized name** in the same country.
2. **Single-word acronyms** (`AHC` -> American Heroes Channel). Multi-word
   acronyms are deliberately rejected (too ambiguous — the old beIN/Bundesliga
   bug). Every candidate must be the same underlying channel.
3. **US call signs** in parentheses (`WBBM` -> WBBM-DT, not WBBM-DT2).
4. **Fuzzy token overlap** (Jaccard) with guards: same numbers, same
   west/+1 variant, no event/ppv mismatch, and every top candidate must be
   the same guide name — otherwise the row stays blank. Tokenization:
   - Guide camelCase brands split into words (`NewsNation` == `News Nation`,
     `SportsGrid` == `Sports Grid`, `TalkingPictures` == `Talking Pictures`),
     but short suffixes like `Net` are not split (`SportsNet` keeps the
     `SPORTSNET` lexeme).
   - A trailing `National` is a feed word, not a brand word
     (`SBS World Movies National` == `SBS World Movies`). Leading `National`
     stays (`National Geographic`).
4a. **Bare-brand single-word names** (`COZI HD`, `GET TV`, `NHL CHANNEL FHD`):
   the stripped name must be one content word (plus TV/Channel/Network
   suffixes), and the guide's token set must equal it exactly
   (`COZI TV` -> `{cozi}`). The bare-word test is what keeps
   `USA NETWORK WEST` off `West TV`.
4b. **Strict bucket fallback**: if the rarest token never appears in any guide
   (`RELIGIOUS 3ABN DARE TO DREAM` — `religious` is in no guide name), retry
   the other token buckets, accepting only Jaccard `>= 0.8` with a unique top
   candidate. Looser names stay blank.
5. **Provider id** only with `--allow-provider-id` *and* only when the guide
   id's own country suffix agrees with the channel's country. Never blind.

`key()` is shared verbatim with Kotlin `EpgMap.key()` — do not diverge.

## Coverage (live5.db, 6752 English rows)

- Before: EPG `82.4%` (5561/6752), mapped 2236, name-listing 3325, unmatched
  1191, 1848 keys / 1909 entries / 16 guides, 484 logos, cross-country 0.
- After this change: EPG `82.7%` (5586/6752), mapped 2261, name-listing 3325,
  unmatched 1166 (1147 no-guide + 19 no-country), 1870 keys / 1932 entries /
  17 guides (DISTROTV1 now used), 491 logos, cross-country 0.

New matches include: `COZI HD`->`COZI.TV.us2`, `GET TV`->`plex.tv.GET.plex`,
`NHL CHANNEL FHD`->`NHL.Network.HD.us2`, `THE MLB CHANNEL`->`MLB.Network.HD.us2`,
`UFC CHANNEL FHD`->`plex.tv.UFC.plex`, `NEWS NATION HD (EAST)`->`NewsNation.us2`,
`SPORTS GRID HD`->`SportsGrid.us2`, `REELZ HD`->`ReelzChannel.HD.us2`,
`MSG HD`->`MSG.National.us2`, `DAYSTAR FHD`->`Daystar.TV.distro`,
`RELIGIOUS 3ABN DARE TO DREAM`->`3ABN.Dare.to.Dream.Network.us2`,
`SBS VICELAND NATIONAL HD`->`442911`, `ABC ME NATIONAL`->`3269`,
`TALKING PICTURES TV`->`TalkingPictures.uk`, plus CA `VISION HD`, `AQUARIUM`,
`SPORTSMAN HD`, `SMITHSONIAN HD`. `NEWS NATION HD (WEST)` and all `+1`/`west`
variants are deliberately left unmatched when the guide has no matching
variant.

Remaining unmatched are dominated by per-team event feeds with no free guide
(EFL/EPL PPV team feeds, Sky Sports+ pop-ups, NFL/NHL/NBA per-team channels,
OHL, ESPN alt feeds), city-local stations (CA locals, Spectrum regionals,
AU city feeds — ambiguous by name), and no-country cricket rows
(`UK-CA| ...` / `UK-IN| ...`) whose ids point at US/IN/ZA listings and so
cannot be keyed into an EN guide without guessing a schedule.
