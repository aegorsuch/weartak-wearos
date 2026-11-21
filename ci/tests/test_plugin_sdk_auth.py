import base64
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import unittest


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
