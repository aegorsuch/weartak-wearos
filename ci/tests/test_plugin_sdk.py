import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import subprocess
import shutil


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
        self.assertEqual({"windows", "linux"}, pin["release_sha256"].keys())
        for expected in pin["release_sha256"].values():
            self.assertRegex(expected, r"^[0-9a-f]{64}$")
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
            self.assertIn("sh ci/build_plugin_sdk.sh", section)

    def test_android_bootstrap_uses_existing_shell_tools(self):
        helper = ROOT / "ci" / "build_plugin_sdk.sh"
        shell = shutil.which("sh")
        if not shell:
            git = shutil.which("git")
            shell = str(Path(git).resolve().parents[1] / "usr" / "bin" / "sh.exe") if git else None
        self.assertTrue(shell and Path(shell).is_file(), "POSIX shell required")
        subprocess.run([shell, "-n", str(helper)], check=True)
        text = helper.read_text()
        for invariant in ("ci/plugin-sdk.json", "core.autocrlf=false", "HEAD^{tree}",
                          "sha256sum -c -", "assembleRelease", "--untracked-files=no"):
            self.assertIn(invariant, text)


if __name__ == "__main__":
    unittest.main()
