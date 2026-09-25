"""Tests for the pure selection rules in select_corpus.py. Standard library only."""

import unittest

from select_corpus import (
    best_taxon,
    choose_lookalikes,
    drop_shared_photos,
    manifest_row,
    partition_other,
    pick,
    shows_the_animal,
    split_for,
)


def obs(i, user="a", licence="cc-by", evidence=None):
    return {
        "id": i,
        "user": {"login": user},
        "taxon": {"id": 99},
        "location": "37.5,-122.1",
        "observed_on": "2026-05-01",
        "photos": [{"id": 1000 + i, "license_code": licence, "url": f"https://x/photos/{1000 + i}/square.jpg"}],
        "annotations": [] if evidence is None else [{"controlled_attribute_id": 22, "controlled_value_id": evidence}],
    }


class PickTest(unittest.TestCase):
    def test_signs_of_the_animal_are_dropped_and_the_animal_kept(self):
        # 24 Organism; 25 Scat; 26 Track; 23 Feather
        self.assertTrue(shows_the_animal(obs(1, evidence=24)))
        self.assertTrue(shows_the_animal(obs(1)))
        for sign in (23, 25, 26):
            self.assertFalse(shows_the_animal(obs(1, evidence=sign)))

    def test_caps_per_observer_and_overall_and_skips_closed_licences(self):
        observations = [obs(i, user="prolific") for i in range(20)] + [obs(100, licence="cc-by-nc")] + [obs(200 + i, user=f"u{i}") for i in range(5)]
        picked = pick(observations, limit=12, per_observer=10)
        self.assertEqual([o["id"] for o in picked], list(range(10)) + [200, 201])

    def test_an_observation_is_never_picked_twice(self):
        self.assertEqual(len(pick([obs(1), obs(1)], limit=10, per_observer=10)), 1)


class SplitTest(unittest.TestCase):
    def test_split_is_stable_and_roughly_proportional(self):
        splits = [split_for(i) for i in range(10_000)]
        self.assertEqual(splits, [split_for(i) for i in range(10_000)])
        self.assertAlmostEqual(splits.count("train") / 10_000, 0.8, delta=0.02)
        self.assertAlmostEqual(splits.count("test") / 10_000, 0.1, delta=0.02)


class ManifestTest(unittest.TestCase):
    def test_row_points_at_the_medium_image_and_carries_attribution(self):
        row = manifest_row(obs(7, user="someone"), "canada-goose", "train")
        self.assertEqual(row["url"], "https://x/photos/1007/medium.jpg")
        self.assertEqual((row["observer"], row["license"], row["lat"]), ("someone", "cc-by", 37.5))


class SharedPhotoTest(unittest.TestCase):
    def test_a_photo_on_two_observations_is_dropped_from_both(self):
        rows = [{"photo_id": 1, "label": "a"}, {"photo_id": 1, "label": "b"}, {"photo_id": 2, "label": "a"}]
        self.assertEqual(drop_shared_photos(rows), [{"photo_id": 2, "label": "a"}])


class OtherClassTest(unittest.TestCase):
    def test_lookalikes_exclude_dex_species_and_take_the_most_observed(self):
        def row(tid, count):
            return {"taxon": {"id": tid, "name": f"s{tid}"}, "count": count}
        chosen = choose_lookalikes({5: [row(1, 900), row(2, 50), row(3, 400), row(4, 10)]}, dex_taxa={1}, per_family=2)
        self.assertEqual([c["taxon_id"] for c in chosen], [3, 2])

    def test_open_set_species_are_disjoint_from_training_species(self):
        species = [{"taxon_id": i} for i in range(50)]
        train, test = partition_other(species, 0.2)
        self.assertEqual(len(test), 10)
        self.assertFalse({s["taxon_id"] for s in train} & {s["taxon_id"] for s in test})


class ResolveTest(unittest.TestCase):
    def taxon(self, tid, name, rank, matched=None, count=0, kingdom=1):
        return {"id": tid, "name": name, "rank": rank, "matched_term": matched or name, "is_active": True,
                "observations_count": count, "ancestor_ids": [48460, kingdom]}

    def test_a_renamed_species_resolves_through_its_synonym_to_the_species_not_a_subspecies(self):
        results = [self.taxon(1, "Antigone canadensis", "species", "Grus canadensis", 85000),
                   self.taxon(2, "Antigone canadensis tabida", "subspecies", "Grus canadensis tabida", 800)]
        self.assertEqual(best_taxon(results, "Grus canadensis", "animal")["id"], 1)

    def test_the_species_wins_over_a_complex_of_the_same_name(self):
        results = [self.taxon(1, "Trametes versicolor", "complex", count=230000, kingdom=47170),
                   self.taxon(2, "Trametes versicolor", "species", count=198000, kingdom=47170)]
        self.assertEqual(best_taxon(results, "Trametes versicolor", "fungus")["id"], 2)

    def test_a_namesake_in_the_other_kingdom_is_ignored_and_a_genus_name_finds_the_genus(self):
        results = [self.taxon(1, "Ammopelmatus", "genus", kingdom=47170), self.taxon(2, "Ammopelmatus", "genus", count=5)]
        self.assertEqual(best_taxon(results, "Ammopelmatus", "animal")["id"], 2)
        self.assertIsNone(best_taxon(results, "Ammopelmatus fuscus", "animal"))


if __name__ == "__main__":
    unittest.main()
