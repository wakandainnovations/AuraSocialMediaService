#!/usr/bin/env python3
"""Load the latest weekly release file into the tracked_releases table.

Runs on its own, separate from AuraSocialMediaService. The service's ReleaseDiscoveryService
writes one file per ISO week (IST), <dir>/releases-2026-W47.json:

    {
      "week": "2026-W47",
      "tmdb":   [ ...from TMDB, rewritten on every discovery pass - don't edit... ],
      "manual": [ ...yours: movies TMDB doesn't list, kept by discovery and carried into new weeks... ]
    }

This script watches the directory and, whenever the newest file changes (discovery rewrote it, a new
week started, or you edited it), upserts its entries into tracked_releases:

* "tmdb" entries: keyed by tmdb_id. An existing row gets its TMDB-owned fields refreshed; a new row
  is inserted only for an Indian language, and starts excluded unless the language is auto-tracked.
  entity_name, keywords and the exclusion flag are never changed (they belong to the admin UI).
* "manual" entries: keyed by entity_name (default: the title), source = 'MANUAL'. Only title and
  release_date (YYYY-MM-DD) are required. Optional: entity_name, original_title, language, keywords,
  context_terms, track_from, track_until, poster_path, genres, runtime, director, production_company.
  Editing an entry updates its row (fields left out keep their current value); removing it from
  the file does not delete the row - exclude the movie in aura-radix instead.

Usage:
    pip install -r scripts/requirements.txt
    python3 scripts/release_file_listener.py                 # watch, checking every 30s
    python3 scripts/release_file_listener.py --once          # sync the latest file once and exit

Database: --dsn, else $DATABASE_URL, else db.url/db.user/db.password from secrets.properties,
else the standard PG* environment variables. Tracking settings (days before/after, languages,
release.files.dir) come from application.properties so they match the service.
"""

import argparse
import datetime as dt
import json
import os
import re
import sys
import time
import unicodedata
from pathlib import Path
from typing import Dict, List, Optional, Tuple

SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_RESOURCES = SCRIPT_DIR.parent / "src" / "main" / "resources"
FILE_NAME = re.compile(r"releases-(\d{4})-W(\d{2})\.json$")

DEFAULT_INDIAN_LANGUAGES = ("hindi,tamil,telugu,kannada,malayalam,marathi,bengali,punjabi,gujarati,odia,"
                            "assamese,bhojpuri,urdu,sanskrit,konkani,tulu,maithili,rajasthani")
DEFAULT_AUTO_TRACK_LANGUAGES = "tamil,telugu,kannada,malayalam"


def log(msg: str) -> None:
    print(f"{dt.datetime.now().isoformat(timespec='seconds')} [ReleaseFile] {msg}", flush=True)


# ---- config ----

def read_properties(path: Path) -> Dict[str, str]:
    """Minimal Java .properties reader (key=value / key: value, # and ! comments)."""
    props: Dict[str, str] = {}
    if not path.is_file():
        return props
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line[0] in "#!":
            continue
        m = re.match(r"([^=:\s]+)\s*[=:]\s*(.*)$", line)
        if m:
            props[m.group(1)] = m.group(2).strip()
    return props


def split_list(value: Optional[str]) -> List[str]:
    if value is None:
        return []
    return [v.strip() for v in value.split(",") if v.strip()]


class Settings:
    def __init__(self, props: Dict[str, str]):
        self.days_before = int(props.get("tracking.days.before", 7))
        self.days_after = int(props.get("tracking.days.after", 7))
        self.indian = {l.lower() for l in split_list(props.get("tracking.indian.languages", DEFAULT_INDIAN_LANGUAGES))}
        self.auto_track = {l.lower() for l in split_list(props.get("tracking.auto.track.languages",
                                                                    DEFAULT_AUTO_TRACK_LANGUAGES))}
        self.files_dir = props.get("release.files.dir") or "releases"


def jdbc_to_dsn(secrets: Dict[str, str]) -> Optional[str]:
    url = secrets.get("db.url")
    if not url:
        return None
    m = re.match(r"jdbc:postgresql://([^/:?]+)(?::(\d+))?/([^?]+)", url)
    if not m:
        raise ValueError(f"Unsupported db.url: {url}")
    host, port, dbname = m.group(1), m.group(2) or "5432", m.group(3)
    from psycopg2.extensions import make_dsn
    return make_dsn(host=host, port=port, dbname=dbname, user=secrets.get("db.user", "postgres"),
                    password=secrets.get("db.password", "postgres"))


# ---- file parsing (mirrors ReleaseFileStore.java) ----

class EntryError(ValueError):
    pass


def _str(o: dict, key: str) -> Optional[str]:
    v = o.get(key)
    if v is None:
        return None
    v = str(v).strip()
    return v or None


