"""Tests for the place-list build (DESIGN.md D68).

The filter is the whole tool, and it is what a curator will change — so it is
pinned here, together with the shipped asset itself, the way
`test_build_catalogue.py` pins the catalogue.
"""

import json
import unittest
from pathlib import Path

import build_places

HERE = Path(__file__).resolve().parent
ASSET = HERE / ".." / ".." / "app" / "src" / "main" / "assets" / "places" / "pacific.tsv"


def row(name, state, feature_class, feature_code):
    """One line of the GeoNames dump, with only the four columns that matter filled."""
    fields = [""] * 19
    fields[build_places.COL_NAME] = name
    fields[build_places.COL_FEATURE_CLASS] = feature_class
    fields[build_places.COL_FEATURE_CODE] = feature_code
    fields[build_places.COL_ADMIN1] = state
    return "\t".join(fields) + "\n"


class SelectPlacesTest(unittest.TestCase):

    def setUp(self):
        self.states, self.tiers = build_places.load_config(HERE / "places.json")

    def select(self, lines):
        return build_places.select_places(lines, self.states, self.tiers)

    def test_keeps_a_town_and_tags_it_tier_zero(self):
        self.assertEqual(
            [("Point Reyes Station", "CA", 0)],
            self.select([row("Point Reyes Station", "CA", "P", "PPL")]),
        )

    def test_drops_a_state_outside_the_region(self):
        self.assertEqual([], self.select([row("Boise", "ID", "P", "PPL")]))

    def test_drops_a_feature_code_nobody_names_a_sighting_after(self):
        # H.STM — streams are 28,000 of the three states' rows on their own.
        self.assertEqual([], self.select([row("Deer Creek", "CA", "H", "STM")]))

    def test_one_name_in_one_state_is_one_suggestion(self):
        places = self.select([
            row("City Park", "CA", "L", "PRK"),
            row("City Park", "CA", "L", "PRK"),
            row("City Park", "OR", "L", "PRK"),
        ])
        self.assertEqual([("City Park", "CA", 1), ("City Park", "OR", 1)], places)

    def test_the_better_tier_wins_a_shared_name(self):
        places = self.select([
            row("Bear Valley", "CA", "T", "MT"),
            row("Bear Valley", "CA", "P", "PPL"),
        ])
        self.assertEqual([("Bear Valley", "CA", 0)], places)

    def test_drops_a_name_with_no_letters_in_it(self):
        self.assertEqual([], self.select([row("4", "CA", "L", "PRK")]))

    def test_output_is_sorted(self):
        places = self.select([
            row("Zamora", "CA", "P", "PPL"),
            row("Alturas", "CA", "P", "PPL"),
        ])
        self.assertEqual(["Alturas", "Zamora"], [name for name, _, _ in places])


class ShippedAssetTest(unittest.TestCase):
    """What the app actually reads. A malformed line here is a crash on the phone."""

    @classmethod
    def setUpClass(cls):
        cls.lines = ASSET.read_text(encoding="utf-8").splitlines()

    def test_every_line_is_a_name_a_state_and_a_tier(self):
        states = set(json.loads((HERE / "places.json").read_text(encoding="utf-8"))["states"])
        for line in self.lines:
            name, state, tier = line.split("\t")
            self.assertTrue(name.strip())
            self.assertIn(state, states)
            self.assertIn(tier, ("0", "1", "2"))

    def test_no_name_carries_a_tab_or_an_empty_field(self):
        self.assertTrue(all(line.count("\t") == 2 for line in self.lines))

    def test_the_places_the_prompt_offers_as_examples_are_in_it(self):
        # The dialog's placeholder reads "e.g. Bear Valley, Point Reyes" — a
        # placeholder naming somewhere the list does not hold would be a lie.
        names = {line.split("\t")[0] for line in self.lines}
        self.assertIn("Bear Valley", names)
        self.assertIn("Point Reyes Station", names)

    def test_it_is_big_enough_to_be_the_whole_region(self):
        self.assertGreater(len(self.lines), 40_000)


if __name__ == "__main__":
    unittest.main()
