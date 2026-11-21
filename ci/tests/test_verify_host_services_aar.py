from hashlib import sha256
from io import BytesIO
from pathlib import Path
import importlib.util
import shutil
import subprocess
import tempfile
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "verify_host_services_aar", ROOT / "ci" / "verify_host_services_aar.py"
)
VERIFY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFY)


def make_aar(path, classes):
    classes_jar = BytesIO()
    with zipfile.ZipFile(classes_jar, "w") as archive:
        for name in classes:
            archive.writestr(name, b"class")
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("classes.jar", classes_jar.getvalue())


class VerifyHostServicesAarTests(unittest.TestCase):
    def test_rejects_duplicate_public_sdk_classes(self):
        with tempfile.TemporaryDirectory() as directory:
            public_sdk = Path(directory) / "public-sdk.aar"
            host_services = Path(directory) / "host-services.aar"
            make_aar(public_sdk, ["com/example/Shared.class"])
            make_aar(host_services, ["com/example/Shared.class"])

            with self.assertRaisesRegex(RuntimeError, "Shared.class"):
                VERIFY.verify_no_duplicate_classes(public_sdk, host_services)

    def test_returns_host_classes_jar_hash_when_class_sets_are_disjoint(self):
        with tempfile.TemporaryDirectory() as directory:
            public_sdk = Path(directory) / "public-sdk.aar"
            host_services = Path(directory) / "host-services.aar"
            make_aar(public_sdk, ["com/example/Public.class"])
            make_aar(host_services, ["com/example/Host.class"])

            digest, class_count = VERIFY.verify_no_duplicate_classes(
                public_sdk, host_services
            )

            host_jar, host_classes = VERIFY.class_entries(host_services)
            self.assertEqual(sha256(host_jar).hexdigest(), digest)
            self.assertEqual(1, class_count)
            self.assertEqual({"com/example/Host.class"}, host_classes)

    def test_rejects_aars_without_classes_jar(self):
        with tempfile.TemporaryDirectory() as directory:
            public_sdk = Path(directory) / "public-sdk.aar"
            host_services = Path(directory) / "host-services.aar"
            with zipfile.ZipFile(public_sdk, "w") as archive:
                archive.writestr("AndroidManifest.xml", b"manifest")
            make_aar(host_services, ["com/example/Host.class"])

            with self.assertRaisesRegex(RuntimeError, "classes.jar"):
                VERIFY.verify_no_duplicate_classes(public_sdk, host_services)


@unittest.skipUnless(shutil.which("java"), "Java is required")
class LauncherVerifierTests(unittest.TestCase):
    def run_verifier(self, public_sdk, host_services):
        return subprocess.run(
            ["java", str(ROOT / "ci" / "VerifyHostServicesAar.java"),
             str(public_sdk), str(host_services)],
            capture_output=True,
            text=True,
        )

    def test_hash_matches_python_verifier_for_disjoint_artifacts(self):
        with tempfile.TemporaryDirectory() as directory:
            public_sdk = Path(directory) / "public-sdk.aar"
            host_services = Path(directory) / "host-services.aar"
            make_aar(public_sdk, ["com/example/Public.class"])
            make_aar(host_services, ["com/example/Host.class"])
            expected, _ = VERIFY.verify_no_duplicate_classes(public_sdk, host_services)
            result = self.run_verifier(public_sdk, host_services)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(
                f"{expected}  classes.jar\n",
                host_services.with_name("verified-classes.sha256").read_text(),
            )

    def test_rejects_duplicate_classes_in_embedded_jars(self):
        with tempfile.TemporaryDirectory() as directory:
            public_sdk = Path(directory) / "public-sdk.aar"
            host_services = Path(directory) / "host-services.aar"
            make_aar(public_sdk, ["com/example/Shared.class"])
            make_aar(host_services, ["com/example/Other.class"])
            jar = BytesIO()
            with zipfile.ZipFile(jar, "w") as archive:
                archive.writestr("com/example/Shared.class", b"class")
            with zipfile.ZipFile(host_services, "a") as archive:
                archive.writestr("libs/extra.jar", jar.getvalue())
            result = self.run_verifier(public_sdk, host_services)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("com/example/Shared.class", result.stderr)
            self.assertFalse(host_services.with_name("verified-classes.sha256").exists())

    def test_rejects_malformed_embedded_jar(self):
        with tempfile.TemporaryDirectory() as directory:
            public_sdk = Path(directory) / "public-sdk.aar"
            host_services = Path(directory) / "host-services.aar"
            make_aar(public_sdk, ["com/example/Public.class"])
            make_aar(host_services, ["com/example/Host.class"])
            with zipfile.ZipFile(host_services, "a") as archive:
                archive.writestr("libs/broken.jar", b"not a zip file")
            result = self.run_verifier(public_sdk, host_services)
            self.assertNotEqual(0, result.returncode)
            self.assertFalse(host_services.with_name("verified-classes.sha256").exists())

    def test_rejects_duplicates_within_host_aar(self):
        with tempfile.TemporaryDirectory() as directory:
            public_sdk = Path(directory) / "public-sdk.aar"
            host_services = Path(directory) / "host-services.aar"
            make_aar(public_sdk, ["com/example/Public.class"])
            make_aar(host_services, ["com/example/Host.class"])
            jar = BytesIO()
            with zipfile.ZipFile(jar, "w") as archive:
                archive.writestr("com/example/Host.class", b"class")
            with zipfile.ZipFile(host_services, "a") as archive:
                archive.writestr("libs/extra.jar", jar.getvalue())
            result = self.run_verifier(public_sdk, host_services)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("duplicate classes across jars", result.stderr)


if __name__ == "__main__":
    unittest.main()
