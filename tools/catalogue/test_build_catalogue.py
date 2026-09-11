#!/usr/bin/env python3
"""Tests for the fungi half of the catalogue builder.

    cd tools/catalogue && python3 -m unittest test_build_catalogue -v

Standard library only, and no network: GBIF and Wikipedia are stubbed, because
what is under test is the rule the pipeline applies to whatever they return.

The rules worth a test here are the ones no dataset stands behind. Dr. Duke's
decides which *plant* carries a caution, and the plant path's checks are
exercised against the real thing by re-running the build. For fungi there is no
source at all — no medicinal join, no `Poison` record — so `uses: []` and the
caution are enforced by code, and code that enforces a safety rule should be
the kind you can watch fail.
"""

from __future__ import annotations

import struct
import unittest
import zlib
from collections import defaultdict

import build_catalogue as bc

ECOSYSTEMS = {"coastal-rainforest", "urban-suburban"}

ROW = {
    "dexNumber": 1,
    "commonName": "Test Mushroom",
    "scientificName": "Amanita testa",
    "fungusClass": "mushroom",
    "ecosystemIds": ["coastal-rainforest"],
    "usesNote": "Caution: poisonous, and confused with several pale mushrooms.",
}

WIKI = {
    "habitatText": "It grows in conifer duff from October to December.",
    "description": "Amanita testa is a fungus of the Pacific Northwest.",
    "imageUrl": "https://upload.wikimedia.org/x.jpg",
    "infoUrl": "https://en.wikipedia.org/wiki/Amanita_testa",
    "imageAttribution": "Wikimedia Commons · CC BY-SA 4.0 · someone",
}


class FungusBuilderTest(unittest.TestCase):

    def setUp(self):
        self.report = defaultdict(list)
        self._gbif, self._wiki = bc.gbif_match, bc.fetch_wikipedia
        bc.gbif_match = lambda name, refresh: {
            "species": name, "matchType": "EXACT", "confidence": 99,
            "rank": "SPECIES", "status": "ACCEPTED", "kingdom": "Fungi",
        }
        bc.fetch_wikipedia = lambda *a, **k: dict(WIKI)

    def tearDown(self):
        bc.gbif_match, bc.fetch_wikipedia = self._gbif, self._wiki

    def build(self, **overrides):
        return bc.build_fungus(
            {**ROW, **overrides}, ECOSYSTEMS, False, self.report, [],
        )

    def test_a_fungus_carries_no_use_and_no_dukes_field(self):
        record = self.build()
        self.assertEqual([], record["uses"])
        self.assertEqual([], record["medicinalActivities"])
        self.assertEqual(0, record["medicinalRecordCount"])
        self.assertIsNone(record["usesAttribution"])
        self.assertEqual("fungus", record["kingdom"])
        self.assertEqual("sil_mushroom", record["silhouetteRes"])

    def test_an_edible_flag_fails_the_build_rather_than_being_ignored(self):
        # Silently dropping the flag would let a curator believe the tag took.
        with self.assertRaises(SystemExit) as raised:
            self.build(edible=True)
        self.assertIn("fungi carry no uses", str(raised.exception))

    def test_an_override_cannot_smuggle_a_use_tag_in(self):
        # The generic override loop runs last and would otherwise win.
        for override in ({"uses": ["edible"]}, {"medicinal": True}, {"usesAttribution": "x"}):
            with self.assertRaises(SystemExit):
                self.build(overrides=override)

    def test_a_caution_may_not_use_an_edibility_word(self):
        with self.assertRaises(SystemExit) as raised:
            self.build(usesNote="Caution: not edible, and easily confused.")
        self.assertIn("edibility", str(raised.exception))

    def test_a_gbif_kingdom_that_is_not_fungi_fails(self):
        bc.gbif_match = lambda name, refresh: {
            "species": name, "matchType": "EXACT", "confidence": 99,
            "rank": "SPECIES", "status": "ACCEPTED", "kingdom": "Plantae",
        }
        with self.assertRaises(SystemExit) as raised:
            self.build()
        self.assertIn("declared a fungus", str(raised.exception))

    def test_a_fetched_description_loses_its_edibility_sentence(self):
        bc.fetch_wikipedia = lambda *a, **k: dict(
            WIKI,
            description=(
                "Amanita testa is a fungus of the Pacific Northwest. "
                "It is an edible mushroom sold in markets."
            ),
        )
        record = self.build()
        self.assertEqual(
            "Amanita testa is a fungus of the Pacific Northwest.", record["description"]
        )
        self.assertEqual(1, len(self.report["fungi_edibility_dropped"]))

    def test_inedible_survives_the_scrub(self):
        # `\b` before "edible" is what keeps a claim in the safe direction.
        dropped = []
        text = "The bracket is inedible because it is woody."
        self.assertEqual(text, bc.drop_edibility_sentences(text, dropped))
        self.assertEqual([], dropped)


