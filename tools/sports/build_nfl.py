"""Slim nflverse player files for the TV's NFL props (data/NflProps.kt).

nflverse's players.csv and weekly stats are ~20 MB per refresh; the props code reads a handful of
columns and only players who have played. This keeps exactly those, under the same header names,
so the app parses them unchanged. Seasons follow games.csv: the latest season and the one before,
the two the app asks for.

Usage: python tools/sports/build_nfl.py <out_dir>
"""
import csv
import io
import os
import sys
import urllib.error
import urllib.request

NFLVERSE = "https://github.com/nflverse/nflverse-data/releases/download"
PLAYER_COLS = ["espn_id", "gsis_id", "display_name", "position", "latest_team"]
# Keep in step with NflProps.buildBook and NflProps.Market.column.
STAT_COLS = ["player_id", "season", "week", "team", "game_id",
             "passing_yards", "rushing_yards", "receiving_yards", "receptions"]


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": "edgestream-sports-data"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read().decode("utf-8")


def slim(text, cols, keep=lambda row: True):
    rows = csv.DictReader(io.StringIO(text))
    missing = [c for c in cols if c not in (rows.fieldnames or [])]
    if missing:
        # nflverse renamed a column: publishing a file without it would silently empty the props.
        raise SystemExit(f"nflverse dropped columns {missing}")
    out = io.StringIO()
    w = csv.writer(out, lineterminator="\n")
    w.writerow(cols)
    n = 0
    for row in rows:
        if keep(row):
            w.writerow([row[c] for c in cols])
            n += 1
    return out.getvalue(), n


def main(out_dir):
    os.makedirs(out_dir, exist_ok=True)
    games = csv.DictReader(io.StringIO(fetch(f"{NFLVERSE}/schedules/games.csv")))
    latest = max(int(g["season"]) for g in games if g["season"].isdigit())
    played = set()
    for season in (latest, latest - 1):
        try:
            text = fetch(f"{NFLVERSE}/stats_player/stats_player_week_{season}.csv")
        except urllib.error.HTTPError as e:
            # Before week 1 the new season's file doesn't exist yet; the app falls back the same way.
            if e.code == 404:
                print(f"stats {season}: not published yet")
                continue
            raise
        body, n = slim(text, STAT_COLS)
        played.update(r["player_id"] for r in csv.DictReader(io.StringIO(body)))
        write(out_dir, f"nfl-stats-{season}.csv", body, n)
    if not played:
        raise SystemExit("no player stats downloaded")
    body, n = slim(fetch(f"{NFLVERSE}/players/players.csv"), PLAYER_COLS,
                   keep=lambda r: r["espn_id"] and r["gsis_id"] in played)
    write(out_dir, "nfl-players.csv", body, n)


def write(out_dir, name, body, rows):
    with open(os.path.join(out_dir, name), "w", encoding="utf-8", newline="") as f:
        f.write(body)
    print(f"{name}: {rows} rows, {len(body.encode()) // 1024} KB")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "sports-out")
