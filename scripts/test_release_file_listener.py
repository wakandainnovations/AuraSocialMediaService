"""python3 -m unittest discover -s scripts  (no database needed)"""
import datetime as dt
import json
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
            tmdb, manual, errors = rfl.read_week_file(p)
            self.assertEqual([], tmdb)
            self.assertEqual(["Ok"], [m["title"] for m in manual])
            self.assertEqual(2, len(errors))

    def test_invalid_json_raises(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(ValueError):
                rfl.read_week_file(self.write(d, "releases-2026-W47.json", "{not json"))

    def test_latest_file_picks_newest_week(self):
        with tempfile.TemporaryDirectory() as d:
            for name in ("releases-2026-W52.json", "releases-2027-W01.json", "releases-2026-W47.json.tmp", "notes.json"):
                self.write(d, name, {})
            self.assertEqual("releases-2027-W01.json", rfl.latest_file(Path(d)).name)


class ConfigTest(unittest.TestCase):
    def test_jdbc_url_becomes_dsn(self):
        dsn = rfl.jdbc_to_dsn({"db.url": "jdbc:postgresql://db.example:6543/aura?ssl=true", "db.user": "u",
                               "db.password": "p"})
        for part in ("host=db.example", "port=6543", "dbname=aura", "user=u", "password=p"):
            self.assertIn(part, dsn)


if __name__ == "__main__":
    unittest.main()
