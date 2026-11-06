import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import subprocess
import shutil
import zipfile


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("build_plugin_sdk", ROOT / "ci" / "build_plugin_sdk.py")
SDK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SDK)


class PluginSdkTests(unittest.TestCase):
    def test_fresh_checkout_has_no_head_and_tracked_changes_still_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            checkout = Path(directory)
            SDK.run("git", "init", "--quiet", cwd=checkout)
            SDK.verify_checkout_clean(checkout)
            source = checkout / "source.txt"
            source.write_bytes(b"original\n")
            SDK.run("git", "add", "source.txt", cwd=checkout)
            SDK.run("git", "-c", "user.name=SDK Fixture", "-c",
                    "user.email=fixture@example.invalid", "commit", "--quiet",
                    "-m", "Fixture", cwd=checkout)
            SDK.verify_checkout_clean(checkout)
            source.write_bytes(b"modified\n")
            with self.assertRaisesRegex(RuntimeError, "tracked changes"):
                SDK.verify_checkout_clean(checkout)

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
        overlay = pin["overlay"]
        SDK.verify_hash(ROOT / overlay["archive"], overlay["archive_sha256"])
        SDK.verify_hash(ROOT / overlay["inventory"], overlay["source_sha256"])

    def test_overlay_is_additive_exact_and_repeatable(self):
        pin = json.loads((ROOT / "ci" / "plugin-sdk.json").read_text())
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory)
            added = SDK.apply_overlay(target, pin["overlay"])
            self.assertEqual(17, len(added))
            self.assertEqual(added, SDK.apply_overlay(target, pin["overlay"]))
            sample = target / sorted(added)[0]
            sample.write_bytes(b"modified source")
            with self.assertRaisesRegex(RuntimeError, "hash mismatch"):
                SDK.apply_overlay(target, pin["overlay"])

    def test_canonical_export_preserves_raw_blob_newlines(self):
        contents = b"line1\nline2\n"
        response = subprocess.CompletedProcess(
            [], 0, stdout=b"0" * 40 + f" blob {len(contents)}\n".encode() + contents + b"\n"
        )
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory)
            script = target / "gradlew.bat"
            script.write_bytes(b"line1\r\nline2\r\n")
            with patch.object(SDK.subprocess, "run", return_value=response) as command:
                SDK.restore_git_blob_bytes(target, {"gradlew.bat"})
                self.assertEqual(b"HEAD:gradlew.bat\n", command.call_args.kwargs["input"])
            self.assertEqual(contents, script.read_bytes())

    def test_overlay_rejects_nontransport_and_traversal_paths(self):
        for name in ("../escape.kt", "build.gradle.kts"):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                archive = root / "overlay.zip"
                inventory = root / "overlay.sha256"
                contents = b"fixture"
                with zipfile.ZipFile(archive, "w") as source:
                    source.writestr(name, contents)
                inventory.write_bytes(
                    f"{SDK.hashlib.sha256(contents).hexdigest()}  {name}\n".encode("ascii")
                )
                overlay = {
                    "archive": archive.name,
                    "archive_sha256": SDK.hashlib.sha256(archive.read_bytes()).hexdigest(),
                    "inventory": inventory.name,
                    "source_sha256": SDK.hashlib.sha256(inventory.read_bytes()).hexdigest(),
                }
                with self.assertRaisesRegex(RuntimeError, "overlay"):
                    SDK.apply_overlay(root / "output", overlay, root=root)

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
                          "sha256sum -c -", "assembleRelease", "diff --quiet HEAD"):
            self.assertIn(invariant, text)


if __name__ == "__main__":
    unittest.main()
