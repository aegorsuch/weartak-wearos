import base64
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "build_plugin_sdk", ROOT / "ci" / "build_plugin_sdk.py"
)
SDK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SDK)


class PluginSdkAuthTests(unittest.TestCase):
    def test_linux_builder_scopes_auth_to_sdk_and_clears_it_before_build(self):
        helper = (ROOT / "ci" / "build_plugin_sdk.sh").read_text()

        self.assertIn(
            "https://git.tak.gov/weartak-plugins/weartak-plugin-sdk.git", helper
        )
        self.assertIn("gitlab-ci-token:$job_token", helper)
        self.assertIn("unset CI_JOB_TOKEN", helper)
        self.assertIn("GIT_SSL_CAINFO|GIT_SSL_CAPATH", helper)
        self.assertIn("scoped Git authorization configuration mismatch", helper)
        self.assertIn("Unsupported plugin SDK source host", helper)

    def test_shell_auth_works_without_git_config_count(self):
        shell = shutil.which("sh")
        if shell is None or shutil.which("git") is None:
            self.skipTest("Shell and Git are required")
        helper = (ROOT / "ci" / "build_plugin_sdk.sh").read_text()
        functions = helper.split("configure_unauthenticated_git() {", 1)[1].split(
            '\ncase "$REPOSITORY" in', 1
        )[0]
        script = (
            "set -eu\n"
            "unset GIT_CONFIG_COUNT\n"
            "configure_unauthenticated_git() {" + functions + "\n"
            "REPOSITORY='https://git.tak.gov/weartak-plugins/weartak-plugin-sdk.git'\n"
            "job_token=fixture-job-token\n"
            "configure_unauthenticated_git\n"
            "configure_tak_git_auth\n"
            '[ "$(git config --get-urlmatch http.extraHeader "$REPOSITORY/info/refs")" '
            '= "Authorization: Basic $credentials" ]\n'
            'other=$(git config --get-urlmatch http.extraHeader '
            '"https://git.tak.gov/weartak-plugins/other.git/info/refs" || true)\n'
            '[ -z "$other" ]\n'
            '[ -z "${GIT_CONFIG_COUNT:-}" ]\n'
        )
        subprocess.run([shell], input=script, text=True, check=True)

    def test_source_access_probe_is_scoped_to_the_current_core_branch(self):
        ci = (ROOT / ".gitlab-ci.yml").read_text()
        job = ci.split("\nverify_tak_sdk_source_access:", 1)[1].split(
            "\nrelease_publisher_tests:", 1
        )[0]

        self.assertIn("name: python:3.12-bookworm", job)
        self.assertIn("python3 ci/verify_tak_sdk_source_access.py", job)
        self.assertIn("aegorsuch-weartak-313-ditto-host-contract", job)
        self.assertIn("CI_PROJECT_ID == \"6739\"", job)
        self.assertNotIn("allow_failure", job)

    def test_tak_gov_fetch_uses_only_ephemeral_job_token_header(self):
        token = "fixture-job-token"
        env = SDK.git_fetch_environment(
            SDK.TAK_GOV_SDK_REPOSITORY,
            {"PATH": "fixture-path", "CI_JOB_TOKEN": token, "GIT_CONFIG_VALUE_9": "old"},
        )

        self.assertEqual("5", env["GIT_CONFIG_COUNT"])
        self.assertEqual(
            f"http.{SDK.TAK_GOV_SDK_REPOSITORY}/.extraHeader",
            env["GIT_CONFIG_KEY_4"],
        )
        header_parts = env["GIT_CONFIG_VALUE_4"].split(" ", 2)
        self.assertEqual(["Authorization:", "Basic"], header_parts[:2])
        self.assertEqual(
            "gitlab-ci-token:" + token,
            base64.b64decode(header_parts[2]).decode("utf-8"),
        )
        self.assertNotIn("CI_JOB_TOKEN", env)
        self.assertNotIn("GIT_CONFIG_VALUE_9", env)
        self.assertEqual("0", env["GIT_TERMINAL_PROMPT"])
        self.assertNotIn(token, " ".join(env.values()).replace(env["GIT_CONFIG_VALUE_4"], ""))

    def test_auth_header_is_scoped_to_the_exact_sdk_repository(self):
        repository = SDK.TAK_GOV_SDK_REPOSITORY
        env = SDK.git_fetch_environment(
            repository,
            {"PATH": os.environ["PATH"], "CI_JOB_TOKEN": "fixture-job-token"},
        )
        sdk_url = repository + "/info/refs?service=git-upload-pack"
        other_url = "https://git.tak.gov/weartak-plugins/another-project.git/info/refs"

        sdk_header = subprocess.run(
            ["git", "config", "--get-urlmatch", "http.extraHeader", sdk_url],
            env=env,
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip()
        other_header = subprocess.run(
            ["git", "config", "--get-urlmatch", "http.extraHeader", other_url],
            env=env,
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip()

        self.assertIn("Authorization: Basic ", sdk_header)
        self.assertEqual("", other_header)

    def test_github_fetch_does_not_inherit_job_token_or_auth_config(self):
        env = SDK.git_fetch_environment(
            "https://github.com/example/plugin-sdk.git",
            {"PATH": "fixture-path", "CI_JOB_TOKEN": "fixture-job-token"},
        )

        self.assertNotIn("CI_JOB_TOKEN", env)
        self.assertFalse(any("fixture-job-token" in value for value in env.values()))
        self.assertEqual("4", env["GIT_CONFIG_COUNT"])

    def test_tak_gov_fetch_fails_closed_without_job_token(self):
        with self.assertRaisesRegex(RuntimeError, "CI_JOB_TOKEN"):
            SDK.git_fetch_environment(
                SDK.TAK_GOV_SDK_REPOSITORY, {"PATH": "fixture-path"}
            )

    def test_local_source_fetches_only_the_requested_commit(self):
        if shutil.which("git") is None:
            self.skipTest("Git is required")
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source"
            checkout = Path(directory) / "checkout"
            source.mkdir()
            checkout.mkdir()
            subprocess.run(["git", "init", "--quiet"], cwd=source, check=True)
            (source / "file.txt").write_text("fixture", encoding="utf-8")
            subprocess.run(["git", "add", "file.txt"], cwd=source, check=True)
            subprocess.run(
                ["git", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                 "commit", "--quiet", "-m", "fixture"], cwd=source, check=True,
            )
            ref = subprocess.check_output(
                ["git", "rev-parse", "HEAD"], cwd=source, text=True,
            ).strip()
            subprocess.run(["git", "init", "--quiet"], cwd=checkout, check=True)

            SDK.fetch_source(checkout, SDK.TAK_GOV_SDK_REPOSITORY, ref, source)
            fetched = subprocess.check_output(
                ["git", "rev-parse", "FETCH_HEAD"], cwd=checkout, text=True,
            ).strip()
            self.assertEqual(ref, fetched)
            with self.assertRaisesRegex(RuntimeError, "unavailable in local source"):
                SDK.fetch_source(checkout, SDK.TAK_GOV_SDK_REPOSITORY, "f" * 40, source)
            with self.assertRaisesRegex(RuntimeError, "HTTPS|canonical"):
                SDK.fetch_source(checkout, "https://example.invalid/sdk.git", ref, source)

    def test_local_source_is_rejected_in_ci(self):
        with mock.patch.object(SDK.sys, "argv", ["build_plugin_sdk.py", "--local-source", "."]):
            with mock.patch.dict(os.environ, {"CI": "true"}):
                with self.assertRaisesRegex(RuntimeError, "only supported outside CI"):
                    SDK.main()

    def test_local_source_does_not_forward_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            with mock.patch.dict(os.environ, {"CI_JOB_TOKEN": "fixture-secret"}):
                with mock.patch.object(SDK.subprocess, "run") as git:
                    git.return_value.returncode = 0
                    SDK.fetch_source(
                        Path(directory), SDK.TAK_GOV_SDK_REPOSITORY, "f" * 40,
                        Path(directory),
                    )
            env = git.call_args.kwargs["env"]
            self.assertNotIn("CI_JOB_TOKEN", env)
            self.assertFalse(any("fixture-secret" in value for value in env.values()))
            self.assertEqual(os.devnull, env["GIT_CONFIG_GLOBAL"])

    def test_rejects_untrusted_hosts_and_credential_bearing_urls(self):
        for repository in (
            "https://git.tak.gov.attacker.example/core/sdk.git",
            "https://user:password@git.tak.gov/core/sdk.git",
            "https://git.tak.gov/another/repository.git",
            "http://git.tak.gov/core/sdk.git",
        ):
            with self.subTest(repository=repository), self.assertRaisesRegex(
                RuntimeError, "HTTPS|canonical"
            ):
                SDK.git_fetch_environment(
                    repository, {"CI_JOB_TOKEN": "fixture-job-token"}
                )

    def test_untrusted_children_do_not_receive_gitlab_token_or_auth_configuration(self):
        env = SDK.credential_free_environment(
            {
                "PATH": "fixture-path",
                "CI_JOB_TOKEN": "fixture-job-token",
                "GIT_CONFIG_COUNT": "5",
                "GIT_CONFIG_VALUE_4": "JOB-TOKEN: fixture-job-token",
                "GRADLE_OPTS": "-Xmx1g",
            }
        )

        self.assertNotIn("CI_JOB_TOKEN", env)
        self.assertFalse(any(key.startswith("GIT_CONFIG_") for key in env))
        self.assertEqual("-Xmx1g", env["GRADLE_OPTS"])


if __name__ == "__main__":
    unittest.main()
