from __future__ import annotations

import copy
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

import prepare_release as release


class ReleasePromotionTest(unittest.TestCase):
    def setUp(self):
        self.commit = "a" * 40
        self.repository = "quiaingmuarua/xa_mass_platform"
        self.run = dict(head_sha=self.commit, event="workflow_dispatch", status="completed",
                        conclusion="success", path=".github/workflows/proof-ci.yml",
                        repository={"full_name": self.repository})
        self.jobs = [dict(name=name, status="completed", conclusion="success")
                     for name in sorted(release.REQUIRED_JOBS | {
                         "Worker Convergence Health (worker-fault)",
                         "Worker Convergence Health (task-fault)"})]

    def test_complete_manual_proof_is_accepted(self):
        release.validate_run(self.run, self.jobs, self.commit, self.repository)

    def test_reusable_workflow_job_name_is_accepted(self):
        jobs = copy.deepcopy(self.jobs)
        next(job for job in jobs if job["name"] == "Product Coexistence")["name"] += " / Shared products and recipient outcomes"
        release.validate_run(self.run, jobs, self.commit, self.repository)

    def test_wrong_sha_partial_failed_and_foreign_runs_are_rejected(self):
        changes = [dict(head_sha="b" * 40), dict(event="push"), dict(conclusion="failure"),
                   dict(status="in_progress"), dict(repository={"full_name": "other/repo"}),
                   dict(path=".github/workflows/other.yml")]
        for change in changes:
            with self.subTest(change=change), self.assertRaises(ValueError):
                release.validate_run(dict(self.run, **change), self.jobs, self.commit, self.repository)

    def test_missing_skipped_and_failed_jobs_are_rejected(self):
        for jobs in [self.jobs[:-1], [dict(job, conclusion="skipped") for job in self.jobs],
                     [dict(job, conclusion="failure") for job in self.jobs]]:
            with self.subTest(jobs=jobs), self.assertRaises(ValueError):
                release.validate_run(self.run, jobs, self.commit, self.repository)

    def test_artifacts_require_current_attempt_and_unexpired_pair(self):
        artifacts = [dict(name=f"xa-mass-{component}-2", expired=False) for component in release.COMPONENTS]
        self.assertEqual(2, len(release.validate_artifacts(artifacts, 2)))
        for invalid, attempt in [(artifacts[:1], 2),
                                 ([dict(artifact, expired=True) for artifact in artifacts], 2),
                                 (artifacts + [artifacts[0]], 2), (artifacts, 1)]:
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                release.validate_artifacts(invalid, attempt)

    def test_archive_version_sha_and_schema_are_verified(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "runtime.zip"
            for changes in [{}, {"version": "0.5.0"}, {"gitCommit": "b" * 40}, {"schemaVersion": 3}]:
                manifest = dict(version="1.0.0", gitCommit=self.commit, schemaVersion=6)
                manifest.update(changes)
                with zipfile.ZipFile(path, "w") as archive:
                    archive.writestr("xa-mass-server-runtime-1.0.0/manifest.json", json.dumps(manifest))
                if changes:
                    with self.assertRaises(ValueError):
                        release.archive_manifest(path, "server-runtime", "1.0.0", self.commit)
                else:
                    release.archive_manifest(path, "server-runtime", "1.0.0", self.commit)

    def test_checksum_uses_only_asset_basename(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "runtime.zip"
            archive.write_bytes(b"published bytes")
            sidecar = release.checksum(archive)
            self.assertEqual(release.digest(archive) + "  runtime.zip\n", sidecar.read_text())
            self.assertEqual(sidecar, release.checksum(archive))

    def test_draft_resume_accepts_only_identical_assets(self):
        with tempfile.TemporaryDirectory() as directory:
            expected = Path(directory) / "runtime.zip"
            downloaded = Path(directory) / "download.zip"
            expected.write_bytes(b"exact")
            downloaded.write_bytes(b"exact")
            release.validate_draft(dict(tag_name="v1.0.0", draft=True, assets=[{"name": expected.name}]), "v1.0.0", [expected])
            release.verify_existing_asset(expected, downloaded)
            downloaded.write_bytes(b"different")
            with self.assertRaises(ValueError):
                release.verify_existing_asset(expected, downloaded)
            for changes in [dict(draft=False), dict(tag_name="v0.5.0"), dict(assets=[{"name": "unknown"}])]:
                with self.assertRaises(ValueError):
                    release.validate_draft({**dict(tag_name="v1.0.0", draft=True, assets=[]), **changes}, "v1.0.0", [expected])


if __name__ == "__main__":
    unittest.main()
