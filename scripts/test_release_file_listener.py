"""python3 -m unittest discover -s scripts  (no database needed)

Set TEST_DATABASE_URL to also run SyncDatabaseTest against PostgreSQL: it works on a TEMP copy of
tracked_releases (which shadows the real table for that connection only), so no real rows change.
"""
import datetime as dt
import json
import os
import tempfile
import unittest
from pathlib import Path

import release_file_listener as rfl


class DefaultKeywordsTest(unittest.TestCase):
    def test_multi_word_title_gets_hashtag_and_phrase(self):
        self.assertEqual("MrLucky,Mr. Lucky", rfl.default_keywords("Mr. Lucky", "Mr. Lucky"))

    def test_single_word_title_gets_one_keyword(self):
        self.assertEqual("Chandrahaasa", rfl.default_keywords("Chandrahaasa", None))

    def test_non_latin_original_title_is_skipped(self):
        self.assertEqual("JanaNayagan,Jana Nayagan", rfl.default_keywords("Jana Nayagan", "ஜன நாயகன்"))

    def test_distinct_latin_original_title_is_added(self):
        self.assertEqual("Toxic,ToxicAFairyTale,Toxic A Fairy Tale",
                         rfl.default_keywords("Toxic", "Toxic: A Fairy Tale"))

    def test_comma_in_title_never_splits_into_two_keywords(self):
        self.assertEqual(["HelloWorld", "Hello World"], rfl.split_list(rfl.default_keywords("Hello, World", None)))


