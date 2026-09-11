from __future__ import annotations

import json
from collections import Counter
import tempfile
import unittest
from pathlib import Path

from integrations.worker_proof_support.scenario_inventory import (
    MAX_RECORDS_PER_GROUP,
    PHONE_GROUP,
    STRING_GROUP,
    canonical_100_worker_world,
    canonical_1000_worker_world,
    inventory_coordinates,
    materialize_inventory,
    product_worker_world,
)


ROOT = Path(__file__).resolve().parents[2]



class ScenarioInventoryTest(unittest.TestCase):

    def test_product_fixtures_keep_exact_quotas_original_order_and_coordinates(self):
        for counts in ((1, 1, 1), (4, 4, 4), (700, 200, 100)):
            with self.subTest(counts=counts), tempfile.TemporaryDirectory() as directory:
                world = product_worker_world(counts)
                records = world["demo-sim"]
                self.assertEqual(dict(zip(("CN", "US", "GB"), counts)), Counter(p["country"] for p in records))
                cycle = ["CN"] * 7 + ["US"] * 2 + ["GB"] if counts == (700, 200, 100) else ["CN", "US", "GB"]
                for index, properties in enumerate(records):
                    self.assertEqual({"runtime": "java", "phone": str(861700000001 + index),
                                      "country": cycle[index % len(cycle)], "operator": "Preview SIM",
                                      "simulated": "true", "messaging.enabled": "true"}, properties)
                root = Path(directory) / "data/scenario-workers"
                coordinates = materialize_inventory(root, world)["demo-sim"]
                self.assertEqual("workers-000.jsonl:1", coordinates[0])
                self.assertEqual(f"workers-{(sum(counts)-1)//100:03d}.jsonl:{(sum(counts)-1)%100+1}", coordinates[-1])
                before = {path.name: path.read_bytes() for path in (root / "demo-sim").glob("*.jsonl")}
                with self.assertRaisesRegex(ValueError, "already exist"):
                    materialize_inventory(root, world)
                self.assertEqual(before, {path.name: path.read_bytes() for path in (root / "demo-sim").glob("*.jsonl")})
        self.assertTrue(all("messaging.enabled" not in p for p in product_worker_world((1, 1, 1), messages=False)["demo-sim"]))
        for counts in ((1, 1), (0, 1, 1), (True, 1, 1), (1.5, 1, 1), (15000, 1, 1)):
            with self.assertRaises(ValueError):
                product_worker_world(counts)

    def test_materializes_fixed_chunks_and_location_properties(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "scenario-workers"
            groups = {"group-a": tuple({"index": str(i)} for i in range(205))}

            coordinates = materialize_inventory(root, groups)

            files = sorted((root / "group-a").glob("*.jsonl"))
            self.assertEqual(
                ["workers-000.jsonl", "workers-001.jsonl", "workers-002.jsonl"],
                [path.name for path in files],
            )
            self.assertEqual([100, 100, 5], [len(_lines(path)) for path in files])
            first = json.loads(_lines(files[0])[0])
            last = json.loads(_lines(files[2])[-1])
            self.assertEqual(
                "workers-000.jsonl",
                first["workerProperties"]["labInventoryKey"],
            )
            self.assertEqual("1", first["workerProperties"]["labInventoryLine"])
            self.assertEqual("204", last["workerProperties"]["index"])
            self.assertEqual("5", last["workerProperties"]["labInventoryLine"])
            self.assertEqual("workers-002.jsonl:5", coordinates["group-a"][-1])

    def test_supports_fixed_group_boundaries(self) -> None:
        for count in (1, 100, MAX_RECORDS_PER_GROUP):
            with self.subTest(count=count):
                groups = {"group-a": tuple({"index": str(i)} for i in range(count))}
                coordinates = inventory_coordinates(groups)
                self.assertEqual(count, len(coordinates["group-a"]))
        with self.assertRaisesRegex(ValueError, "1..15000"):
            inventory_coordinates({
                "group-a": tuple(
                    {"index": str(i)}
                    for i in range(MAX_RECORDS_PER_GROUP + 1)
                )
            })

    def test_materializes_fifteen_thousand_records_in_fixed_files(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "scenario-workers"
            records = tuple(
                {"workerIndex": str(index)}
                for index in range(1, MAX_RECORDS_PER_GROUP + 1)
            )

            coordinates = materialize_inventory(root, {"group-a": records})

            files = sorted((root / "group-a").glob("*.jsonl"))
            self.assertEqual(150, len(files))
            self.assertTrue(all(len(_lines(path)) == 100 for path in files))
            self.assertEqual("workers-149.jsonl:100", coordinates["group-a"][-1])

    def test_rejects_reserved_invalid_and_existing_inputs(self) -> None:
        with self.assertRaisesRegex(ValueError, "reserved properties"):
            inventory_coordinates({
                "group-a": ({"labInventoryLine": 1},),
            })
        with self.assertRaisesRegex(ValueError, "flat string Properties"):
            inventory_coordinates({"group-a": ({"value": float("nan")},)})
        with self.assertRaisesRegex(ValueError, "one path segment"):
            inventory_coordinates({"parent\\group-a": ({"value": "1"},)})
        with self.assertRaisesRegex(ValueError, "duplicate normalized"):
            inventory_coordinates({
                "group-a": ({"value": "1"},),
                " group-a ": ({"value": "2"},),
            })
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "scenario-workers"
            (root / "group-a").mkdir(parents=True)
            with self.assertRaisesRegex(ValueError, "already exist"):
                materialize_inventory(root, {"group-a": ({"value": "1"},)})

    def test_canonical_world_keeps_the_fixed_correctness_fixture(self) -> None:
        world = canonical_100_worker_world()
        self.assertEqual({PHONE_GROUP, STRING_GROUP}, set(world))
        self.assertEqual([50, 50], sorted(len(records) for records in world.values()))
        for group_id, records in world.items():
            self.assertEqual("1", records[0]["labSlot"])
            self.assertEqual("50", records[-1]["labSlot"])
            self.assertTrue(all(record["convergenceSlot"] == "A" for record in records))

    def test_canonical_convergence_world_spans_five_files_per_group(self) -> None:
        world = canonical_1000_worker_world()
        coordinates = inventory_coordinates(world)

        self.assertEqual({PHONE_GROUP, STRING_GROUP}, set(world))
        self.assertEqual(
            [500, 500],
            sorted(len(records) for records in world.values()),
        )
        for group_id in (PHONE_GROUP, STRING_GROUP):
            self.assertEqual("workers-000.jsonl:1", coordinates[group_id][0])
            self.assertEqual("workers-000.jsonl:100", coordinates[group_id][99])
            self.assertEqual("workers-001.jsonl:1", coordinates[group_id][100])
            self.assertEqual("workers-004.jsonl:100", coordinates[group_id][-1])


def _lines(path: Path) -> list[str]:
    return path.read_text(encoding="utf-8").splitlines()


if __name__ == "__main__":
    unittest.main()