class FungusValidationTest(unittest.TestCase):
    """`validate` is the check on the finished asset, which a person can hand-edit."""

    def asset(self, **overrides):
        species = {
            "id": "test-mushroom", "dexNumber": 1, "commonName": "Test Mushroom",
            "scientificName": "Amanita testa", "taxClass": "mushroom", "kingdom": "fungus",
            "ecosystemIds": ["coastal-rainforest"], "habitatText": "In duff.",
            "description": "A fungus.", "imageUrl": "u", "infoUrl": "u",
            "imageAttribution": "a", "silhouetteRes": "sil_mushroom", "uses": [],
            "usesNote": "Caution: poisonous.", "medicinalActivities": [],
            "medicinalRecordCount": 0, "usesAttribution": None, "provenance": {},
        }
        species.update(overrides)
        catalogue = {"species": [species], "ecosystems": []}
        internals = {"test-mushroom": {"curatedNote": species["usesNote"]}}
        # The counts now come from the curated inputs; this fixture is one fungus.
        counts = {"animal": 0, "plant": 0, "fungus": 1}
        return bc.validate(catalogue, ECOSYSTEMS, internals, counts)

    def problems_about(self, fragment, **overrides):
        return [p for p in self.asset(**overrides) if fragment in p]

    def test_a_fungus_may_carry_no_note_at_all(self):
        # A fungus used to be *required* to carry a caution, harmless ones included, which
        # put a warning on the turkey tail and the puffball. A note is written now only when
        # the species itself is dangerous, so no note is an ordinary outcome — as it is for
        # an animal. What a note may not be is anything other than a caution.
        self.assertEqual([], self.problems_about("Caution", usesNote=None))

    def test_a_note_with_prose_outside_its_caution_is_a_problem(self):
        self.assertTrue(
            self.problems_about(
                "must be a 'Caution:' sentence only",
                usesNote="Good in soups. Caution: poisonous.",
            )
        )

    def test_a_hand_edited_use_tag_is_a_problem(self):
        self.assertTrue(self.problems_about("carries a use tag", uses=["edible"]))

    def test_an_edibility_claim_anywhere_is_a_problem(self):
        self.assertTrue(
            self.problems_about("claims something about edibility", description="An edible fungus.")
        )

    def test_a_silhouette_that_does_not_match_the_growth_form_is_a_problem(self):
        self.assertTrue(
            self.problems_about("silhouetteRes does not match", silhouetteRes="sil_bracket")
        )

    def test_a_well_formed_fungus_raises_nothing_of_its_own(self):
        # The count and dex-range checks still fire on a one-species catalogue; what must
        # not appear is any problem naming this species.
        self.assertEqual([], [p for p in self.asset() if p.startswith("test-mushroom:")])


