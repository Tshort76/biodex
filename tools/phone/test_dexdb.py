"""dexdb's database edits, against the app's own exported schema."""

import json
import sqlite3
import unittest
from pathlib import Path

import dexdb

SCHEMAS = Path(__file__).resolve().parents[2] / "app/schemas/dev.tlong.biodex.data.db.AppDatabase"


def latest_schema_db() -> sqlite3.Connection:
    newest = max(SCHEMAS.glob("*.json"), key=lambda p: int(p.stem))
    conn = sqlite3.connect(":memory:")
    conn.execute("PRAGMA foreign_keys = ON")
    for entity in json.loads(newest.read_text())["database"]["entities"]:
        conn.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
    return conn


def add_species(conn, id, source="user", name=None, number=9001):
    conn.execute(
        "INSERT INTO species (id, regionId, dexNumber, source, detailsPending, commonName, "
        "scientificName, taxClass, kingdom, silhouetteRes, userEditedFields, uses, "
        "medicinalActivities, medicinalRecordCount) "
        "VALUES (?, 'pacific', ?, ?, 0, ?, NULL, 'INSECT', 'ANIMAL', 'sil', '[]', '[]', '[]', 0)",
        (id, number, source, name or id),
    )


def add_capture(conn, id, species_id, taken_at):
    conn.execute(
        "INSERT INTO captures (id, speciesId, photoUri, thumbPath, takenAt, createdAt) "
        "VALUES (?, ?, 'content://x', ?, ?, 0)",
        (id, species_id, f"thumbnails/{id}.jpg", taken_at),
    )


class DexDbTest(unittest.TestCase):
    def setUp(self):
        self.conn = latest_schema_db()
        self.conn.execute("INSERT INTO ecosystems (id, regionId, name, sortOrder) VALUES ('meadow', 'pacific', 'Meadow', 0)")
        add_species(self.conn, "user-moth", name="Grass-veneer Moth", number=9001)
        add_species(self.conn, "heron", source="curated", name="Great Blue Heron", number=3)
        self.conn.execute("INSERT INTO species_ecosystems VALUES ('user-moth', 'meadow')")
        self.conn.execute("INSERT INTO entries (speciesId, caughtAt, favoriteCaptureId) VALUES ('user-moth', 100, 'm1')")
        self.conn.execute("INSERT INTO entries (speciesId, caughtAt, favoriteCaptureId) VALUES ('heron', 100, 'h1')")
        add_capture(self.conn, "m1", "user-moth", 100)
        add_capture(self.conn, "h1", "heron", 100)
        add_capture(self.conn, "h2", "heron", 200)

    def count(self, table, where="1"):
        return self.conn.execute(f"SELECT COUNT(*) FROM {table} WHERE {where}").fetchone()[0]

    def test_finds_by_name_ignoring_case_or_by_id(self):
        self.assertEqual("user-moth", dexdb.find_species(self.conn, "grass-veneer moth").id)
        self.assertEqual("heron", dexdb.find_species(self.conn, "heron").id)
        with self.assertRaises(dexdb.DexDbError):
            dexdb.find_species(self.conn, "Varied Thrush")

    def test_removing_a_user_species_takes_everything_it_holds(self):
        gone = dexdb.remove_species(self.conn, dexdb.find_species(self.conn, "Grass-veneer Moth"))
        self.assertEqual(["thumbnails/m1.jpg"], [f for c in gone for f in c.files])
        for table in ("species", "entries", "captures", "species_ecosystems"):
            key = "id" if table == "species" else "speciesId"
            self.assertEqual(0, self.count(table, f"{key} = 'user-moth'"), table)
        self.assertEqual(2, self.count("captures"), "the other species is untouched")

    def test_a_catalogue_species_is_refused(self):
        with self.assertRaises(dexdb.DexDbError):
            dexdb.remove_species(self.conn, dexdb.find_species(self.conn, "heron"))
        self.assertEqual(1, self.count("species", "id = 'heron'"))

    def test_dropping_a_sighting_moves_the_caught_date_and_clears_the_favourite(self):
        last = dexdb.drop_capture(self.conn, dexdb.capture_by_id(self.conn, "h1"))
        self.assertFalse(last)
        caught, favourite = self.conn.execute(
            "SELECT caughtAt, favoriteCaptureId FROM entries WHERE speciesId = 'heron'"
        ).fetchone()
        self.assertEqual((200, None), (caught, favourite))

    def test_dropping_the_last_sighting_makes_the_species_uncaught(self):
        self.assertTrue(dexdb.drop_capture(self.conn, dexdb.capture_by_id(self.conn, "m1")))
        self.assertEqual(0, self.count("entries", "speciesId = 'user-moth'"))
        self.assertEqual(1, self.count("species", "id = 'user-moth'"), "the species itself stays")


if __name__ == "__main__":
    unittest.main()
