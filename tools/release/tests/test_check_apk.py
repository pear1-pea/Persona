import hashlib
import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
import warnings
import zipfile

SPEC = importlib.util.spec_from_file_location("release_check", Path(__file__).parents[1] / "check_apk.py")
checker = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(checker)
REVISION = "a" * 40
JNI = "lib/arm64-v8a/libpersona_mnn.so"


def elf(alignment=16384, machine=183, offset=0, address=0):
    ident = b"\x7fELF\x02\x01\x01" + b"\0" * 9
    header = struct.pack("<HHIQQQIHHHHHH", 3, machine, 1, 0, 64, 0, 0, 64, 56, 1, 0, 0, 0)
    segment = struct.pack("<IIQQQQQQ", 1, 5, offset, address, 0, 120, 120, alignment)
    return ident + header + segment


class ReleaseCheckTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.apk = Path(self.temp.name) / "candidate.apk"

    def package(self, rows, compression=zipfile.ZIP_STORED):
        with zipfile.ZipFile(self.apk, "w", compression=compression) as archive:
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                for name, data in rows:
                    archive.writestr(name, data)
        return self.apk

    def test_checks_every_library_and_records_hashes_without_claiming_distribution(self):
        self.package([(JNI, elf()), ("lib/arm64-v8a/libc++_shared.so", elf(65536))])
        report = checker.inspect_apk(self.apk, REVISION)
        self.assertEqual(hashlib.sha256(self.apk.read_bytes()).hexdigest(), report["apk_sha256"])
        self.assertEqual(2, len(report["native_libraries"]))
        self.assertFalse(report["ready_for_distribution"])
        self.assertEqual("not_verified", report["signing"])

    def test_rejects_4kb_alignment_in_any_library(self):
        self.package([(JNI, elf()), ("lib/arm64-v8a/libdependency.so", elf(4096))])
        with self.assertRaises(ValueError):
            checker.inspect_apk(self.apk, REVISION)

    def test_rejects_wrong_architecture_and_elf_class(self):
        for data in [elf(machine=62), b"\x7fELF\x01" + elf()[5:], b"not an ELF file"]:
            with self.subTest(data=data[:7]), self.assertRaises(ValueError):
                checker.inspect_elf(data)

    def test_rejects_truncated_or_out_of_bounds_segment_tables(self):
        for data in [elf()[:100], elf()[:32] + struct.pack("<Q", 1000) + elf()[40:]]:
            with self.assertRaises(ValueError):
                checker.inspect_elf(data)

    def test_rejects_incongruent_or_non_power_of_two_alignment(self):
        for data in [elf(address=1), elf(alignment=24576)]:
            with self.assertRaises(ValueError):
                checker.inspect_elf(data)

    def test_rejects_segment_file_range_beyond_elf(self):
        with self.assertRaises(ValueError):
            checker.inspect_elf(elf(offset=16384))

    def test_rejects_duplicates_and_other_abis(self):
        for rows in [[(JNI, elf()), (JNI, elf())], [(JNI, elf()), ("lib/x86_64/libextra.so", elf())]]:
            self.package(rows)
            with self.assertRaises(ValueError):
                checker.inspect_apk(self.apk, REVISION)

    def test_requires_the_actual_jni_library(self):
        for rows in [[], [("lib/arm64-v8a/libother.so", elf())]]:
            self.package(rows)
            with self.assertRaises(ValueError):
                checker.inspect_apk(self.apk, REVISION)

    def test_compression_does_not_replace_elf_or_zip_alignment_checks(self):
        self.package([(JNI, elf())], zipfile.ZIP_DEFLATED)
        report = checker.inspect_apk(self.apk, REVISION)
        self.assertTrue(report["native_libraries"][0]["zip_compressed"])
        self.assertEqual("requires_separate_zipalign_check", report["zip_alignment"])

    def test_requires_a_bound_source_revision(self):
        self.package([(JNI, elf())])
        with self.assertRaises(ValueError):
            checker.inspect_apk(self.apk, "unknown")


if __name__ == "__main__":
    unittest.main()
