from __future__ import annotations

import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import check_proof_selection as proof_selection


class ProofSelectionContractTest(unittest.TestCase):

    def test_double_star_matches_root_and_nested_paths(self) -> None:
        self.assertTrue(proof_selection.matches("README.md", "**/*.md"))
        self.assertTrue(proof_selection.matches("doc/README.md", "**/*.md"))
        self.assertFalse(proof_selection.matches("README.txt", "**/*.md"))

    def test_double_star_between_segments_can_be_empty(self) -> None:
        pattern = "xa-android/**/src/main/**"
        self.assertTrue(
            proof_selection.matches("xa-android/demo/src/main/App.java", pattern)
        )
        self.assertTrue(
            proof_selection.matches(
                "xa-android/worker/demo/src/main/App.java",
                pattern,
            )
        )

    def test_star_does_not_cross_directory_boundary(self) -> None:
        pattern = "server/api/model/TaskRpcCall*.java"
        self.assertTrue(
            proof_selection.matches(
                "server/api/model/TaskRpcCallRequest.java",
                pattern,
            )
        )
        self.assertFalse(
            proof_selection.matches(
                "server/api/model/nested/TaskRpcCallRequest.java",
                pattern,
            )
        )

    def test_markdown_exclusion_overrides_positive_rule(self) -> None:
        rules = ["transport/worker-core/**", "!**/*.md"]
        self.assertTrue(
            proof_selection.lane_matches(
                "transport/worker-core/src/main/Worker.java",
                rules,
            )
        )
        self.assertFalse(
            proof_selection.lane_matches(
                "transport/worker-core/README.md",
                rules,
            )
        )

    def test_filter_parser_rejects_unowned_yaml_shape(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            filters = Path(directory) / "filters.yml"
            filters.write_text(
                "lane:\n    nested: value\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ValueError, "unsupported filter syntax"):
                proof_selection.load_filters(filters)

    def test_current_repository_contract_is_closed(self) -> None:
        self.assertEqual([], proof_selection.validate())

    def test_repository_files_follow_uncommitted_moves(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            subprocess.run(["git", "init", "--quiet", str(root)], check=True)
            (root / ".gitignore").write_text("build/\n", encoding="utf-8")
            old = root / "binding.java"
            old.write_text("class Binding {}", encoding="utf-8")
            subprocess.run(["git", "add", "."], cwd=root, check=True)
            old.rename(root / "Endpoint 目录.java")
            (root / "build").mkdir()
            (root / "build/ignored.java").write_text("", encoding="utf-8")

            with patch.object(proof_selection, "ROOT", root):
                self.assertEqual(
                    {".gitignore", "Endpoint 目录.java"},
                    proof_selection.repository_files(),
                )


if __name__ == "__main__":
    unittest.main()