def _required(o: dict, key: str) -> str:
    v = _str(o, key)
    if v is None:
        raise EntryError(f"missing {key}")
    return v


def _date(o: dict, key: str, required: bool) -> Optional[dt.date]:
    v = _str(o, key)
    if v is None:
        if required:
            raise EntryError(f"missing {key}")
        return None
    try:
        return dt.date.fromisoformat(v)
    except ValueError:
        raise EntryError(f"{key} '{v}' is not YYYY-MM-DD")


def _num(o: dict, key: str) -> Optional[float]:
    v = o.get(key)
    if v is None or (isinstance(v, str) and not v.strip()):
        return None
    try:
        return float(v)
    except (TypeError, ValueError):
        raise EntryError(f"{key} is not a number")


def _list(o: dict, key: str) -> List[str]:
    """A list field given either as a JSON array or a comma-separated string."""
    v = o.get(key)
    if v is None:
        return []
    if isinstance(v, list):
        return [s for s in (str(x).replace(",", " ").strip() for x in v if x is not None) if s]
    return split_list(str(v))


def _lower(s: Optional[str]) -> Optional[str]:
    return s.lower() if s else s


def _details(o: dict) -> dict:
    genres = _list(o, "genres")
    runtime = _num(o, "runtime")
    return {
        "genres": ",".join(genres) if genres else None,
        "runtime": int(runtime) if runtime and runtime > 0 else None,
        "director": _str(o, "director"),
        "production_company": _str(o, "production_company"),
    }


def _context_terms(o: dict) -> Optional[str]:
    terms = _list(o, "context_terms")
    return ",".join(terms) if terms else None


def parse_tmdb(o: dict) -> dict:
    if o.get("tmdb_id") is None:
        raise EntryError("missing tmdb_id")
    try:
        tmdb_id = int(o["tmdb_id"])
    except (TypeError, ValueError):
        raise EntryError("tmdb_id is not a number")
    return dict(tmdb_id=tmdb_id, title=_required(o, "title"), original_title=_str(o, "original_title"),
                language=_lower(_str(o, "language")), release_date=_date(o, "release_date", True),
                popularity=_num(o, "popularity"), poster_path=_str(o, "poster_path"),
                context_terms=_context_terms(o), **_details(o))


def parse_manual(o: dict) -> dict:
    title = _required(o, "title")
    track_from, track_until = _date(o, "track_from", False), _date(o, "track_until", False)
    if track_from and track_until and track_until < track_from:
        raise EntryError("track_until is before track_from")
    keywords = _list(o, "keywords")
    return dict(entity_name=_str(o, "entity_name") or title, title=title, original_title=_str(o, "original_title"),
                language=_lower(_str(o, "language")), release_date=_date(o, "release_date", True),
                track_from=track_from, track_until=track_until,
                keywords=",".join(keywords) if keywords else None, poster_path=_str(o, "poster_path"),
                context_terms=_context_terms(o), **_details(o))


def read_week_file(path: Path) -> Tuple[List[dict], List[dict], List[str]]:
    """(tmdb entries, manual entries, errors). Raises ValueError if the file isn't valid JSON."""
    root = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(root, dict):
        raise ValueError("top level is not a JSON object")
    errors: List[str] = []
    parsed: Dict[str, List[dict]] = {"tmdb": [], "manual": []}
    for key, parser in (("tmdb", parse_tmdb), ("manual", parse_manual)):
        items = root.get(key) or []
        if not isinstance(items, list):
            errors.append(f'"{key}" is not a list')
            continue
        for i, o in enumerate(items):
            if not isinstance(o, dict):
                errors.append(f"{key}[{i}] is not an object")
                continue
            try:
                parsed[key].append(parser(o))
            except EntryError as e:
                errors.append(f"{key}[{i}] {o.get('title')!r}: {e}")
    return parsed["tmdb"], parsed["manual"], errors


def latest_file(directory: Path) -> Optional[Path]:
    if not directory.is_dir():
        return None
    files = [p for p in directory.iterdir() if p.is_file() and FILE_NAME.fullmatch(p.name)]
    return max(files, key=lambda p: p.name) if files else None


# ---- keywords / entity names (mirrors the former TrackedReleaseSource.java logic) ----

def _is_latin_text(s: str) -> bool:
    for ch in s:
        cat = unicodedata.category(ch)
        if cat[0] in "NP" or cat == "Zs":
            continue
        if cat[0] == "L" and "LATIN" in unicodedata.name(ch, ""):
            continue
        return False
    return bool(s)


def _letters_digits(s: str) -> str:
    return "".join(ch for ch in s if unicodedata.category(ch)[0] in "LN")