class RangeMapTest(unittest.TestCase):
    """D34's two pieces that would fail silently: the decoder and the threshold."""

    def png(self, width, height, pixels, filter_type=0):
        """A minimal 8-bit RGBA PNG, one filter type for every row."""
        raw = bytearray()
        for y in range(height):
            raw.append(filter_type)
            for x in range(width):
                raw.extend(pixels[y * width + x])

        def chunk(tag, body):
            return (
                struct.pack(">I", len(body)) + tag + body
                + struct.pack(">I", zlib.crc32(tag + body) & 0xFFFFFFFF)
            )

        return (
            b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(bytes(raw)))
            + chunk(b"IEND", b"")
        )

    def test_the_decoder_reads_an_unfiltered_image(self):
        pixels = [(10, 20, 30, 255), (40, 50, 60, 0), (70, 80, 90, 128), (1, 2, 3, 4)]
        w, h, out = bc.decode_png(self.png(2, 2, pixels))
        self.assertEqual((2, 2), (w, h))
        self.assertEqual(bytes([10, 20, 30, 255, 40, 50, 60, 0]), out[:8])
        self.assertEqual(128, out[11])

    def test_the_decoder_undoes_the_up_and_sub_filters(self):
        """A decoder that ignored the filter byte would return the deltas."""
        def encoded(filter_type, rows):
            raw = bytearray()
            for row in rows:
                raw.append(filter_type)
                raw.extend(row)

            def chunk(tag, body):
                return (
                    struct.pack(">I", len(body)) + tag + body
                    + struct.pack(">I", zlib.crc32(tag + body) & 0xFFFFFFFF)
                )

            return (
                b"\x89PNG\r\n\x1a\n"
                + chunk(b"IHDR", struct.pack(">IIBBBBB", 2, 2, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(bytes(raw)))
                + chunk(b"IEND", b"")
            )

        solid = bytes([9, 9, 9, 255] * 2)
        # Up: the second row is stored as all-zero deltas from the first.
        _, _, out = bc.decode_png(encoded(2, [solid, bytes(8)]))
        self.assertEqual(solid, out[8:])
        # Sub: each pixel is stored as a delta from the pixel to its left.
        first = bytes([9, 9, 9, 255]) + bytes(4)
        _, _, out = bc.decode_png(encoded(1, [first, first]))
        self.assertEqual(solid, out[:8])

    def test_the_decoder_refuses_a_format_it_cannot_read(self):
        # Palette PNGs decode to nonsense through an RGBA reader, and nonsense
        # here shades the wrong part of the world while looking plausible.
        png = bytearray(self.png(1, 1, [(0, 0, 0, 255)]))
        png[25] = 3  # colour type 3, palette
        with self.assertRaises(ValueError):
            bc.decode_png(bytes(png))

    def test_a_stray_record_is_dropped_and_a_real_cluster_is_kept(self):
        # The shape measured on the California thrasher: a handful of one-pixel
        # cells scattered across the world, and a few saturated ones where it
        # lives. Only the saturated ones are range.
        coverage = {100: 1, 200: 1, 300: 2, 400: 40, 401: 55, 402: 30}
        best = max(coverage.values())
        floor = max(bc.RANGE_MIN_PIXELS, bc.RANGE_KEEP_FRACTION * best)
        kept = sorted(c for c, n in coverage.items() if n >= floor or n >= bc.RANGE_KEEP_ABSOLUTE)
        self.assertEqual([400, 401, 402], kept)

    def test_the_grid_divides_the_world_raster_exactly(self):
        # A cell index means a patch of the world, and the same patch on every
        # species' map. That only holds while the arithmetic divides.
        self.assertEqual(0, (bc.RANGE_TILE_PX * 2) % bc.RANGE_GRID_W)
        self.assertEqual(0, bc.RANGE_TILE_PX % bc.RANGE_GRID_H)
        self.assertEqual(bc.RANGE_CELL_PX, bc.RANGE_TILE_PX * 2 // bc.RANGE_GRID_W)
        self.assertEqual(bc.RANGE_CELL_PX, bc.RANGE_TILE_PX // bc.RANGE_GRID_H)


if __name__ == "__main__":
    unittest.main()
