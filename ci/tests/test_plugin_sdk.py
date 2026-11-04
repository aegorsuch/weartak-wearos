import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("build_plugin_sdk", ROOT / "ci" / "build_plugin_sdk.py")
SDK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SDK)


class PluginSdkTests(unittest.TestCase):
    def test_hash_rejects_modified_artifact(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sdk.aar"
            path.write_bytes(b"fixture")
            SDK.verify_hash(path, SDK.hashlib.sha256(b"fixture").hexdigest())
            with self.assertRaisesRegex(RuntimeError, "hash mismatch"):
                SDK.verify_hash(path, "0" * 64)

    def test_pin_is_immutable_and_default_has_no_legacy_fallback(self):
        pin = json.loads((ROOT / "ci" / "plugin-sdk.json").read_text())
        self.assertRegex(pin["commit"], r"^[0-9a-f]{40}$")
        self.assertRegex(pin["tree"], r"^[0-9a-f]{40}$")
        self.assertRegex(pin["release_sha256"], r"^[0-9a-f]{64}$")
        gradle = (ROOT / "app" / "build.gradle").read_text()
        self.assertIn('pluginSdkPin.commit', gradle)
        self.assertNotIn("rootProject.file('weartak-plugin-sdk-release.aar')", gradle)
        self.assertIn("dependsOn tasks.named('verifyPluginSdk'), sdkNotices", gradle)
        self.assertIn("include 'LICENSE', 'LICENSE-ADDITIONAL-PERMISSION.md'", gradle)

    def test_build_sign_and_lint_paths_prepare_verified_sdk(self):
        ci = (ROOT / ".gitlab-ci.yml").read_text()
        for job, end in (("android_mopp_check:", "release_publisher_tests:"),
                         ("host_lint_debug:", "agp_resolution_diagnostic:"),
                         (".release_build:", "main_release:")):
            section = ci.split("\n" + job, 1)[1].split("\n" + end, 1)[0]
            self.assertIn("python3 ci/build_plugin_sdk.py", section)


if __name__ == "__main__":
    unittest.main()
