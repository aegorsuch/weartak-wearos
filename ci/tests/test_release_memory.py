from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]


class ReleaseMemoryTests(unittest.TestCase):
    def test_release_bounds_workers_and_shares_existing_heap_with_kotlin(self):
        ci = (ROOT / ".gitlab-ci.yml").read_text()
        release = ci.split("\n.release_build:", 1)[1].split("\nmain_release:", 1)[0]
        command = re.search(r"^\s+- (\./gradlew .*assembleRelease)\s*$", release, re.M)
        self.assertIsNotNone(command)
        self.assertEqual(
            command.group(1),
            "./gradlew --no-daemon --max-workers=2 "
            "-Pkotlin.compiler.execution.strategy=in-process "
            "-PbatdokAcceptance=true clean assembleRelease",
        )
        self.assertIn('KUBERNETES_MEMORY_LIMIT: "8Gi"', ci)
        properties = (ROOT / "gradle.properties").read_text()
        self.assertRegex(properties, r"(?m)^org\.gradle\.jvmargs=-Xmx4096m\b")
        build = (ROOT / "app" / "build.gradle").read_text()
        release_type = build.split("minifyEnabled true", 1)[1]
        self.assertIn("shrinkResources true", release_type)
        self.assertIn("Release signing verified.", release)
        self.assertIn("verified-apk.sha256", release)

    def test_signed_trial_is_scoped_manual_and_does_not_publish(self):
        ci = (ROOT / ".gitlab-ci.yml").read_text()
        trial = ci.split("\nbatdok_signed_trial:", 1)[1].split("\nmain_release:", 1)[0]
        self.assertIn("extends: .release_build", trial)
        self.assertIn("name: battak-trial", trial)
        self.assertIn("action: prepare", trial)
        self.assertIn('CI_PIPELINE_SOURCE == "web"', trial)
        self.assertIn('CI_COMMIT_REF_PROTECTED == "true"', trial)
        self.assertIn('CI_COMMIT_BRANCH == "aegorsuch-batdok-patient-sharing"', trial)
        self.assertEqual(3, trial.count("when: manual"))
        self.assertNotIn("needs:", trial)
        self.assertNotIn("script:", trial)
        self.assertIn("when: on_success", trial)
        self.assertIn("access: developer", trial)
        publisher = ci.split("\npublish_personal_github_main:", 1)[1]
        self.assertIn("- main_release", publisher)
        self.assertNotIn("batdok_signed_trial", publisher)
        self.assertIn('$CI_PIPELINE_SOURCE == "push"', publisher)
        android = ci.split("\nandroid_mopp_check:", 1)[1].split("\nrelease_publisher_tests:", 1)[0]
        self.assertIn('CI_PIPELINE_SOURCE == "web"', android)
        self.assertIn('CI_COMMIT_REF_PROTECTED == "true"', android)


if __name__ == "__main__":
    unittest.main()
