#!/usr/bin/env python3
"""Remove test rows from the BioDex database on the attached phone, from the Mac.

The app has no way to remove a species the owner added, on purpose: this is for
clearing up after a test, not a feature. It pulls the database, edits it
locally, and pushes it back with the app stopped.

    python3 tools/phone/dexdb.py list                      # species you added, with sightings
    python3 tools/phone/dexdb.py show "Grass-veneer Moth"  # one species and its sightings
    python3 tools/phone/dexdb.py remove "Grass-veneer Moth"          # dry run: says what would go
    python3 tools/phone/dexdb.py remove "Grass-veneer Moth" --apply  # does it
    python3 tools/phone/dexdb.py drop-capture <capture-id> --apply   # one sighting, any species

`remove` takes only a species the owner added: a catalogue species would come
back on the next catalogue import, so for one of those, drop its sightings
instead. Every --apply first saves the pulled database, and the thumbnails it
is about to delete, under .phone-backups/<timestamp>/.

The app's persisted read grant on a removed photo is not handed back — only the
app can do that — so it stays counted toward Android's cap until the next
uninstall. At 4,500 grants that would matter; at a few test photos it does not.
"""

from __future__ import annotations

import argparse
import datetime
import os
import shutil
import sqlite3
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path

PKG = "dev.tlong.biodex"
APP_DIR = f"/data/data/{PKG}"
DB_FILES = ("biodex.db", "biodex.db-wal", "biodex.db-shm")
REPO = Path(__file__).resolve().parents[2]
BACKUPS = REPO / ".phone-backups"

# The app's own rule (D75, Daos.kt SYNC_CAUGHT_AT_SQL): caught on the earliest photo's date.
SYNC_CAUGHT_AT_SQL = (
    "UPDATE entries SET caughtAt = "
    "(SELECT MIN(c.takenAt) FROM captures c WHERE c.speciesId = entries.speciesId) "
    "WHERE EXISTS (SELECT 1 FROM captures c WHERE c.speciesId = entries.speciesId) "
    "AND speciesId = ?"
)


# --------------------------------------------------------------------------
# The database part: plain sqlite, no phone, so it is tested on its own.
# --------------------------------------------------------------------------

@dataclass(frozen=True)
class Species:
    id: str
    dex_number: int
    common_name: str
    scientific_name: str | None
    source: str


@dataclass(frozen=True)
class Capture:
    id: str
    species_id: str
    taken_at: int
    location_label: str | None
    thumb_path: str | None
    local_copy_path: str | None

    @property
    def files(self) -> list[str]:
        return [p for p in (self.thumb_path, self.local_copy_path) if p]


class DexDbError(Exception):
    pass


def open_db(path: Path) -> sqlite3.Connection:
    conn = sqlite3.connect(path)
    # Cascades from species to entries, captures and ecosystem rows are declared in the
    # schema, but sqlite only honours them with this set.
    conn.execute("PRAGMA foreign_keys = ON")
    return conn


def _species(row) -> Species:
    return Species(*row)


_SPECIES_COLS = "id, dexNumber, commonName, scientificName, source"
_CAPTURE_COLS = "id, speciesId, takenAt, locationLabel, thumbPath, localCopyPath"


def find_species(conn: sqlite3.Connection, query: str) -> Species:
    """By id, or by common or scientific name ignoring case. Exactly one, or an error."""
    rows = conn.execute(
        f"SELECT {_SPECIES_COLS} FROM species "
        "WHERE id = ? OR lower(commonName) = lower(?) OR lower(scientificName) = lower(?)",
        (query, query, query),
    ).fetchall()
    if not rows:
        raise DexDbError(f"no species is called {query!r}")
    if len(rows) > 1:
        names = ", ".join(f"{r[2]} ({r[0]})" for r in rows)
        raise DexDbError(f"{query!r} names more than one species: {names}; pass the id")
    return _species(rows[0])


def user_species(conn: sqlite3.Connection) -> list[tuple[Species, int]]:
    rows = conn.execute(
        f"SELECT {', '.join('s.' + c.strip() for c in _SPECIES_COLS.split(','))}, "
        "(SELECT COUNT(*) FROM captures c WHERE c.speciesId = s.id) "
        "FROM species s WHERE s.source = 'user' ORDER BY s.dexNumber"
    ).fetchall()
    return [(_species(r[:5]), r[5]) for r in rows]


def captures_of(conn: sqlite3.Connection, species_id: str) -> list[Capture]:
    rows = conn.execute(
        f"SELECT {_CAPTURE_COLS} FROM captures WHERE speciesId = ? ORDER BY takenAt",
        (species_id,),
    ).fetchall()
    return [Capture(*r) for r in rows]


def capture_by_id(conn: sqlite3.Connection, capture_id: str) -> Capture:
    row = conn.execute(f"SELECT {_CAPTURE_COLS} FROM captures WHERE id = ?", (capture_id,)).fetchone()
    if row is None:
        raise DexDbError(f"no sighting has id {capture_id!r}")
    return Capture(*row)


def check_removable(species: Species) -> None:
    if species.source != "user":
        raise DexDbError(
            f"{species.common_name} is a catalogue species, and the next catalogue import "
            "would put it back; drop its sightings with drop-capture instead"
        )


def remove_species(conn: sqlite3.Connection, species: Species) -> list[Capture]:
    """Deletes a species the owner added, with its entry, sightings and ecosystem rows.

    Returns the sightings that went, so their files can be deleted too.
    """
    check_removable(species)
    gone = captures_of(conn, species.id)
    with conn:
        conn.execute("DELETE FROM species WHERE id = ?", (species.id,))
    return gone


