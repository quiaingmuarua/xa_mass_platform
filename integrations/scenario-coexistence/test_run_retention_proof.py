import hashlib
import json
import os
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

import run_retention_proof as proof


class HistoryEvidenceTest(unittest.TestCase):
    def test_readonly_evidence_closes_database_before_runtime_cleanup(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "history.sqlite"
            connection = sqlite3.connect(path)
            connection.execute("CREATE TABLE task_history_rows(history_id TEXT,message_id TEXT,result TEXT)")
            connection.executemany("INSERT INTO task_history_rows VALUES(?,?,?)", [
                ("history", "one", json.dumps({"code": "200", "opaqueResultPayload": "原始结果"})),
                ("history", "failed", json.dumps({"code": "failed", "opaqueResultPayload": "failed"})),
                ("history", "pending", None),
            ])
            connection.commit()
            connection.close()
            opened = []
            real_connect = sqlite3.connect

            def connect(*args, **kwargs):
                value = real_connect(*args, **kwargs)
                opened.append(value)
                return value

            with patch.object(proof.sqlite3, "connect", side_effect=connect):
                count, digest = proof.archived_successes(path, "history")
            expected = json.dumps({"messageId": "one", "opaqueResultPayload": "原始结果"}, ensure_ascii=False, separators=(",", ":")) + os.linesep
            self.assertEqual(1, count)
            self.assertEqual(hashlib.sha256(expected.encode()).hexdigest(), digest)
            for value in opened:
                with self.assertRaises(sqlite3.ProgrammingError):
                    value.execute("SELECT 1")
            path.unlink()  # Windows must be able to remove the database without waiting for GC.


if __name__ == "__main__":
    unittest.main()
