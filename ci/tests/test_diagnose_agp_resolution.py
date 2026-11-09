import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "ci" / "diagnose_agp_resolution.sh"


def find_shell():
    shell = shutil.which("sh")
    if shell:
        return shell
    git = shutil.which("git")
    if git:
        bundled = Path(git).resolve().parents[1] / "usr" / "bin" / "sh.exe"
        if bundled.is_file():
            return str(bundled)
    return None


@unittest.skipUnless(find_shell(), "POSIX shell required for diagnostic tests")
class DiagnosticTests(unittest.TestCase):
    def run_diagnostic(self, curl_exit=0, gradle_exit=0):
        prelude = """
java() { printf 'java\\n' >> "$CALL_LOG"; }
curl() {
    printf 'curl %s\\n' "$*" >> "$CALL_LOG"
    return "$CURL_EXIT"
}
sh() {
    printf 'gradle %s\\n' "$*" >> "$CALL_LOG"
    case "$*" in
        *--version*) return 0 ;;
        *) return "$GRADLE_EXIT" ;;
    esac
}
. ci/diagnose_agp_resolution.sh
"""
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "calls"
            environment = dict(
                os.environ,
                CALL_LOG=log.as_posix(),
                CURL_EXIT=str(curl_exit),
                GRADLE_EXIT=str(gradle_exit),
                CI_COMMIT_SHA="test-source-sha",
            )
            result = subprocess.run(
                [find_shell(), "-c", prelude],
                cwd=ROOT,
                env=environment,
                capture_output=True,
                text=True,
            )
            return result, log.read_text()

    def test_shell_syntax(self):
        subprocess.run([find_shell(), "-n", str(SCRIPT)], check=True)

    def test_success_only_configures_gradle(self):
        result, calls = self.run_diagnostic()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("test-source-sha", result.stdout)
        self.assertEqual(calls.count("curl "), 2)
        self.assertEqual(calls.count("--connect-timeout 10 --max-time 30"), 2)
        self.assertIn(
            "gradle ./gradlew --no-daemon help --info --stacktrace --console=plain",
            calls,
        )
        self.assertNotIn("assemble", calls)
        self.assertNotIn("signing", calls)

    def test_curl_failure_still_runs_gradle_and_fails(self):
        result, calls = self.run_diagnostic(curl_exit=6)
        self.assertEqual(result.returncode, 1)
        self.assertEqual(calls.count("curl "), 2)
        self.assertIn("help --info", calls)
        self.assertIn("curl exit 6", result.stdout)

    def test_gradle_failure_is_not_hidden(self):
        result, _ = self.run_diagnostic(gradle_exit=1)
        self.assertEqual(result.returncode, 1)
        self.assertIn("Gradle configuration failed", result.stdout)


if __name__ == "__main__":
    unittest.main()
