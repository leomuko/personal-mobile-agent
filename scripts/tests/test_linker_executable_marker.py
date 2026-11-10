"""Synthetic host-only marker checks; no prospective target or native process."""

import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import stat
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "linker_marker_test", ROOT / "scripts/diagnostics/linker_executable_access_v1/marker.py")
marker = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(marker)


class LinkerExecutableMarkerTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        # macOS's tempfile ancestor may use /var -> /private/var. Only synthetic
        # fixture setup resolves it; the marker API must reject such aliases.
        self.root = Path(temporary.name).resolve()
        self.path = self.root / "private" / "nested" / "marker"
        self.payload = b"synthetic inert marker\x00\xff\n"

    def prepare(self):
        return marker.prepare(self.path, self.payload)

    def assert_rejected(self, operation):
        with self.assertRaises((ValueError, OSError)):
            operation()

    def test_round_trip_pins_complete_file_and_every_parent(self):
        receipt = self.prepare()
        self.assertEqual(self.path.read_bytes(), self.payload)
        info = self.path.lstat()
        expected = dict(path=str(self.path), sha256=hashlib.sha256(self.payload).hexdigest(),
                        size=len(self.payload), mode=0o555, uid=os.getuid(), gid=info.st_gid,
                        dev=info.st_dev, ino=info.st_ino, nlink=1,
                        mtimeNs=info.st_mtime_ns, ctimeNs=info.st_ctime_ns)
        self.assertEqual({k: receipt[k] for k in expected}, expected)
        parent_paths = list(reversed(self.path.parents))
        self.assertEqual([row["path"] for row in receipt["parents"]],
                         [str(path) for path in parent_paths])
        for row, path in zip(receipt["parents"], parent_paths):
            info = path.lstat()
            self.assertTrue(stat.S_ISDIR(info.st_mode))
            self.assertEqual(row, dict(path=str(path), mode=stat.S_IMODE(info.st_mode),
                                       uid=info.st_uid, gid=info.st_gid,
                                       dev=info.st_dev, ino=info.st_ino))
        for path in (self.path.parent, self.path.parent.parent):
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o700)
        self.assertEqual(marker.inspect(self.path, self.payload), receipt)
        self.assertIsNone(marker.verify(self.path, self.payload, json.loads(json.dumps(receipt))))

    def test_empty_and_maximum_payload_are_exact(self):
        for index, payload in enumerate((b"", bytes(range(256)) * 16)):
            with self.subTest(size=len(payload)):
                path = self.root / ("marker" + str(index))
                receipt = marker.prepare(path, payload)
                self.assertEqual(path.read_bytes(), payload)
                self.assertIsNone(marker.verify(path, payload, receipt))

    def test_invalid_payload_is_rejected_before_creation(self):
        for payload in (b"x" * 4097, "text", bytearray(b"data"), None):
            with self.subTest(payload_type=type(payload)):
                self.assert_rejected(lambda: marker.prepare(self.path, payload))
                self.assertFalse(self.path.parent.parent.exists())

    def test_prepare_refuses_overwrite_without_chmod(self):
        self.prepare()
        before = self.path.lstat()
        with patch.object(marker.os, "fchmod", side_effect=AssertionError("chmod existing")):
            with self.assertRaises(FileExistsError):
                self.prepare()
        self.assertEqual(self.path.read_bytes(), self.payload)
        self.assertEqual(self.path.lstat(), before)

    def test_no_access_predicate_or_path_chmod(self):
        with patch.object(marker.os, "access", side_effect=AssertionError("access predicate")), \
                patch.object(marker.os, "chmod", side_effect=AssertionError("path chmod")):
            receipt = self.prepare()
            marker.inspect(self.path, self.payload)
            marker.verify(self.path, self.payload, receipt)

    def test_opens_use_no_follow_and_exclusive_file_creation(self):
        with patch.object(marker.os, "open", wraps=os.open) as opened:
            self.prepare()
            marker.inspect(self.path, self.payload)
        file_calls = []
        for call in opened.call_args_list:
            flags = call.args[1]
            self.assertTrue(flags & os.O_NOFOLLOW)
            if call.args[0] == self.path.name:
                file_calls.append(call)
            else:
                self.assertTrue(flags & os.O_DIRECTORY)
        self.assertEqual(len(file_calls), 2)
        self.assertTrue(file_calls[0].args[1] & os.O_EXCL)
        self.assertTrue(file_calls[0].args[1] & os.O_CREAT)
        self.assertEqual(file_calls[0].args[2], 0o600)
        self.assertFalse(file_calls[1].args[1] & (os.O_CREAT | os.O_TRUNC))

    def test_alias_spellings_are_rejected(self):
        receipt = self.prepare()
        aliases = ("relative/marker", str(self.root) + "/private/./nested/marker",
                   str(self.root) + "/private/nested/../nested/marker",
                   str(self.path) + "/", str(self.root) + "//private/nested/marker",
                   "/" + str(self.path), str(self.path) + "\0")
        for path in aliases:
            for operation in (lambda: marker.prepare(path, self.payload),
                              lambda: marker.inspect(path, self.payload),
                              lambda: marker.verify(path, self.payload, receipt)):
                with self.subTest(path=path):
                    self.assert_rejected(operation)

    def test_symlink_marker_and_dangling_marker_are_preserved(self):
        target = self.root / "data"
        target.write_bytes(self.payload)
        for name, destination in (("link", target), ("dangling", self.root / "absent")):
            path = self.root / name
            path.symlink_to(destination)
            self.assert_rejected(lambda: marker.prepare(path, self.payload))
            self.assert_rejected(lambda: marker.inspect(path, self.payload))
            self.assertTrue(path.is_symlink())
        self.assertEqual(target.read_bytes(), self.payload)
        self.assertFalse((self.root / "absent").exists())

    def test_symlink_in_any_parent_is_rejected_without_creation(self):
        actual = self.root / "actual"
        actual.mkdir(mode=0o700)
        alias = self.root / "alias"
        alias.symlink_to(actual, target_is_directory=True)
        path = alias / "missing" / "marker"
        self.assert_rejected(lambda: marker.prepare(path, self.payload))
        self.assert_rejected(lambda: marker.inspect(path, self.payload))
        self.assertEqual(list(actual.iterdir()), [])

    def test_parent_symlink_after_prepare_rejected_even_for_same_directory(self):
        receipt = self.prepare()
        original = self.root / "original"
        self.path.parent.parent.rename(original)
        (self.root / "private").symlink_to(original, target_is_directory=True)
        self.assert_rejected(lambda: marker.verify(self.path, self.payload, receipt))

    def test_hardlinks_are_rejected_for_both_names(self):
        receipt = self.prepare()
        alias = self.path.parent / "other"
        os.link(self.path, alias)
        for path in (self.path, alias):
            self.assert_rejected(lambda: marker.inspect(path, self.payload))
            self.assert_rejected(lambda: marker.verify(path, self.payload, receipt))
            with self.assertRaises(FileExistsError):
                marker.prepare(path, self.payload)

    def test_unlinked_then_renamed_alias_cannot_reuse_receipt(self):
        receipt = self.prepare()
        alias = self.path.parent / "renamed"
        self.path.rename(alias)
        self.assert_rejected(lambda: marker.verify(alias, self.payload, receipt))

    def test_nonprivate_existing_root_is_not_chmodded_or_populated(self):
        root = self.root / "public"
        root.mkdir(mode=0o755)
        before = root.lstat()
        with patch.object(marker.os, "fchmod", side_effect=AssertionError("root chmod")):
            self.assert_rejected(lambda: marker.prepare(root / "new" / "marker", self.payload))
            self.assert_rejected(lambda: marker.prepare(root / "marker", self.payload))
        self.assertEqual(root.lstat(), before)
        self.assertEqual(list(root.iterdir()), [])

    def test_wrong_owner_is_mocked_without_chown(self):
        receipt = self.prepare()
        with patch.object(marker.os, "getuid", return_value=os.getuid() + 1):
            self.assert_rejected(lambda: marker.inspect(self.path, self.payload))
            self.assert_rejected(lambda: marker.verify(self.path, self.payload, receipt))
            self.assert_rejected(lambda: marker.prepare(self.root / "new" / "marker", self.payload))
        self.assertFalse((self.root / "new").exists())

    def test_wrong_marker_owner_rejected_even_with_correct_private_parent(self):
        self.prepare()
        real_fstat = os.fstat

        def wrong_file_owner(fd):
            info = real_fstat(fd)
            if stat.S_ISREG(info.st_mode):
                fields = ("st_mode", "st_uid", "st_gid", "st_dev", "st_ino", "st_size",
                          "st_nlink", "st_mtime_ns", "st_ctime_ns")
                fake = SimpleNamespace(**{name: getattr(info, name) for name in fields})
                fake.st_uid += 1
                return fake
            return info

        with patch.object(marker.os, "fstat", side_effect=wrong_file_owner):
            self.assert_rejected(lambda: marker.inspect(self.path, self.payload))
            with patch.object(marker.os, "fchmod", side_effect=AssertionError("chmod wrong owner")):
                self.assert_rejected(lambda: marker.prepare(self.root / "wrong-owner", self.payload))
        retained = self.root / "wrong-owner"
        self.assertTrue(retained.is_file())
        self.assertEqual(stat.S_IMODE(retained.stat().st_mode), 0o600)

    def test_file_mode_and_parent_mode_drift_are_rejected(self):
        receipt = self.prepare()
        os.chmod(self.path, 0o755)
        self.assert_rejected(lambda: marker.verify(self.path, self.payload, receipt))
        os.chmod(self.path, 0o555)
        os.chmod(self.path.parent.parent, 0o750)
        self.assert_rejected(lambda: marker.verify(self.path, self.payload, receipt))

    def test_supplied_payload_difference_and_byte_tamper_are_rejected(self):
        receipt = self.prepare()
        self.assert_rejected(lambda: marker.inspect(self.path, b"different"))
        os.chmod(self.path, 0o600)
        self.path.write_bytes(b"Z" * len(self.payload))
        os.chmod(self.path, 0o555)
        self.assert_rejected(lambda: marker.inspect(self.path, self.payload))
        self.assert_rejected(lambda: marker.verify(self.path, self.payload, receipt))

    def test_oversized_existing_file_rejected_before_read(self):
        self.prepare()
        os.chmod(self.path, 0o600)
        self.path.write_bytes(b"x" * 4097)
        os.chmod(self.path, 0o555)
        with patch.object(marker.os, "read", side_effect=AssertionError("oversized read")):
            self.assert_rejected(lambda: marker.inspect(self.path, self.payload))

    def test_directory_and_fifo_are_rejected_without_blocking(self):
        directory = self.root / "directory"
        directory.mkdir(mode=0o555)
        self.assert_rejected(lambda: marker.inspect(directory, self.payload))
        fifo = self.root / "fifo"
        os.mkfifo(fifo, 0o555)
        with patch.object(marker.os, "open", wraps=os.open) as opened:
            self.assert_rejected(lambda: marker.inspect(fifo, self.payload))
            self.assertFalse(any(call.args[0] == "fifo" for call in opened.call_args_list))

    def test_file_replacement_between_inspections_changes_receipt(self):
        receipt = self.prepare()
        self.path.rename(self.path.with_name("old"))
        marker.prepare(self.path, self.payload)
        self.assert_rejected(lambda: marker.verify(self.path, self.payload, receipt))

    def test_parent_replacement_preserving_marker_inode_changes_receipt(self):
        receipt = self.prepare()
        old = self.path.parent.with_name("old-parent")
        self.path.parent.rename(old)
        self.path.parent.mkdir(mode=0o700)
        (old / self.path.name).rename(self.path)
        self.assertEqual(self.path.stat().st_ino, receipt["ino"])
        self.assert_rejected(lambda: marker.verify(self.path, self.payload, receipt))

    def test_receipt_tampering_and_omitted_ancestor_are_rejected(self):
        receipt = self.prepare()
        changes = [dict(receipt, sha256="0" * 64), dict(receipt, unexpected=True)]
        for key in ("size", "mode", "uid", "gid", "dev", "ino", "nlink"):
            changes.append(dict(receipt, **{key: receipt[key] + 1}))
        for key in ("mode", "uid", "gid", "dev", "ino"):
            changed = copy.deepcopy(receipt)
            changed["parents"][0][key] += 1
            changes.append(changed)
        changes.append(dict(receipt, parents=receipt["parents"][1:]))
        for changed in changes:
            with self.subTest(receipt=changed):
                self.assert_rejected(lambda: marker.verify(self.path, self.payload, changed))

    def test_mocked_file_replacement_during_open_is_rejected(self):
        self.prepare()
        replacement = self.path.with_name("replacement")
        marker.prepare(replacement, self.payload)
        real_open = os.open

        def replace_before_open(path, flags, *args, **kwargs):
            if path == self.path.name:
                replacement.replace(self.path)
            return real_open(path, flags, *args, **kwargs)

        with patch.object(marker.os, "open", side_effect=replace_before_open):
            self.assert_rejected(lambda: marker.inspect(self.path, self.payload))

    def test_mocked_parent_replacement_during_read_is_rejected(self):
        self.prepare()
        real_read = os.read
        moved = self.path.parent.with_name("detached")

        def replace_during_read(fd, count):
            result = real_read(fd, count)
            if not moved.exists():
                self.path.parent.rename(moved)
                self.path.parent.mkdir(mode=0o700)
            return result

        with patch.object(marker.os, "read", side_effect=replace_during_read):
            self.assert_rejected(lambda: marker.inspect(self.path, self.payload))

    def test_same_size_byte_mutation_during_read_is_rejected(self):
        self.prepare()
        real_read = os.read
        changed = False

        def mutate_during_read(fd, count):
            nonlocal changed
            result = real_read(fd, count)
            if not changed:
                changed = True
                os.chmod(self.path, 0o600)
                self.path.write_bytes(b"Z" * len(self.payload))
                os.chmod(self.path, 0o555)
            return result

        with patch.object(marker.os, "read", side_effect=mutate_during_read):
            self.assert_rejected(lambda: marker.inspect(self.path, self.payload))

    def test_short_reads_are_completed_with_bounded_read_requests(self):
        receipt = self.prepare()
        real_read = os.read
        requests = []

        def short_read(fd, count):
            requests.append(count)
            return real_read(fd, min(2, count))

        with patch.object(marker.os, "read", side_effect=short_read):
            marker.verify(self.path, self.payload, receipt)
        self.assertGreater(len(requests), 1)
        self.assertLessEqual(max(requests), 4097)

    def test_chmod_failure_retains_new_file_without_deletion(self):
        with patch.object(marker.os, "fchmod", side_effect=OSError("synthetic chmod failure")):
            with self.assertRaisesRegex(OSError, "synthetic chmod failure"):
                self.prepare()
        self.assertEqual(self.path.read_bytes(), self.payload)
        self.assertEqual(stat.S_IMODE(self.path.stat().st_mode), 0o600)

    def test_partial_write_failure_retains_file_and_private_directories(self):
        real_write = os.write
        calls = 0

        def fail_after_partial_write(fd, data):
            nonlocal calls
            calls += 1
            if calls == 1:
                return real_write(fd, data[:3])
            raise OSError("synthetic write failure")

        with patch.object(marker.os, "write", side_effect=fail_after_partial_write):
            with self.assertRaisesRegex(OSError, "synthetic write failure"):
                self.prepare()
        self.assertEqual(self.path.read_bytes(), self.payload[:3])
        self.assertEqual(stat.S_IMODE(self.path.stat().st_mode), 0o600)
        self.assertEqual(stat.S_IMODE(self.path.parent.stat().st_mode), 0o700)
        with self.assertRaises(FileExistsError):
            self.prepare()

    def test_finalization_failure_retains_complete_file(self):
        with patch.object(marker.os, "fsync", side_effect=OSError("synthetic fsync failure")):
            with self.assertRaisesRegex(OSError, "synthetic fsync failure"):
                self.prepare()
        self.assertEqual(self.path.read_bytes(), self.payload)
        self.assertEqual(stat.S_IMODE(self.path.stat().st_mode), 0o555)

    def test_short_writes_are_completed(self):
        real_write = os.write
        with patch.object(marker.os, "write", side_effect=lambda fd, data: real_write(fd, data[:2])):
            receipt = self.prepare()
        marker.verify(self.path, self.payload, receipt)


if __name__ == "__main__":
    unittest.main()
