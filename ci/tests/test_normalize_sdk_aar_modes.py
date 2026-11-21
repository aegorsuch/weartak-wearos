import hashlib
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[2]
JAVA = shutil.which("java")
NORMALIZER = ROOT / "ci" / "NormalizeSdkAarModes.java"


def fixture(path, content=b"original"):
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, value, mode, dos in (
            ("aidl/", b"", 0o40755, 0x10),
            ("aidl/test.aidl", content, 0o100644, 0),
            ("classes.jar", b"class fixture", 0o100644, 0),
        ):
            entry = zipfile.ZipInfo(name)
            entry.create_system = 3
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = (mode << 16) | dos
            archive.writestr(entry, value)


def central_entries(data):
    eocd = len(data) - 22
    count = struct.unpack_from("<H", data, eocd + 10)[0]
    offset = struct.unpack_from("<I", data, eocd + 16)[0]
    for _ in range(count):
        if data[offset:offset + 4] != b"PK\x01\x02":
            raise AssertionError("Invalid fixture")
        name, extra, comment = struct.unpack_from("<HHH", data, offset + 28)
        yield offset
        offset += 46 + name + extra + comment


def run_normalizer(path):
    return subprocess.run(
        [JAVA, str(NORMALIZER), str(path)], stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, text=True, check=False,
    )


@unittest.skipUnless(JAVA, "Java is required")
class NormalizeSdkAarModesTest(unittest.TestCase):
    def test_normalizes_only_mode_and_keeps_canonical_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sdk.aar"
            fixture(path)
            canonical = path.read_bytes()
            expected = hashlib.sha256(canonical).digest()
            result = run_normalizer(path)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(canonical, path.read_bytes())

            private = bytearray(canonical)
            changed = []
            for entry in central_entries(private):
                mode = struct.unpack_from("<H", private, entry + 40)[0]
                if mode == 0o100644:
                    struct.pack_into("<H", private, entry + 40, 0o100600)
                    changed.extend([entry + 40, entry + 41])
            self.assertTrue(changed)
            path.write_bytes(private)
            result = run_normalizer(path)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(expected, hashlib.sha256(path.read_bytes()).digest())
            self.assertEqual(canonical, path.read_bytes())
            for index, (before, after) in enumerate(zip(private, canonical)):
                if before != after:
                    self.assertIn(index, changed)

    def test_valid_changed_content_still_fails_pinned_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sdk.aar"
            fixture(path)
            expected = hashlib.sha256(path.read_bytes()).hexdigest()
            fixture(path, b"tampered")
            result = run_normalizer(path)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertNotEqual(expected, hashlib.sha256(path.read_bytes()).hexdigest())
            sha256sum = shutil.which("sha256sum")
            if sha256sum:
                guard = subprocess.run(
                    [sha256sum, "-c", "-"], input=f"{expected}  {path}\n",
                    text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                    check=False,
                )
                self.assertNotEqual(0, guard.returncode)

    def test_rejects_bad_modes_paths_crc_and_trailing_bytes_without_writes(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sdk.aar"
            fixture(path)
            canonical = path.read_bytes()
            modes = bytearray(canonical)
            entries = list(central_entries(modes))
            struct.pack_into("<H", modes, entries[1] + 40, 0o120777)
            crc = bytearray(canonical)
            crc[entries[1] + 16] ^= 1
            invalids = [bytes(modes), bytes(crc), canonical + b"x"]
            unsafe = Path(directory) / "unsafe.aar"
            with zipfile.ZipFile(unsafe, "w") as archive:
                entry = zipfile.ZipInfo("../test")
                entry.create_system = 3
                entry.compress_type = zipfile.ZIP_DEFLATED
                entry.external_attr = 0o100644 << 16
                archive.writestr(entry, b"payload")
            invalids.append(unsafe.read_bytes())
            for data in invalids:
                with self.subTest(length=len(data)):
                    path.write_bytes(data)
                    result = run_normalizer(path)
                    self.assertNotEqual(0, result.returncode)
                    self.assertEqual(data, path.read_bytes())

    def test_shell_guard_remains_after_normalization(self):
        script = (ROOT / "ci" / "build_plugin_sdk.sh").read_text()
        normalize = script.index('java "$ROOT/ci/NormalizeSdkAarModes.java"')
        guard = script.index('"$SHA" build/outputs/aar/weartak-plugin-sdk-release.aar | sha256sum -c -')
        self.assertLess(normalize, guard)
        self.assertNotIn("umask 022", script)
        python = (ROOT / "ci" / "build_plugin_sdk.py").read_text()
        self.assertIn('if platform == "linux":', python)
        self.assertLess(python.index('str(ROOT / "ci" / "NormalizeSdkAarModes.java")'),
                        python.index("verify_hash(aar, expected)"))


if __name__ == "__main__":
    unittest.main()
