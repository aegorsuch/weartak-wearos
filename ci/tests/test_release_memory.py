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
            "-Pkotlin.compiler.execution.strategy=in-process clean assembleRelease",
        )
        self.assertIn('KUBERNETES_MEMORY_LIMIT: "8Gi"', ci)
        properties = (ROOT / "gradle.properties").read_text()
        self.assertRegex(properties, r"(?m)^org\.gradle\.jvmargs=-Xmx4096m\b")
        build = (ROOT / "app" / "build.gradle").read_text()
        release_type = build.split("minifyEnabled true", 1)[1]
        self.assertIn("shrinkResources true", release_type)
        self.assertIn("Release signing verified.", release)
        self.assertIn("verified-apk.sha256", release)


if __name__ == "__main__":
    unittest.main()
