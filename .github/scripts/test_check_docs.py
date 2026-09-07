#!/usr/bin/env python3
"""Regression coverage for local Markdown file and chapter contracts."""

from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import check_docs


class MarkdownLinksTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        root_patch = patch.object(check_docs, "ROOT", self.root)
        root_patch.start()
        self.addCleanup(root_patch.stop)

    def write(self, name, content):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def check(self, content, targets=None):
        self.write("README.md", content)
        for name, text in (targets or {}).items():
            self.write(name, text)
        errors = []
        check_docs.validate_markdown_links(["README.md"], errors)
        return errors

    def test_same_and_cross_file_chapters(self):
        self.assertEqual([], self.check(
            "# Entry\n[self](#entry)\n[file](doc/owner.md)\n"
            "[chapter](doc/owner.md#score-primitives)",
            {"doc/owner.md": "# Owner\n## Score Primitives\n"},
        ))

    def test_relative_parent_and_title(self):
        self.write("doc/child.md", '[owner](../README.md#entry "Entry")')
        self.write("README.md", "# Entry")
        errors = []
        check_docs.validate_markdown_links(["doc/child.md"], errors)
        self.assertEqual([], errors)

    def test_missing_file_reports_source_line(self):
        errors = self.check("# Entry\n[missing](gone.md#owner)")
        self.assertEqual(1, len(errors))
        self.assertIn("README.md:2: missing link target 'gone.md#owner'", errors[0])

    def test_existing_file_missing_anchor(self):
        errors = self.check('[stale](owner.md#three-score-axes)', {"owner.md": "# Score Owners"})
        self.assertEqual(1, len(errors))
        self.assertIn("missing link anchor", errors[0])

    def test_missing_same_file_anchor(self):
        self.assertIn("missing link anchor", self.check("# Entry\n[bad](#removed)")[0])

    def test_duplicate_headings_and_colliding_suffix(self):
        text = "# Run\n# Run\n# Run-1\n# Run\n"
        self.assertEqual({"run", "run-1", "run-1-1", "run-2"}, check_docs.markdown_anchors(text))
        self.assertEqual([], self.check(text + "[again](#run-2)"))

    def test_chinese_and_percent_encoding(self):
        self.assertEqual([], self.check(
            "# 中文标题\n[本页](#%E4%B8%AD%E6%96%87%E6%A0%87%E9%A2%98)\n"
            "[文件](<文档/Owner Guide.md#启动流程>)",
            {"文档/Owner Guide.md": "## 启动流程"},
        ))

    def test_inline_format_and_punctuation(self):
        self.assertEqual({"score--hot-lease", "owner-contract"}, check_docs.markdown_anchors(
            "## **Score** & `HOT` Lease\n## [Owner](owner.md) Contract ###\n"
        ))

    def test_setext_and_explicit_id(self):
        self.assertEqual([], self.check(
            'Read Me\n=======\n<a id="custom"></a>\n[one](#read-me) [two](#custom)'
        ))

    def test_fenced_fake_headings_do_not_create_anchors(self):
        text = "# Real\n```md\n# Fake\n```\n~~~\n# Other\n~~~\n"
        self.assertEqual({"real"}, check_docs.markdown_anchors(text))
        self.assertIn("missing link anchor", self.check(text + "[bad](#fake)")[0])

    def test_fences_ignore_links_and_require_matching_closer(self):
        self.assertEqual([], self.check(
            "````md\n[bad](absent.md)\n```\n# Still Code\n~~~~\n````\n"
            "    # Indented code\n    [bad](absent.md)\n# Real\n[real](#real)"
        ))

    def test_code_title_does_not_reserve_duplicate_suffix(self):
        self.assertEqual({"run", "run-1"}, check_docs.markdown_anchors(
            "# Run\n```md\n# Run\n```\n# Run"
        ))

    def test_external_links_are_not_local_dependencies(self):
        self.assertEqual([], self.check(
            "[web](https://example.invalid/missing.md#none) "
            "[http](http://example.invalid) [mail](mailto:someone@example.invalid) "
            "[data](data:text/plain,test) [app](codex://review) "
            "[root](/runtime/path) [empty]()"
        ))

    def test_reference_destination_checks_chapter(self):
        self.assertIn("missing link anchor", self.check(
            '[owner][ref]\n[ref]: owner.md#missing "Owner"', {"owner.md": "# Owner"}
        )[0])

    def test_html_target_ids(self):
        self.assertEqual([], self.check('[page](overview.htm#authority)', {
            "overview.htm": '<section id="authority"></section>'
        }))
        self.assertIn("missing link anchor", self.check('[page](overview.htm#absent)')[0])


if __name__ == "__main__":
    unittest.main()
