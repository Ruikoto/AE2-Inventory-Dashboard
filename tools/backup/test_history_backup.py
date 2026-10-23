"""Linux 上运行：python3 -m unittest discover -s tools/backup -p 'test_*.py'。"""

from contextlib import closing
import fcntl
import os
from pathlib import Path
import sqlite3
import stat
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

import history_backup


class HistoryBackupTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.world = Path(self.directory.name)
        self.source = self.world / "ae2-dashboard/history.sqlite"
        self.source.parent.mkdir()
        self.live = sqlite3.connect(self.source)
        self.addCleanup(self.live.close)
        self.live.execute("PRAGMA journal_mode=WAL")
        self.live.execute("PRAGMA wal_autocheckpoint=0")
        self.live.execute("CREATE TABLE samples(id INTEGER PRIMARY KEY, quantity TEXT)")
        self.live.execute("INSERT INTO samples VALUES(1,'9007199254740993')")
        self.live.commit()
        self.completed = self.world / "ae2-dashboard-backup/history.sqlite"

    def run_script(self, *arguments):
        return subprocess.run(
            [sys.executable, str(Path(history_backup.__file__)), "--world", str(self.world), *arguments],
            capture_output=True, text=True, timeout=5,
        )

    def assert_previous_preserved(self, previous):
        self.assertEqual(previous, self.completed.read_bytes())
        self.assertEqual([], list((self.world / ".ae2-dashboard-backup-staging").glob("history-*")))

    def test_wal_is_backed_up_and_published_as_independent_database(self):
        self.assertTrue(Path(str(self.source) + "-wal").is_file())
        result = self.run_script()
        self.assertEqual(0, result.returncode, result.stderr)
        with closing(sqlite3.connect(self.completed)) as restored:
            self.assertEqual(("ok",), restored.execute("PRAGMA quick_check").fetchone())
            self.assertEqual(("9007199254740993",), restored.execute("SELECT quantity FROM samples").fetchone())
            self.assertEqual(("delete",), restored.execute("PRAGMA journal_mode").fetchone())
        self.assertEqual(["history.sqlite"], [p.name for p in self.completed.parent.iterdir()])
        self.live.execute("INSERT INTO samples VALUES(2,'42')")
        self.live.commit()
        self.assertEqual(0, self.run_script().returncode)
        with closing(sqlite3.connect(self.completed)) as restored:
            self.assertEqual((2,), restored.execute("SELECT count(*) FROM samples").fetchone())

    def test_lock_rejects_overlap_and_preserves_previous(self):
        history_backup.backup(self.world)
        previous = self.completed.read_bytes()
        with (self.world / ".ae2-dashboard-backup-staging/backup.lock").open("a") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            result = self.run_script()
            self.assertEqual(1, result.returncode)
            self.assertIn("已有备份正在运行", result.stderr)
        self.assert_previous_preserved(previous)

    def test_insufficient_disk_preserves_previous(self):
        history_backup.backup(self.world)
        previous = self.completed.read_bytes()
        with patch.object(history_backup.shutil, "disk_usage", return_value=type("Disk", (), {"free": 0})()):
            with self.assertRaisesRegex(OSError, "空间不足"):
                history_backup.backup(self.world)
        self.assert_previous_preserved(previous)

    def test_publish_failure_cleans_only_current_temporary_file(self):
        history_backup.backup(self.world)
        previous = self.completed.read_bytes()
        unrelated = self.world / ".ae2-dashboard-backup-staging/previous-interruption.sqlite"
        unrelated.write_bytes(b"preserve evidence")
        with patch.object(history_backup.os, "replace", side_effect=OSError("publish denied")):
            with self.assertRaisesRegex(OSError, "publish denied"):
                history_backup.backup(self.world)
        self.assert_previous_preserved(previous)
        self.assertEqual(b"preserve evidence", unrelated.read_bytes())

    def test_locked_source_times_out_without_replacing_previous(self):
        history_backup.backup(self.world)
        previous = self.completed.read_bytes()
        self.live.execute("PRAGMA journal_mode=DELETE")
        self.live.execute("BEGIN EXCLUSIVE")
        started = time.monotonic()
        try:
            result = self.run_script("--timeout", "0.1")
        finally:
            self.live.rollback()
        self.assertEqual(1, result.returncode)
        self.assertIn("超过 0.1 秒", result.stderr)
        self.assertLess(time.monotonic() - started, 3)
        self.assert_previous_preserved(previous)

    def test_directory_sync_failure_reports_completed_publication(self):
        history_backup.backup(self.world)
        self.live.execute("INSERT INTO samples VALUES(2,'42')")
        self.live.commit()
        fsync = os.fsync

        def fail_directory_sync(descriptor):
            if stat.S_ISDIR(os.fstat(descriptor).st_mode):
                raise OSError("directory I/O error")
            fsync(descriptor)

        with patch.object(history_backup.os, "fsync", side_effect=fail_directory_sync):
            with self.assertRaisesRegex(OSError, "新副本已完整发布"):
                history_backup.backup(self.world)
        with closing(sqlite3.connect(self.completed)) as restored:
            self.assertEqual(("ok",), restored.execute("PRAGMA quick_check").fetchone())
            self.assertEqual((2,), restored.execute("SELECT count(*) FROM samples").fetchone())

    def test_missing_source_is_not_created(self):
        with tempfile.TemporaryDirectory() as empty:
            with self.assertRaises(FileNotFoundError):
                history_backup.backup(Path(empty))
            self.assertEqual([], list(Path(empty).iterdir()))


if __name__ == "__main__":
    unittest.main()