def drop_capture(conn: sqlite3.Connection, capture: Capture) -> bool:
    """Deletes one sighting the way the app's photo viewer does (applyDeletion).

    Returns True when it was the species' last one, which makes the species uncaught.
    """
    with conn:
        # entries.favoriteCaptureId has no foreign key, so nothing else would clear it.
        conn.execute(
            "UPDATE entries SET favoriteCaptureId = NULL WHERE favoriteCaptureId = ?",
            (capture.id,),
        )
        conn.execute("DELETE FROM captures WHERE id = ?", (capture.id,))
        left = conn.execute(
            "SELECT COUNT(*) FROM captures WHERE speciesId = ?", (capture.species_id,)
        ).fetchone()[0]
        if left == 0:
            conn.execute("DELETE FROM entries WHERE speciesId = ?", (capture.species_id,))
        else:
            conn.execute(SYNC_CAUGHT_AT_SQL, (capture.species_id,))
    return left == 0


# --------------------------------------------------------------------------
# The phone part.
# --------------------------------------------------------------------------

def adb_path() -> str:
    props = REPO / "local.properties"
    for line in props.read_text().splitlines() if props.exists() else []:
        if line.startswith("sdk.dir="):
            candidate = Path(line.split("=", 1)[1]) / "platform-tools" / "adb"
            if candidate.exists():
                return str(candidate)
    found = shutil.which("adb")
    if not found:
        raise DexDbError("adb not found: local.properties has no usable sdk.dir")
    return found


def adb(*args: str, stdin: bytes | None = None) -> bytes:
    result = subprocess.run([adb_path(), *args], input=stdin, capture_output=True)
    if result.returncode != 0:
        raise DexDbError(f"adb {' '.join(args)} failed: {result.stderr.decode().strip()}")
    return result.stdout


def run_as(command: str, stdin: bytes | None = None) -> bytes:
    return adb("exec-out", "run-as", PKG, "sh", "-c", command, stdin=stdin)


def pull_db(into: Path) -> Path:
    """Pulls the database with its WAL and folds the WAL in, so one file holds everything."""
    for name in DB_FILES:
        data = run_as(f"cat {APP_DIR}/databases/{name} 2>/dev/null || true")
        if data:
            (into / name).write_bytes(data)
    db = into / "biodex.db"
    if not db.exists():
        raise DexDbError("the phone has no BioDex database — is the app installed?")
    conn = sqlite3.connect(db)
    conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    conn.close()
    for name in DB_FILES[1:]:
        (into / name).unlink(missing_ok=True)
    return db


def push_db(db: Path) -> None:
    adb("shell", "am", "force-stop", PKG)
    # The fresh -wal/-shm would be replayed over the pushed file.
    run_as(f"rm -f {APP_DIR}/databases/biodex.db-wal {APP_DIR}/databases/biodex.db-shm")
    run_as(f"cat > {APP_DIR}/databases/biodex.db", stdin=db.read_bytes())


def backup(db: Path, files: list[str]) -> Path:
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    dest = BACKUPS / stamp
    dest.mkdir(parents=True)
    shutil.copy2(db, dest / "biodex.db")
    for rel in files:
        target = dest / "files" / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(run_as(f"cat {APP_DIR}/files/{rel} 2>/dev/null || true"))
    return dest


def delete_files(files: list[str]) -> None:
    for rel in files:
        run_as(f"rm -f {APP_DIR}/files/{rel}")


def describe(c: Capture) -> str:
    when = datetime.datetime.fromtimestamp(c.taken_at / 1000).strftime("%Y-%m-%d %H:%M")
    return f"  {c.id}  {when}  {c.location_label or '(no place)'}"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("list", help="species you added, with their sighting counts")
    show = sub.add_parser("show", help="one species and its sightings")
    show.add_argument("species")
    remove = sub.add_parser("remove", help="a species you added, with everything it holds")
    remove.add_argument("species")
    remove.add_argument("--apply", action="store_true")
    drop = sub.add_parser("drop-capture", help="one sighting of any species")
    drop.add_argument("capture_id")
    drop.add_argument("--apply", action="store_true")
    args = parser.parse_args(argv)

    with tempfile.TemporaryDirectory() as tmp:
        db = pull_db(Path(tmp))
        conn = open_db(db)
        try:
            if args.command == "list":
                for s, n in user_species(conn):
                    print(f"#{s.dex_number}  {s.common_name}  ({s.scientific_name or '-'})  {n} sighting(s)  {s.id}")
                return 0
            if args.command == "show":
                s = find_species(conn, args.species)
                print(f"#{s.dex_number}  {s.common_name}  ({s.scientific_name or '-'})  {s.source}  {s.id}")
                for c in captures_of(conn, s.id):
                    print(describe(c))
                return 0
            if args.command == "remove":
                s = find_species(conn, args.species)
                check_removable(s)
                caps = captures_of(conn, s.id)
                files = [f for c in caps for f in c.files]
                print(f"remove #{s.dex_number} {s.common_name} and {len(caps)} sighting(s):")
                for c in caps:
                    print(describe(c))
                if not args.apply:
                    print("dry run — add --apply to do it")
                    return 0
                where = backup(db, files)
                remove_species(conn, s)
            else:
                c = capture_by_id(conn, args.capture_id)
                files = c.files
                print(f"drop sighting:\n{describe(c)}")
                if not args.apply:
                    print("dry run — add --apply to do it")
                    return 0
                where = backup(db, files)
                if drop_capture(conn, c):
                    print("that was its last sighting, so the species is uncaught again")
        finally:
            conn.close()
        push_db(db)
        delete_files(files)
        print(f"done; the database as it was is in {where.relative_to(REPO)}")
        return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except DexDbError as e:
        print(f"dexdb: {e}", file=sys.stderr)
        sys.exit(1)