def default_keywords(title: Optional[str], original_title: Optional[str]) -> str:
    """Hashtag form ("Mr. Lucky" -> "MrLucky") plus the plain spaced title for phrase search.
    A non-Latin original title is skipped (it would mostly add noise to the OR'd queries)."""
    by_lower: Dict[str, str] = {}
    for t in (title, original_title):
        if not t or not t.strip() or not _is_latin_text(t):
            continue
        compact = _letters_digits(t)
        if compact:
            by_lower.setdefault(compact.lower(), compact)
        # ':' would be read as a search operator by X, ',' is the keywords column's separator.
        spaced = "".join(ch if unicodedata.category(ch)[0] in "LN" or ch.isspace() or ch in ".'&-" else " "
                         for ch in t)
        spaced = re.sub(r"\s+", " ", spaced).strip()
        if " " in spaced:
            by_lower.setdefault(spaced.lower(), spaced)
    if not by_lower and title and title.strip():
        by_lower[title.lower()] = re.sub(r"\s+", " ", re.sub(r"[,:]", " ", title)).strip()
    return ",".join(by_lower.values())


def unique_entity_name(cur, r: dict) -> str:
    """entity_name keys every collected post, so it must be unique: same-named releases get
    " (Language)", then " (Language YYYY)", then the TMDB id appended."""
    base = r["title"].strip()
    lang = (r["language"] or "").capitalize()
    candidates = [base]
    if lang:
        candidates += [f"{base} ({lang})", f"{base} ({lang} {r['release_date'].year})"]
    candidates.append(f"{base} (tmdb {r['tmdb_id']})")
    cur.execute("SELECT entity_name FROM tracked_releases WHERE entity_name = ANY (%s)", (candidates,))
    taken = {row[0] for row in cur.fetchall()}
    return next((c for c in candidates if c not in taken), candidates[-1])


# ---- database writes ----

def window(r: dict, s: Settings) -> Tuple[dt.date, dt.date]:
    return (r.get("track_from") or r["release_date"] - dt.timedelta(days=s.days_before),
            r.get("track_until") or r["release_date"] + dt.timedelta(days=s.days_after))


def upsert_tmdb(cur, r: dict, s: Settings) -> bool:
    """Refresh an existing row's TMDB-owned fields, or insert a new Indian-language release.
    Returns True when a row was inserted."""
    start, end = window(r, s)
    cur.execute(
        "UPDATE tracked_releases SET title = %s, original_title = %s, language = %s, release_date = %s, "
        "track_from = %s, track_until = %s, popularity = %s, poster_path = %s, context_terms = %s, "
        "genres = %s, runtime = %s, director = %s, production_company = %s, updated_at = NOW() "
        "WHERE tmdb_id = %s",
        (r["title"], r["original_title"], r["language"], r["release_date"], start, end, r["popularity"],
         r["poster_path"], r["context_terms"], r["genres"], r["runtime"], r["director"],
         r["production_company"], r["tmdb_id"]))
    if cur.rowcount > 0:
        return False
    language = (r["language"] or "").strip().lower()
    if language not in s.indian:
        return False
    auto_exclude = language not in s.auto_track
    cur.execute(
        "INSERT INTO tracked_releases (tmdb_id, entity_name, title, original_title, language, release_date, "
        "track_from, track_until, keywords, category, source, popularity, poster_path, excluded, "
        "excluded_reason, excluded_at, context_terms, genres, runtime, director, production_company) "
        "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, 'media.movie', 'TMDB', %s, %s, %s, %s, "
        "CASE WHEN %s THEN NOW() END, %s, %s, %s, %s, %s) ON CONFLICT DO NOTHING",
        (r["tmdb_id"], unique_entity_name(cur, r), r["title"], r["original_title"], r["language"],
         r["release_date"], start, end, default_keywords(r["title"], r["original_title"]), r["popularity"],
         r["poster_path"], auto_exclude,
         f"Auto-excluded: {r['language'] or 'unknown'} is not auto-tracked (restore to track)" if auto_exclude else None,
         auto_exclude, r["context_terms"], r["genres"], r["runtime"], r["director"], r["production_company"]))
    return cur.rowcount > 0