class FileParsingTest(unittest.TestCase):
    def write(self, d, name, data):
        p = Path(d) / name
        p.write_text(json.dumps(data) if not isinstance(data, str) else data, encoding="utf-8")
        return p

    def test_manual_entry_defaults_and_list_forms(self):
        m = rfl.parse_manual({"title": "Coolie", "release_date": "2026-12-05", "keywords": "Coolie, Coolie Movie",
                              "context_terms": ["Rajinikanth", "Lokesh Kanagaraj"], "language": "Tamil",
                              "genres": ["Action", "Thriller"], "runtime": 165})
        self.assertEqual("Coolie", m["entity_name"])
        self.assertEqual("Coolie,Coolie Movie", m["keywords"])
        self.assertEqual("Rajinikanth,Lokesh Kanagaraj", m["context_terms"])
        self.assertEqual("tamil", m["language"])
        self.assertEqual("Action,Thriller", m["genres"])
        self.assertEqual(165, m["runtime"])
        s = rfl.Settings({})
        self.assertEqual((dt.date(2026, 12, 3), dt.date(2026, 12, 7)), rfl.window(m, s))

    def test_explicit_tracking_window_wins(self):
        m = rfl.parse_manual({"title": "X", "release_date": "2026-12-05", "track_from": "2026-12-01",
                              "track_until": "2026-12-20"})
        self.assertEqual((dt.date(2026, 12, 1), dt.date(2026, 12, 20)), rfl.window(m, rfl.Settings({})))

    def test_bad_entries_are_reported_not_fatal(self):
        with tempfile.TemporaryDirectory() as d:
            p = self.write(d, "releases-2026-W47.json", {"tmdb": [{"title": "No id", "release_date": "2026-11-20"}],
                                                         "manual": [{"title": "Ok", "release_date": "2026-11-20"},
                                                                    {"title": "Bad", "release_date": "20/11/2026"}]})
            tmdb, manual, removed, errors = rfl.read_week_file(p)
            self.assertEqual([], tmdb)
            self.assertEqual(["Ok"], [m["title"] for m in manual])
            self.assertEqual(2, len(errors))

    def test_removed_entries_are_parsed_and_bad_ones_reported(self):
        with tempfile.TemporaryDirectory() as d:
            p = self.write(d, "releases-2026-W48.json", {"tmdb": [], "manual": [],
                                                         "removed": [{"tmdb_id": 1504630, "title": "Mysaa"},
                                                                     {"tmdb_id": "77"}, {"title": "No id"}]})
            _, _, removed, errors = rfl.read_week_file(p)
            self.assertEqual([{"tmdb_id": 1504630, "title": "Mysaa"}, {"tmdb_id": 77, "title": None}], removed)
            self.assertEqual(1, len(errors))

    def test_file_without_removed_list_still_loads(self):
        with tempfile.TemporaryDirectory() as d:
            _, _, removed, errors = rfl.read_week_file(self.write(d, "releases-2026-W47.json", {"tmdb": []}))
            self.assertEqual(([], []), (removed, errors))

    def test_invalid_json_raises(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(ValueError):
                rfl.read_week_file(self.write(d, "releases-2026-W47.json", "{not json"))

    def test_latest_file_picks_newest_week(self):
        with tempfile.TemporaryDirectory() as d:
            for name in ("releases-2026-W52.json", "releases-2027-W01.json", "releases-2026-W47.json.tmp", "notes.json"):
                self.write(d, name, {})
            self.assertEqual("releases-2027-W01.json", rfl.latest_file(Path(d)).name)


class LanguageExclusionTest(unittest.TestCase):
    def test_only_auto_tracked_languages_start_included(self):
        s = rfl.Settings({})
        self.assertEqual((False, None), rfl.language_exclusion("tamil", s))
        excluded, reason = rfl.language_exclusion("hindi", s)
        self.assertTrue(excluded)
        self.assertIn("hindi is not auto-tracked", reason)


@unittest.skipUnless(os.environ.get("TEST_DATABASE_URL"), "set TEST_DATABASE_URL to run against PostgreSQL")
class SyncDatabaseTest(unittest.TestCase):
    TODAY = dt.datetime.now(rfl.IST).date()

    def setUp(self):
        import psycopg2
        self.conn = psycopg2.connect(os.environ["TEST_DATABASE_URL"])
        with self.conn.cursor() as cur:
            cur.execute("CREATE TEMP TABLE tracked_releases (LIKE public.tracked_releases INCLUDING ALL)")
        self.conn.commit()
        self.dir = tempfile.TemporaryDirectory()
        self.s = rfl.Settings({})

    def tearDown(self):
        self.conn.close()  # drops the temp table
        self.dir.cleanup()

    def entry(self, tmdb_id, title, language, release):
        return {"tmdb_id": tmdb_id, "title": title, "original_title": title, "language": language,
                "release_date": release.isoformat(), "popularity": 1.0, "poster_path": None,
                "context_terms": [], "genres": [], "runtime": None, "director": None, "production_company": None}

    def sync(self, tmdb=(), removed=()):
        p = Path(self.dir.name) / "releases-2026-W48.json"
        p.write_text(json.dumps({"tmdb": list(tmdb), "manual": [], "removed": list(removed)}), encoding="utf-8")
        rfl.sync(self.conn, p, self.s)

    def row(self, tmdb_id):
        with self.conn.cursor() as cur:
            cur.execute("SELECT excluded, excluded_reason, excluded_at IS NOT NULL, release_date FROM tracked_releases "
                        "WHERE tmdb_id = %s", (tmdb_id,))
            return cur.fetchone()

    def test_removed_movie_is_excluded_then_restored_when_dated_again(self):
        soon = self.TODAY + dt.timedelta(days=1)
        self.sync(tmdb=[self.entry(1, "Mysaa", "telugu", soon)])
        self.assertEqual((False, None, False, soon), self.row(1))

        self.sync(removed=[{"tmdb_id": 1, "title": "Mysaa"}])
        self.assertEqual((True, rfl.REMOVED_REASON, True, soon), self.row(1))

        later = self.TODAY + dt.timedelta(days=30)
        self.sync(tmdb=[self.entry(1, "Mysaa", "telugu", later)])
        self.assertEqual((False, None, False, later), self.row(1))

    def test_redated_non_auto_tracked_movie_gets_language_exclusion_back(self):
        soon = self.TODAY + dt.timedelta(days=1)
        self.sync(tmdb=[self.entry(2, "Diler", "hindi", soon)])
        with self.conn.cursor() as cur:  # as if it had been excluded for losing its India date
            cur.execute("UPDATE tracked_releases SET excluded_reason = %s WHERE tmdb_id = 2", (rfl.REMOVED_REASON,))
        self.conn.commit()
        self.sync(tmdb=[self.entry(2, "Diler", "hindi", soon)])
        excluded, reason, _, _ = self.row(2)
        self.assertTrue(excluded)
        self.assertIn("hindi is not auto-tracked", reason)

    def test_removed_leaves_admin_choices_and_ended_rows_alone(self):
        soon = self.TODAY + dt.timedelta(days=1)
        past = self.TODAY - dt.timedelta(days=10)
        self.sync(tmdb=[self.entry(3, "Restored", "tamil", soon), self.entry(4, "Pulse", "tamil", soon),
                        self.entry(5, "Ended", "tamil", past)])
        with self.conn.cursor() as cur:
            cur.execute("UPDATE tracked_releases SET relevance_override = TRUE WHERE tmdb_id = 3")
            cur.execute("UPDATE tracked_releases SET excluded = TRUE, excluded_reason = 'not worth it' WHERE tmdb_id = 4")
        self.conn.commit()
        self.sync(removed=[{"tmdb_id": i, "title": None} for i in (3, 4, 5, 999)])
        self.assertEqual((False, None), self.row(3)[:2])
        self.assertEqual((True, "not worth it"), self.row(4)[:2])
        self.assertEqual((False, None), self.row(5)[:2])
        # A movie the admin excluded for their own reason stays excluded when TMDB dates it again.
        self.sync(tmdb=[self.entry(4, "Pulse", "tamil", soon)])
        self.assertEqual((True, "not worth it"), self.row(4)[:2])


class ConfigTest(unittest.TestCase):
    def test_jdbc_url_becomes_dsn(self):
        dsn = rfl.jdbc_to_dsn({"db.url": "jdbc:postgresql://db.example:6543/aura?ssl=true", "db.user": "u",
                               "db.password": "p"})
        for part in ("host=db.example", "port=6543", "dbname=aura", "user=u", "password=p"):
            self.assertIn(part, dsn)


if __name__ == "__main__":
    unittest.main()
