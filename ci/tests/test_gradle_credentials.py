from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]


class GradleCredentialsTests(unittest.TestCase):
    def test_only_gradle_jobs_request_ephemeral_credentials(self):
        ci = (ROOT / ".gitlab-ci.yml").read_text()
        self.assertIn("project: devsecops/gitlab-ci-common", ci)
        self.assertIn("file: /template/artifactory_token_service.yml", ci)
        self.assertIn("\nbefore_script: []", ci)
        self.assertIn("extends: .artifactory_service_create_token_bash", ci)
        self.assertIn('ARTIFACTORY_PASSWORD="$CI_JOB_ARTIFACTORY_TOKEN"', ci)
        for job in ("android_mopp_check", "agp_resolution_diagnostic", ".release_build"):
            block = re.search(rf"(?ms)^{re.escape(job)}:\n(.*?)(?=^\S|\Z)", ci).group(1)
            self.assertIn("extends: .artifactory_credentials", block)
        release = ci.split("\n.release_build:", 1)[1].split("\nmain_release:", 1)[0]
        self.assertIn("!reference [.artifactory_credentials, before_script]", release)
        for job in ("release_publisher_tests", "publish_personal_github_main"):
            block = re.search(rf"(?ms)^{job}:\n(.*?)(?=^\S|\Z)", ci).group(1)
            self.assertNotIn(".artifactory_credentials", block)

    def test_runtime_resolver_uses_same_credential_gated_mirror(self):
        settings = (ROOT / "settings.gradle").read_text()
        self.assertEqual(4, settings.count(".find { it != null && !it.isBlank() }"))
        self.assertIn("if (takUser && takPass) {\n    gradle.ext.takArtifactoryUrl", settings)
        build = (ROOT / "app" / "build.gradle").read_text()
        self.assertIn("if (gradle.ext.has('takArtifactoryUrl'))", build)
        for prop, extra in (("id", "'takArtifactory'"), ("url", "gradle.ext.takArtifactoryUrl"),
                            ("username", "gradle.ext.takArtifactoryUser"),
                            ("password", "gradle.ext.takArtifactoryPassword")):
            self.assertIn(f"test.systemProperty 'robolectric.dependency.repo.{prop}', {extra}", build)


if __name__ == "__main__":
    unittest.main()