def upsert_manual(cur, m: dict, s: Settings) -> str:
    """'inserted', 'updated' or 'skipped' (entity_name already used by a TMDB-tracked movie)."""
    start, end = window(m, s)
    cur.execute("SELECT id, source FROM tracked_releases WHERE entity_name = %s", (m["entity_name"],))
    row = cur.fetchone()
    if row and row[1] != "MANUAL":
        log(f"Skipped manual {m['entity_name']!r}: that entity_name is already a {row[1]}-tracked movie "
            f"(give the manual entry a different entity_name, or edit the existing movie in aura-radix).")
        return "skipped"
    if row:
        cur.execute(
            "UPDATE tracked_releases SET title = %s, original_title = COALESCE(%s, original_title), "
            "language = COALESCE(%s, language), release_date = %s, track_from = %s, track_until = %s, "
            "keywords = COALESCE(%s, keywords), poster_path = COALESCE(%s, poster_path), "
            "context_terms = COALESCE(%s, context_terms), genres = COALESCE(%s, genres), "
            "runtime = COALESCE(%s, runtime), director = COALESCE(%s, director), "
            "production_company = COALESCE(%s, production_company), updated_at = NOW() WHERE id = %s",
            (m["title"], m["original_title"], m["language"], m["release_date"], start, end, m["keywords"],
             m["poster_path"], m["context_terms"], m["genres"], m["runtime"], m["director"],
             m["production_company"], row[0]))
        return "updated"
    cur.execute(
        "INSERT INTO tracked_releases (entity_name, title, original_title, language, release_date, track_from, "
        "track_until, keywords, category, source, poster_path, context_terms, genres, runtime, director, "
        "production_company) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, 'media.movie', 'MANUAL', %s, %s, %s, %s, %s, %s)",
        (m["entity_name"], m["title"], m["original_title"], m["language"], m["release_date"], start, end,
         m["keywords"] or default_keywords(m["title"], m["original_title"]), m["poster_path"], m["context_terms"],
         m["genres"], m["runtime"], m["director"], m["production_company"]))
    return "inserted"


def sync(conn, path: Path, s: Settings) -> None:
    try:
        tmdb, manual, errors = read_week_file(path)
    except (ValueError, OSError) as e:
        log(f"{path} doesn't parse, nothing loaded (fix and save it again): {e}")
        return
    for err in errors:
        log(f"Skipped {err}")

    counts = {"tmdb_new": 0, "inserted": 0, "updated": 0, "skipped": 0, "failed": 0}
    # One transaction per entry, so one bad row doesn't undo the rest.
    for kind, entries in (("tmdb", tmdb), ("manual", manual)):
        for e in entries:
            try:
                with conn.cursor() as cur:
                    if kind == "tmdb":
                        counts["tmdb_new"] += upsert_tmdb(cur, e, s)
                    else:
                        counts[upsert_manual(cur, e, s)] += 1
                conn.commit()
            except Exception as ex:  # noqa: BLE001 - log and move on to the next entry
                conn.rollback()
                counts["failed"] += 1
                log(f"Failed to save {kind} {e.get('title')!r}: {ex}")
    log(f"Loaded {path.name}: {len(tmdb)} TMDB ({counts['tmdb_new']} new), {len(manual)} manual "
        f"({counts['inserted']} new, {counts['updated']} updated), "
        f"{counts['skipped'] + counts['failed'] + len(errors)} skipped/failed.")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--dir", help="release files directory (default: release.files.dir from application.properties, "
                                  "relative to the current directory)")
    ap.add_argument("--app-properties", type=Path, default=DEFAULT_RESOURCES / "application.properties")
    ap.add_argument("--secrets", type=Path, default=DEFAULT_RESOURCES / "secrets.properties")
    ap.add_argument("--dsn", help="PostgreSQL connection string (overrides $DATABASE_URL and --secrets)")
    ap.add_argument("--poll-seconds", type=int, default=30)
    ap.add_argument("--once", action="store_true", help="sync the latest file once and exit")
    args = ap.parse_args()

    import psycopg2

    settings = Settings(read_properties(args.app_properties))
    directory = Path(args.dir or settings.files_dir)
    dsn = args.dsn or os.environ.get("DATABASE_URL") or jdbc_to_dsn(read_properties(args.secrets)) or ""

    last: Tuple[Optional[Path], Optional[float]] = (None, None)
    conn = None
    while True:
        try:
            path = latest_file(directory)
            if path is None:
                if last != (None, 0):
                    log(f"No release file in {directory.resolve()} yet.")
                last = (None, 0)
            else:
                current = (path, path.stat().st_mtime)
                if current != last:
                    # Remembered before syncing, so a broken file is reported once, not every poll.
                    last = current
                    if conn is None or conn.closed:
                        conn = psycopg2.connect(dsn)
                    sync(conn, path, settings)
        except psycopg2.OperationalError as e:
            log(f"Database unavailable, will retry: {e}")
            last = (None, None)
            if conn is not None and not conn.closed:
                conn.close()
            conn = None
        except Exception as e:  # noqa: BLE001 - keep the listener alive
            log(f"Sync failed: {e}")
        if args.once:
            return 0
        time.sleep(max(5, args.poll_seconds))


if __name__ == "__main__":
    sys.exit(main())
