from __future__ import annotations

import unittest

from kernel_design.executable_spec import RedisKeyspace


class RedisKeyspaceTest(unittest.TestCase):
    def test_renders_fixed_root_and_valid_scope(self) -> None:
        self.assertEqual(
            "xa_mass:test_runtime_boundary_20260822_ab12cd34",
            RedisKeyspace(
                "test_runtime_boundary_20260822_ab12cd34"
            ).base,
        )

    def test_rejects_arbitrary_or_unsafe_scope(self) -> None:
        for scope in (
            "default",
            "profile_",
            "test_",
            "Profile_default",
            "profile_scenario-workers",
            "profile:default",
            "test_runtime_*",
            "test_{runtime}",
            " test_runtime",
        ):
            with self.subTest(scope=scope), self.assertRaises(ValueError):
                RedisKeyspace(scope)


if __name__ == "__main__":
    unittest.main()
