import base64
import importlib.util
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "build_plugin_sdk", ROOT / "ci" / "build_plugin_sdk.py"
)
SDK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SDK)

PROBE_SPEC = importlib.util.spec_from_file_location(
    "verify_tak_sdk_source_access", ROOT / "ci" / "verify_tak_sdk_source_access.py"
)
PROBE = importlib.util.module_from_spec(PROBE_SPEC)
with mock.patch.dict(sys.modules, {"build_plugin_sdk": SDK}):
    PROBE_SPEC.loader.exec_module(PROBE)


def ci_job_body(ci, name):
    match = re.search(
        rf"(?ms)^{re.escape(name)}:[ \t]*\n(.*?)(?=^[A-Za-z0-9_.-]+:[ \t]*(?:\n|$)|\Z)",
        ci,
    )
    if match is None:
        raise ValueError(f"CI job not found: {name}")
    return match.group(1)


class PluginSdkAuthTests(unittest.TestCase):
    def setUp(self):
        self.git_home = tempfile.TemporaryDirectory()
        self.addCleanup(self.git_home.cleanup)

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
        job = ci_job_body(ci, "verify_tak_sdk_source_access")

        self.assertIn("name: python:3.12-bookworm", job)
        self.assertIn("python3 ci/verify_tak_sdk_source_access.py", job)
        self.assertIn("aegorsuch-weartak-313-ditto-host-contract", job)
        self.assertIn("CI_PROJECT_ID == \"6739\"", job)
        self.assertNotIn("allow_failure", job)

    def test_ci_job_extraction_stops_at_next_top_level_job(self):
        ci = "probe:\n  script: check\nnext_job:\n  allow_failure: false\n"
        self.assertEqual("  script: check\n", ci_job_body(ci, "probe"))
        self.assertIn("allow_failure", ci_job_body(ci, "next_job"))
        with self.assertRaisesRegex(ValueError, "CI job not found"):
            ci_job_body(ci, "missing")

    def test_source_probe_checks_pinned_commit_after_fetching_moving_branch(self):
        with mock.patch.object(PROBE, "fetch_source") as fetch, \
             mock.patch.object(PROBE, "git", side_effect=["", "", PROBE.SDK_COMMIT, PROBE.SDK_TREE]):
            PROBE.main()
        self.assertEqual(
            [PROBE.SDK_REF, PROBE.SDK_COMMIT],
            [call.args[2] for call in fetch.call_args_list],
        )

    def test_source_probe_rejects_mismatched_pinned_tree(self):
        with mock.patch.object(PROBE, "fetch_source"), \
             mock.patch.object(PROBE, "git", side_effect=["", "", PROBE.SDK_COMMIT, "wrong-tree"]):
            with self.assertRaisesRegex(RuntimeError, "immutable commit/tree"):
                PROBE.main()

    def test_source_probe_accepts_advanced_branch_with_original_pinned_tree(self):
        if shutil.which("git") is None:
            self.skipTest("Git is required")
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory)
            def git(*args):
                return subprocess.check_output(
                    ["git", "-C", str(source), *args], text=True, stderr=subprocess.DEVNULL,
                ).strip()
            git("init", "--quiet")
            git("config", "user.name", "Fixture")
            git("config", "user.email", "fixture@example.invalid")
            (source / "fixture").write_text("first", encoding="utf-8")
            git("add", "fixture")
            git("commit", "--quiet", "-m", "first")
            commit = git("rev-parse", "HEAD")
            tree = git("rev-parse", "HEAD^{tree}")
            (source / "fixture").write_text("second", encoding="utf-8")
            git("commit", "--quiet", "-am", "second")
            branch = "refs/heads/" + git("branch", "--show-current")

            def fetch_local(checkout, repository, ref):
                SDK.fetch_source(checkout, repository, ref, local_source=source)

            with mock.patch.object(PROBE, "SDK_REF", branch), \
                 mock.patch.object(PROBE, "SDK_COMMIT", commit), \
                 mock.patch.object(PROBE, "SDK_TREE", tree), \
                 mock.patch.object(PROBE, "fetch_source", side_effect=fetch_local):
                PROBE.main()

    def test_tak_gov_fetch_uses_only_ephemeral_job_token_header(self):
        token = "fixture-job-token"
        env = SDK.git_fetch_environment(
            SDK.TAK_GOV_SDK_REPOSITORY,
            self.git_home.name,
            {
                "PATH": "fixture-path",
                "CI_JOB_TOKEN": token,
                "GIT_CONFIG_VALUE_9": "old",
                "GIT_CONFIG_PARAMETERS": "'credential.helper=ambient'",
            },
        )

        parameters = shlex.split(env["GIT_CONFIG_PARAMETERS"])
        credentials = base64.b64encode(
            f"gitlab-ci-token:{token}".encode("utf-8")
        ).decode("ascii")
        self.assertEqual(
            [
                "credential.helper=",
                "http.extraHeader=",
                "http.followRedirects=false",
                f"core.hooksPath={os.devnull}",
                f"http.{SDK.TAK_GOV_SDK_REPOSITORY}/.extraHeader="
                f"Authorization: Basic {credentials}",
            ],
            parameters,
        )
        self.assertEqual(
            " ".join("'" + parameter + "'" for parameter in parameters),
            env["GIT_CONFIG_PARAMETERS"],
        )
        self.assertNotIn("CI_JOB_TOKEN", env)
        self.assertNotIn("GIT_CONFIG_COUNT", env)
        self.assertNotIn("GIT_CONFIG_VALUE_9", env)
        self.assertNotIn("ambient", env["GIT_CONFIG_PARAMETERS"])
        self.assertEqual("0", env["GIT_TERMINAL_PROMPT"])
        self.assertNotIn(token, " ".join(env.values()))

    def test_auth_header_is_scoped_to_the_exact_sdk_repository(self):
        repository = SDK.TAK_GOV_SDK_REPOSITORY
        env = SDK.git_fetch_environment(
            repository,
            self.git_home.name,
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

    def test_git_receives_legacy_config_resets(self):
        env = SDK.git_fetch_environment(
            SDK.TAK_GOV_SDK_REPOSITORY,
            self.git_home.name,
            {"PATH": os.environ["PATH"], "CI_JOB_TOKEN": "fixture-job-token"},
        )
        for key, expected in (
            ("credential.helper", ""),
            ("http.extraHeader", ""),
            ("http.followRedirects", "false"),
            ("core.hooksPath", os.devnull),
        ):
            with self.subTest(key=key):
                result = subprocess.run(
                    ["git", "config", "--get", key],
                    env=env, capture_output=True, text=True, check=True,
                )
                self.assertEqual(expected, result.stdout.strip())

    def test_isolated_home_blocks_ambient_global_config_without_global_override(self):
        repository = SDK.TAK_GOV_SDK_REPOSITORY
        with tempfile.TemporaryDirectory() as directory:
            ambient = Path(directory) / "ambient"
            ambient.mkdir()
            (ambient / ".gitconfig").write_text(
                '[credential]\n\thelper = fixture-ambient-helper\n'
                f'[http "{repository}"]\n'
                '\textraHeader = Ambient: fixture-header\n'
                '\tfollowRedirects = true\n',
                encoding="utf-8",
            )
            (ambient / "git").mkdir()
            (ambient / "git" / "config").write_text(
                "[credential]\n\thelper = fixture-xdg-helper\n", encoding="utf-8"
            )
            ambient_env = {
                "PATH": os.environ["PATH"],
                "HOME": str(ambient),
                "XDG_CONFIG_HOME": str(ambient),
                "GIT_CONFIG_NOSYSTEM": "1",
                "GIT_TERMINAL_PROMPT": "0",
            }
            if "SystemRoot" in os.environ:
                ambient_env["SystemRoot"] = os.environ["SystemRoot"]

            def git_config(env, *args):
                return subprocess.run(
                    ["git", "config", *args], cwd=directory, env=env,
                    capture_output=True, text=True, check=True,
                ).stdout.strip()

            self.assertEqual(
                "Ambient: fixture-header",
                git_config(
                    ambient_env, "--get-urlmatch", "http.extraHeader",
                    repository + "/info/refs",
                ),
            )
            self.assertIn(
                "fixture-xdg-helper",
                git_config(ambient_env, "--get-all", "credential.helper"),
            )
            self.assertIn(
                "fixture-ambient-helper",
                git_config(ambient_env, "--get-all", "credential.helper"),
            )
            self.assertEqual(
                "true",
                git_config(
                    ambient_env, "--get-urlmatch", "http.followRedirects",
                    repository + "/info/refs",
                ),
            )
            env = SDK.git_fetch_environment(
                repository, self.git_home.name,
                {**ambient_env, "CI_JOB_TOKEN": "fixture-job-token"},
            )
            env.pop("GIT_CONFIG_GLOBAL")
            self.assertEqual(self.git_home.name, env["HOME"])
            self.assertEqual(self.git_home.name, env["XDG_CONFIG_HOME"])
            self.assertEqual(
                "Authorization: Basic " + base64.b64encode(
                    b"gitlab-ci-token:fixture-job-token"
                ).decode("ascii"),
                git_config(
                    env, "--get-urlmatch", "http.extraHeader",
                    repository + "/info/refs",
                ),
            )
            self.assertEqual("", git_config(env, "--get-all", "credential.helper"))
            self.assertEqual(
                "false",
                git_config(
                    env, "--get-urlmatch", "http.followRedirects",
                    repository + "/info/refs",
                ),
            )

    def test_fetch_uses_temporary_home_only_for_git(self):
        with tempfile.TemporaryDirectory() as checkout:
            seen = {}

            def fake_fetch(*args, **kwargs):
                env = kwargs["env"]
                seen["home"] = env["HOME"]
                self.assertTrue(Path(seen["home"]).is_dir())
                self.assertEqual(seen["home"], env["XDG_CONFIG_HOME"])
                self.assertNotIn("CI_JOB_TOKEN", env)
                return mock.Mock(returncode=0)

            with mock.patch.dict(
                os.environ, {"HOME": "fixture-original-home", "CI_JOB_TOKEN": "fixture-token"}
            ), mock.patch.object(SDK.subprocess, "run", side_effect=fake_fetch):
                SDK.fetch_source(Path(checkout), SDK.TAK_GOV_SDK_REPOSITORY, "f" * 40)
                self.assertEqual("fixture-original-home", os.environ["HOME"])
            self.assertFalse(Path(seen["home"]).exists())

    def test_legacy_config_quotes_apostrophes_in_values(self):
        path = "fixture path O'Brien"
        with mock.patch.object(SDK.os, "devnull", path):
            env = SDK.git_fetch_environment(
                "https://github.com/example/plugin-sdk.git",
                self.git_home.name,
                {"PATH": os.environ["PATH"]},
            )
        env["GIT_CONFIG_GLOBAL"] = os.devnull
        result = subprocess.run(
            ["git", "config", "--get", "core.hooksPath"],
            env=env, capture_output=True, text=True, check=True,
        )
        self.assertEqual(path, result.stdout.strip())

    def test_github_fetch_does_not_inherit_job_token_or_auth_config(self):
        env = SDK.git_fetch_environment(
            "https://github.com/example/plugin-sdk.git",
            self.git_home.name,
            {"PATH": "fixture-path", "CI_JOB_TOKEN": "fixture-job-token"},
        )

        self.assertNotIn("CI_JOB_TOKEN", env)
        self.assertFalse(any("fixture-job-token" in value for value in env.values()))
        self.assertEqual(4, len(shlex.split(env["GIT_CONFIG_PARAMETERS"])))
        self.assertNotIn("GIT_CONFIG_COUNT", env)

    def test_tak_gov_fetch_fails_closed_without_job_token(self):
        with self.assertRaisesRegex(RuntimeError, "CI_JOB_TOKEN"):
            SDK.git_fetch_environment(
                SDK.TAK_GOV_SDK_REPOSITORY, self.git_home.name, {"PATH": "fixture-path"}
            )

    def test_sanitizer_redacts_at_and_colon_in_passwords(self):
        message = SDK.sanitize_git_stderr(
            "fatal: unable to access "
            "'https://user:p@ss:word@proxy.example:8443/sdk.git': "
            "authentication failed"
        )

        self.assertIn("https://[REDACTED]@proxy.example:8443/sdk.git", message)
        self.assertNotIn("p@ss:word", message)

    def test_sanitizer_redacts_percent_encoded_credentials(self):
        message = SDK.sanitize_git_stderr(
            "https://user%40domain:p%40ss%3Aword@proxy.example:8443/sdk.git"
        )

        self.assertIn("https://[REDACTED]@proxy.example:8443/sdk.git", message)
        self.assertNotIn("user%40domain", message)
        self.assertNotIn("p%40ss%3Aword", message)

    def test_sanitizer_redacts_socks_proxy_credentials(self):
        message = SDK.sanitize_git_stderr(
            "Could not resolve proxy: "
            "socks5h://user:pa@ss:word@proxy.example:1080"
        )

        self.assertIn("socks5h://[REDACTED]@proxy.example:1080", message)
        self.assertNotIn("pa@ss:word", message)

    def test_sanitizer_redacts_multiple_urls_on_one_line(self):
        message = SDK.sanitize_git_stderr(
            "proxies https://alice:one@proxy-a.example:8080/path and "
            "socks4a://bob:two@proxy-b.example:1081/path"
        )

        self.assertIn("https://[REDACTED]@proxy-a.example:8080/path", message)
        self.assertIn("socks4a://[REDACTED]@proxy-b.example:1081/path", message)
        self.assertNotIn("alice:one", message)
        self.assertNotIn("bob:two", message)

    def test_sanitizer_redacts_scheme_less_proxy_credentials(self):
        message = SDK.sanitize_git_stderr(
            "Could not resolve proxy: user:p@ss:word@proxy.example:1080"
        )

        self.assertIn("proxy: [REDACTED]@proxy.example:1080", message)
        self.assertNotIn("p@ss:word", message)

    def test_sanitizer_preserves_url_query_fragment_host_path_and_error(self):
        message = SDK.sanitize_git_stderr(
            "fatal: unable to access "
            "'https://user:password@proxy.example:8443/group/sdk.git"
            "?mode=read#release': TLS certificate verify failed"
        )

        self.assertIn("fatal: unable to access", message)
        self.assertIn(
            "https://[REDACTED]@proxy.example:8443/group/sdk.git"
            "?mode=read#release",
            message,
        )
        self.assertIn("TLS certificate verify failed", message)
        self.assertNotIn("user:password", message)

    def test_sanitizer_redacts_auth_header_and_job_token(self):
        token = "fixture-job-token-that-must-not-leak"
        encoded = base64.b64encode(
            f"gitlab-ci-token:{token}".encode("utf-8")
        ).decode("ascii")
        message = SDK.sanitize_git_stderr(
            f"fatal: Authorization: Basic {encoded}\njob_token={token}",
            token,
        )

        self.assertIn("Authorization: [REDACTED]", message)
        self.assertIn("job_token=[REDACTED]", message)
        self.assertNotIn(token, message)
        self.assertNotIn(encoded, message)

    def test_fetch_failure_reports_exit_code_and_sanitized_stderr(self):
        token = "fixture-job-token-that-must-not-leak"
        stderr = f"fatal: authentication failed for token {token}".encode()
        with tempfile.TemporaryDirectory() as directory, \
             mock.patch.dict(os.environ, {"CI_JOB_TOKEN": token}), \
             mock.patch.object(SDK, "run", return_value="git version 2.45.0.windows.1"), \
             mock.patch.object(
                 SDK.subprocess, "run",
                 return_value=mock.Mock(returncode=128, stderr=stderr),
             ):
            with self.assertRaisesRegex(
                RuntimeError, "git version 2.45.0.windows.1; exit 128"
            ) as error:
                SDK.fetch_source(
                    Path(directory), SDK.TAK_GOV_SDK_REPOSITORY, "f" * 40
                )

        self.assertIn("authentication failed", str(error.exception))
        self.assertNotIn(token, str(error.exception))

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
                    repository, self.git_home.name, {"CI_JOB_TOKEN": "fixture-job-token"}
                )

    def test_untrusted_children_do_not_receive_gitlab_token_or_auth_configuration(self):
        env = SDK.credential_free_environment(
            {
                "PATH": "fixture-path",
                "CI_JOB_TOKEN": "fixture-job-token",
                "GIT_CONFIG_COUNT": "5",
                "GIT_CONFIG_VALUE_4": "JOB-TOKEN: fixture-job-token",
                "GIT_CONFIG_PARAMETERS": "'http.extraHeader=Authorization: Basic fixture-secret'",
                "GRADLE_OPTS": "-Xmx1g",
                "HOME": "fixture-original-home",
                "XDG_CONFIG_HOME": "fixture-original-xdg",
            }
        )

        self.assertNotIn("CI_JOB_TOKEN", env)
        self.assertFalse(any(key.startswith("GIT_CONFIG_") for key in env))
        self.assertEqual("-Xmx1g", env["GRADLE_OPTS"])
        self.assertEqual("fixture-original-home", env["HOME"])
        self.assertEqual("fixture-original-xdg", env["XDG_CONFIG_HOME"])


if __name__ == "__main__":
    unittest.main()
