from __future__ import annotations

import inspect
import json
import unittest
from dataclasses import fields, is_dataclass
from enum import EnumMeta
from pathlib import Path

from kernel_design.executable_spec import kernel


_MANIFEST_PATH = (
    Path(__file__).parents[1] / "kernel_owner_contract_manifest.json"
)


class KernelOwnerContractManifestTest(unittest.TestCase):
    def test_public_kernel_contracts_match_shared_manifest(self) -> None:
        manifest = json.loads(_MANIFEST_PATH.read_text(encoding="utf-8"))
        public_values = {
            name: getattr(kernel, name)
            for name in kernel.__all__
        }

        self.assertEqual(
            sorted(manifest["contracts"]),
            sorted(
                name
                for name, value in public_values.items()
                if inspect.isclass(value) and inspect.isabstract(value)
            ),
        )
        self.assertEqual(
            sorted(manifest["dtos"]),
            sorted(
                name
                for name, value in public_values.items()
                if is_dataclass(value)
            ),
        )
        self.assertEqual(
            sorted(manifest["enums"]),
            sorted(
                name
                for name, value in public_values.items()
                if isinstance(value, EnumMeta)
            ),
        )

        actual_contracts = {
            name: sorted(
                method_name
                for method_name, method in getattr(kernel, name).__dict__.items()
                if inspect.isfunction(method)
                and not method_name.startswith("_")
            )
            for name in manifest["contracts"]
        }
        self.assertEqual(manifest["contracts"], actual_contracts)

        actual_dtos = {}
        for name in manifest["dtos"]:
            dto = getattr(kernel, name)
            self.assertTrue(is_dataclass(dto), name)
            actual_dtos[name] = [field.name for field in fields(dto)]
        self.assertEqual(manifest["dtos"], actual_dtos)

        actual_enums = {}
        for name in manifest["enums"]:
            enum_type = getattr(kernel, name)
            self.assertIsInstance(enum_type, EnumMeta)
            actual_enums[name] = [member.value for member in enum_type]
        self.assertEqual(manifest["enums"], actual_enums)

        actual_constants = {}
        for contract_name, expected in manifest["constants"].items():
            contract = getattr(kernel, contract_name)
            self.assertEqual(
                sorted(expected),
                sorted(
                    name
                    for name, value in contract.__dict__.items()
                    if name.isupper()
                    and isinstance(value, (int, float))
                    and not isinstance(value, bool)
                ),
            )
            actual_constants[contract_name] = {
                name: getattr(contract, name)
                for name in expected
            }
        self.assertEqual(manifest["constants"], actual_constants)


if __name__ == "__main__":
    unittest.main()
