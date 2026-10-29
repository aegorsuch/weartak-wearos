import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import urllib.error
import urllib.parse
import urllib.request


SPEC = importlib.util.spec_from_file_location(
    "publisher", Path(__file__).resolve().parents[1] / "publish_github_release.py"
)
p = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(p)


def environment():
    return {
        "CI_SERVER_HOST": "git.tak.gov",
        "CI_PROJECT_PATH": "core/weartak-core/wearos-tak-civ",
        "CI_COMMIT_BRANCH": "main",
        "CI_COMMIT_REF_PROTECTED": "true",
        "CI_PIPELINE_SOURCE": "push",
        "WEARTAK_GITHUB_TOKEN": "mock-token",
        "CI_PROJECT_ID": "42",
        "CI_PIPELINE_ID": "456",
        "CI_PIPELINE_IID": "12",
        "CI_PIPELINE_URL": "https://git.tak.gov/core/weartak-core/wearos-tak-civ/-/pipelines/456",
        "CI_COMMIT_SHA": "e" * 40,
    }


def asset(asset_id, iid, content=b"old apk"):
    name = f"WearTAK-5.8.0.3-p{iid}.apk"
    return {"id": asset_id, "name": name, "label": name, "state": "uploaded", "size": len(content)}


def legacy_asset(asset_id, iid, content=b"old apk"):
    digest = hashlib.sha256(content).hexdigest()
    return {
        "id": asset_id, "name": f"weartak-development-p{iid}-{digest}.apk",
        "label": f"{p.LABEL_PREFIX}{digest}", "state": "uploaded", "size": len(content),
    }


class FakeGitHub:
    """In-memory GitHub fixture; records writes and never opens a network connection."""

    def __init__(self, state=None):
        self.calls = []
        self.tag = True
        self.repository = {"full_name": p.REPO, "private": True, "archived": False, "disabled": False}
        self.release = None
        self.items = []
        self.content = {}
        self.bad_download = False
        self.fail = None
        self.dispatches = []
        self.synced_sources = []
        self.sync_failure = False
        if state:
            self.release = {
                "id": 1, "tag_name": p.TAG, "draft": False, "prerelease": False,
                "name": p.TITLE, "body": f"<!-- {p.MARKER} {json.dumps(state)} -->",
            }

    def request(self, method, path, data=None, **kwargs):
        self.calls.append((method, path, data))
        if self.fail and self.fail(method, path):
            raise p.PublishError("Injected API failure")
        if method == "GET" and path == "":
            return self.repository
        if path == f"git/ref/tags/{p.TAG}":
            return {"ref": f"refs/tags/{p.TAG}"} if self.tag else None
        if path == f"releases/tags/{p.TAG}":
            return copy.deepcopy(self.release)
        if method == "POST" and path == "releases":
            self.release = dict(data, id=1)
            return copy.deepcopy(self.release)
        if method == "POST" and path.startswith("releases/1/assets?"):
            query = urllib.parse.parse_qs(urllib.parse.urlsplit(path).query)
            item = {
                "id": 100, "name": query["name"][0], "label": query["label"][0],
                "state": "uploaded", "size": len(data),
            }
            self.items.append(item)
            self.content[100] = data
            return copy.deepcopy(item)
        if method == "PATCH" and path == "releases/1":
            self.release.update(data)
            return copy.deepcopy(self.release)
        if method == "GET" and path == "releases/1":
            return copy.deepcopy(self.release)
        if method == "POST" and path == "dispatches":
            self.dispatches.append(data)
            return None
        if method == "DELETE" and path.startswith("releases/assets/"):
            self.items = [a for a in self.items if a["id"] != int(path.rsplit("/", 1)[1])]
            return None
        raise AssertionError(f"Unexpected request: {method} {path}")

    def assets(self, release_id):
        assert release_id == 1
        return copy.deepcopy(self.items)

    def download_digest(self, asset_id):
        self.calls.append(("DOWNLOAD", str(asset_id), None))
        content = self.content[asset_id]
        return len(content), "bad" if self.bad_download else hashlib.sha256(content).hexdigest()

    def sync_main(self, source_sha):
        self.calls.append(("SYNC", source_sha, None))
        if self.sync_failure:
            raise p.PublishError("Injected non-fast-forward source failure")
        self.synced_sources.append(source_sha)


class PublisherTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        output = self.root / p.OUTPUT
        output.mkdir(parents=True)
        self.apk = output / "WearTAK-release.apk"
        self.apk.write_bytes(b"signed apk fixture")
        self.digest = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        (output / "verified-apk.sha256").write_text(
            f"{self.digest}  {self.apk.relative_to(self.root).as_posix()}\n"
        )
        self.metadata = {
            "applicationId": "com.aitech.weartak_civ", "variantName": "release",
            "elements": [{
                "versionCode": 583, "versionName": "5.8.0.3", "outputFile": self.apk.name,
            }],
        }
        self.write_metadata()
        self.state = p.configuration(environment())

    def write_metadata(self):
        (self.root / p.OUTPUT / "output-metadata.json").write_text(json.dumps(self.metadata))

    def publish(self, api):
        p.publish(api, self.state, *p.artifact(self.root))

    def assert_no_writes(self, api):
        self.assertFalse([c for c in api.calls if c[0] != "GET"])

    def test_initial_release_is_draft_until_verified_then_stable(self):
        api = FakeGitHub()
        self.publish(api)
        create = next(c[2] for c in api.calls if c[:2] == ("POST", "releases"))
        self.assertTrue(create["draft"])
        self.assertFalse(create["prerelease"])
        self.assertFalse(api.release["draft"])
        self.assertFalse(api.release["prerelease"])
        self.assertEqual(api.release["name"], "TAK.gov Forge CI/CD Pipeline Release (5.8.0.3-p12)")
        self.assertEqual(api.release["tag_name"], "TAK.gov-Forge-CI-CD-Pipeline-Release")
        self.assertEqual([(a["name"], a["label"]) for a in api.items],
                         [("WearTAK-5.8.0.3-p12.apk", "WearTAK-5.8.0.3-p12.apk")])
        self.assertEqual(api.release["make_latest"], "false")
        body = api.release["body"]
        for value in ("5.8.0.3", "583", self.digest, "e" * 40, self.state["pipeline_url"]):
            self.assertIn(value, body)
        methods = [c[0] for c in api.calls]
        self.assertLess(methods.index("DOWNLOAD"), methods.index("PATCH"))
        self.assertEqual(api.synced_sources, [])

    def personal_api(self, state=None):
        api = FakeGitHub(state)
        api.repository.update(full_name=p.PERSONAL_REPO, private=False)
        return api

    def test_personal_uses_same_verified_artifact_after_exact_source_sync_without_dispatch(self):
        api = self.personal_api()
        p.publish(api, self.state, *p.artifact(self.root), destination=p.PERSONAL_REPO)
        self.assertEqual(api.synced_sources, [self.state["source_sha"]])
        self.assertEqual(api.download_digest(100), (self.apk.stat().st_size, self.digest))
        self.assertEqual(api.items[0]["name"], "WearTAK-5.8.0.3-p12.apk")
        self.assertEqual(p.state_from(api.release, self.state["project_id"])["sha256"], self.digest)
        self.assertEqual(api.dispatches, [])
        methods = [call[0] for call in api.calls]
        self.assertLess(methods.index("SYNC"), methods.index("POST"))

    def test_personal_requires_public_exact_destination_before_source_or_asset_writes(self):
        for change in ({"private": True}, {"private": None}, {"full_name": p.REPO},
                       {"archived": True}, {"disabled": True}):
            api = self.personal_api()
            api.repository.update(change)
            with self.assertRaises(p.PublishError):
                p.publish(api, self.state, *p.artifact(self.root), destination=p.PERSONAL_REPO)
            self.assert_no_writes(api)

    def test_personal_older_pipeline_never_rewinds_source_or_release(self):
        api = self.personal_api(dict(self.state, pipeline_iid=13))
        p.publish(api, self.state, *p.artifact(self.root), destination=p.PERSONAL_REPO)
        self.assert_no_writes(api)
        self.assertEqual(api.synced_sources, [])

    def test_personal_source_failure_preserves_previous_usable_release_and_assets(self):
        api = self.personal_api(dict(self.state, pipeline_iid=11))
        api.items = [asset(9, 11)]
        api.sync_failure = True
        previous = copy.deepcopy(api.release)
        with self.assertRaises(p.PublishError):
            p.publish(api, self.state, *p.artifact(self.root), destination=p.PERSONAL_REPO)
        self.assertEqual(api.release, previous)
        self.assertEqual(api.items, [asset(9, 11)])
        self.assertFalse(any(call[0] in ("POST", "PATCH", "DELETE") for call in api.calls))

    def test_personal_has_no_company_token_fallback_or_arbitrary_destination(self):
        env = environment()
        with self.assertRaisesRegex(p.PublishError, "WEARTAK_PERSONAL_GITHUB_TOKEN"):
            p.configuration(env, p.PERSONAL_REPO)
        env.pop("WEARTAK_GITHUB_TOKEN")
        env["WEARTAK_PERSONAL_GITHUB_TOKEN"] = "mock-personal-token"
        self.assertEqual(p.configuration(env, p.PERSONAL_REPO), self.state)
        for destination in ("other/repository", "https://github.com/" + p.PERSONAL_REPO):
            with self.assertRaises(p.PublishError):
                p.configuration(env, destination)
            with self.assertRaises(p.PublishError):
                p.GitHub("mock-personal-token", destination)
        with patch.dict(p.os.environ, env, clear=True), patch.object(p.Path, "cwd", return_value=self.root), \
                patch.object(p, "GitHub") as github, patch.object(p, "publish") as publish:
            self.assertEqual(p.main(["--personal"]), 0)
            github.assert_called_once_with("mock-personal-token", p.PERSONAL_REPO)
            self.assertEqual(publish.call_args.kwargs["destination"], p.PERSONAL_REPO)

    def test_verified_release_notifies_testers_after_cleanup(self):
        api = FakeGitHub(self.state | {"pipeline_iid": 11, "pipeline_id": "455"})
        api.items = [asset(9, 11)]
        self.publish(api)
        self.assertEqual(api.dispatches, [{
            "event_type": p.DISPATCH_EVENT,
            "client_payload": {"pipeline_id": "456", "release_id": 1},
        }])
        writes = [c for c in api.calls if c[0] != "GET"]
        self.assertEqual(writes[-1][:2], ("POST", "dispatches"))
        self.assertIn(("DELETE", "releases/assets/9"), [c[:2] for c in writes])

    def test_manual_prose_survives_legacy_migration_and_repeated_updates(self):
        api = FakeGitHub()
        self.publish(api)
        legacy = api.release["body"].replace("<!-- weartak-release-managed:start -->\n", "").replace(
            "\n<!-- weartak-release-managed:end -->", "")
        before = "Manual introduction.\n\n"
        after = "\n\nManual install notes.\n"
        api.release["body"] = before + legacy + after
        self.publish(api)
        body = api.release["body"]
        self.assertTrue(body.startswith(before))
        self.assertTrue(body.endswith(after))
        self.assertEqual(body.count("Automatically published stable"), 1)
        self.assertEqual(body.count("<!-- weartak-release-managed:start -->"), 1)
        self.publish(api)
        self.assertEqual(api.release["body"], body)
        self.state = dict(self.state, pipeline_iid=13, pipeline_id="457")
        self.publish(api)
        self.assertTrue(api.release["body"].startswith(before))
        self.assertTrue(api.release["body"].endswith(after))
        self.assertIn("project pipeline **13**", api.release["body"])
        self.assertNotIn("project pipeline **12**", api.release["body"])

    def test_unknown_legacy_notes_are_not_assumed_generated(self):
        api = FakeGitHub(dict(self.state, pipeline_iid=11))
        unknown = "Unknown old instructions.\n"
        api.release["body"] = unknown + api.release["body"] + "\nOperator notes."
        self.publish(api)
        self.assertTrue(api.release["body"].startswith(unknown))
        self.assertTrue(api.release["body"].endswith("\nOperator notes."))
        self.assertEqual(api.release["body"].count(p.MARKER + " "), 1)

    def test_edited_legacy_generated_text_is_retained_without_guessing_ownership(self):
        api = FakeGitHub()
        self.publish(api)
        legacy = api.release["body"].replace("<!-- weartak-release-managed:start -->\n", "").replace(
            "\n<!-- weartak-release-managed:end -->", "")
        legacy = legacy.replace("Install the APK above.", "Operator edited these instructions.")
        api.release["body"] = legacy
        self.publish(api)
        self.assertIn("Operator edited these instructions.", api.release["body"])
        self.assertTrue(api.release["body"].startswith("Automatically published stable"))
        self.assertEqual(api.release["body"].count(p.MARKER + " "), 1)

    def test_unconfirmed_manual_notes_prevent_old_asset_cleanup(self):
        api = FakeGitHub()
        self.publish(api)
        api.release["body"] += "\nManual notes."
        self.state = dict(self.state, pipeline_iid=13)
        api.items.append(asset(9, 11))
        original_request = api.request

        def corrupt_readback(method, path, data=None, **kwargs):
            result = original_request(method, path, data, **kwargs)
            if method == "GET" and path == "releases/1":
                result["body"] = result["body"].replace("\nManual notes.", "")
            return result

        api.request = corrupt_readback
        with self.assertRaisesRegex(p.PublishError, "could not be verified"):
            self.publish(api)
        self.assertTrue(any(item["id"] == 9 for item in api.items))

    def test_ambiguous_managed_boundaries_fail_before_upload_or_cleanup(self):
        for body in (
            "<!-- weartak-release-managed:start -->\n{marker}",
            "{marker}\n<!-- weartak-release-managed:end -->",
            "<!-- weartak-release-managed:start -->{marker}"
            "<!-- weartak-release-managed:start --><!-- weartak-release-managed:end -->",
            "<!-- weartak-release-managed:end -->{marker}<!-- weartak-release-managed:start -->",
        ):
            api = FakeGitHub(dict(self.state, pipeline_iid=11))
            api.release["body"] = body.format(marker=api.release["body"])
            with self.assertRaises(p.PublishError):
                self.publish(api)
            self.assert_no_writes(api)

    def test_unverified_or_skipped_release_does_not_notify_testers(self):
        api = FakeGitHub()
        api.bad_download = True
        with self.assertRaises(p.PublishError):
            self.publish(api)
        self.assertEqual(api.dispatches, [])
        newer = FakeGitHub(self.state | {"pipeline_iid": 13, "pipeline_id": "457"})
        self.publish(newer)
        self.assertEqual(newer.dispatches, [])

    def test_notification_failure_is_reported_and_retry_notifies_once(self):
        api = FakeGitHub()
        api.fail = lambda method, path: path == "dispatches"
        with self.assertRaisesRegex(p.PublishError, "release-testing notification failed"):
            self.publish(api)
        self.assertFalse(api.release["draft"])
        api.fail = None
        self.publish(api)
        self.assertEqual(len(api.dispatches), 1)

    def test_only_older_owned_assets_deleted_after_verification(self):
        api = FakeGitHub(dict(self.state, pipeline_iid=11))
        old = asset(2, 11)
        unrelated = dict(asset(3, 10), label=None)
        future = asset(4, 15)
        notes = {"id": 5, "name": "release-notes.txt", "label": None}
        api.items = [old, unrelated, future, notes]
        self.publish(api)
        self.assertEqual({a["id"] for a in api.items}, {3, 4, 5, 100})
        methods = [c[0] for c in api.calls]
        self.assertLess(methods.index("PATCH"), methods.index("DELETE"))
        self.assertTrue(all("tags/" not in c[1] for c in api.calls if c[0] != "GET"))
        self.assertFalse(any(c[:2] == ("DELETE", "releases/1") for c in api.calls))

    def test_older_pipeline_cannot_replace_newer(self):
        api = FakeGitHub(dict(self.state, pipeline_iid=13))
        self.publish(api)
        self.assert_no_writes(api)

    def test_pipeline_identity_and_hash_must_match_on_retry(self):
        for change in ({"source_sha": "f" * 40}, {"sha256": "0" * 64}):
            api = FakeGitHub({**self.state, "sha256": self.digest, **change})
            with self.assertRaises(p.PublishError):
                self.publish(api)
            self.assert_no_writes(api)

    def test_unmanaged_or_foreign_release_never_modified(self):
        for change in ({"body": "Human release"}, {"prerelease": True},
                       {"body": f'<!-- {p.MARKER} {{"project_id":"other","pipeline_iid":1}} -->'}):
            api = FakeGitHub(self.state)
            api.release.update(change)
            with self.assertRaises(p.PublishError):
                self.publish(api)
            self.assert_no_writes(api)

    def test_missing_anchor_does_not_create_tag_or_release(self):
        api = FakeGitHub()
        api.tag = False
        with self.assertRaisesRegex(p.PublishError, "Pre-create"):
            self.publish(api)
        self.assert_no_writes(api)

    def test_explicitly_approved_public_destination_can_publish(self):
        api = FakeGitHub()
        api.repository["private"] = False
        self.publish(api)
        self.assertFalse(api.release["draft"])
        self.assertEqual(api.release["name"], f"{p.TITLE} (5.8.0.3-p12)")

    def test_stamped_version_name_is_shortened_for_title_and_asset(self):
        self.metadata["elements"][0].update(versionName="5.8.0.3-p12+de139f69", versionCode=5830012)
        self.write_metadata()
        api = FakeGitHub()
        self.publish(api)
        self.assertEqual(api.release["name"], "TAK.gov Forge CI/CD Pipeline Release (5.8.0.3-p12)")
        self.assertEqual(api.items[0]["name"], "WearTAK-5.8.0.3-p12.apk")
        self.assertIn("5.8.0.3-p12+de139f69", api.release["body"])

    def test_legacy_named_assets_are_cleaned_up(self):
        api = FakeGitHub(dict(self.state, pipeline_iid=11))
        api.items = [legacy_asset(2, 11), dict(legacy_asset(3, 10), label=None)]
        self.publish(api)
        self.assertEqual({a["id"] for a in api.items}, {3, 100})

    def test_other_archived_disabled_or_unknown_destination_fails_without_writes(self):
        for change in ({"full_name": "other/repository"}, {"full_name": None},
                       {"archived": True}, {"disabled": True},
                       {"archived": None}, {"disabled": None}):
            api = FakeGitHub()
            api.repository.update(change)
            with self.assertRaisesRegex(p.PublishError, "explicitly approved, active"):
                self.publish(api)
            self.assert_no_writes(api)

    def test_failed_download_keeps_previous_release_and_assets(self):
        api = FakeGitHub(dict(self.state, pipeline_iid=11))
        old = asset(2, 11)
        api.items = [old]
        previous = copy.deepcopy(api.release)
        api.bad_download = True
        with self.assertRaisesRegex(p.PublishError, "verification"):
            self.publish(api)
        self.assertEqual(api.release, previous)
        self.assertIn(old, api.items)
        self.assertFalse(any(c[0] in ("PATCH", "DELETE") for c in api.calls))

    def test_failed_first_upload_leaves_draft(self):
        api = FakeGitHub()
        api.bad_download = True
        with self.assertRaises(p.PublishError):
            self.publish(api)
        self.assertTrue(api.release["draft"])
        self.assertFalse(any(c[0] in ("PATCH", "DELETE") for c in api.calls))

    def test_unconfirmed_metadata_does_not_allow_cleanup(self):
        api = FakeGitHub(dict(self.state, pipeline_iid=11))
        api.items = [asset(2, 11)]
        original_request = api.request

        def request(method, path, *args, **kwargs):
            result = original_request(method, path, *args, **kwargs)
            if method == "GET" and path == "releases/1":
                result["draft"] = True
            return result

        api.request = request
        with self.assertRaisesRegex(p.PublishError, "could not be verified"):
            self.publish(api)
        self.assertFalse(any(c[0] == "DELETE" for c in api.calls))

    def test_upload_metadata_update_or_confirmation_failure_preserves_old_asset(self):
        for method, prefix in (("POST", "releases/1/assets?"),
                               ("PATCH", "releases/1"), ("GET", "releases/1")):
            api = FakeGitHub(dict(self.state, pipeline_iid=11))
            old = asset(2, 11)
            api.items = [old]
            api.fail = lambda m, path: m == method and path.startswith(prefix)
            with self.assertRaises(p.PublishError):
                self.publish(api)
            self.assertIn(old, api.items)
            self.assertFalse(any(c[0] == "DELETE" for c in api.calls))

    def test_idempotent_retry_reuses_verified_asset(self):
        api = FakeGitHub()
        self.publish(api)
        api.calls.clear()
        self.publish(api)
        self.assertEqual(len(api.items), 1)
        self.assertFalse(any(c[0] == "POST" and c[1] != "dispatches" for c in api.calls))

    def test_cleanup_failure_is_reported_and_retry_can_finish(self):
        api = FakeGitHub(dict(self.state, pipeline_iid=11))
        api.items = [asset(2, 11)]
        api.fail = lambda method, path: method == "DELETE"
        with self.assertRaises(p.PublishError):
            self.publish(api)
        self.assertEqual(len(api.items), 2)
        api.fail = None
        self.publish(api)
        self.assertEqual(len(api.items), 1)

    def test_bad_uploaded_size_or_state_never_deletes(self):
        for change in ({"size": 0}, {"state": "starter"}):
            api = FakeGitHub(dict(self.state, pipeline_iid=11))
            item = asset(100, 12, self.apk.read_bytes())
            item.update(change)
            api.items = [asset(2, 11), item]
            with self.assertRaises(p.PublishError):
                self.publish(api)
            self.assertFalse(any(c[0] in ("PATCH", "DELETE") for c in api.calls))

    def test_unrelated_name_collision_is_not_overwritten(self):
        api = FakeGitHub()
        item = dict(asset(100, 12, self.apk.read_bytes()), label="human")
        api.items = [item]
        with self.assertRaisesRegex(p.PublishError, "collision"):
            self.publish(api)
        self.assertIn(item, api.items)

    def test_tampered_missing_or_multiple_apks_are_rejected(self):
        self.apk.write_bytes(b"tampered")
        with self.assertRaisesRegex(p.PublishError, "checksum"):
            p.artifact(self.root)
        self.apk.unlink()
        with self.assertRaisesRegex(p.PublishError, "exactly one"):
            p.artifact(self.root)
        self.apk.write_bytes(b"one")
        self.apk.with_name("second.apk").write_bytes(b"two")
        with self.assertRaisesRegex(p.PublishError, "exactly one"):
            p.artifact(self.root)

    def test_debug_or_wrong_output_metadata_is_rejected(self):
        for key, value in (("applicationId", "com.aitech.weartak_civ.dev"),
                           ("variantName", "debug")):
            original = copy.deepcopy(self.metadata)
            self.metadata[key] = value
            self.write_metadata()
            with self.assertRaises(p.PublishError):
                p.artifact(self.root)
            self.metadata = original
        self.metadata["elements"][0]["outputFile"] = "unrelated.apk"
        self.write_metadata()
        with self.assertRaises(p.PublishError):
            p.artifact(self.root)

    def test_required_configuration_and_protected_source_gates(self):
        env = environment()
        for key in env:
            with self.subTest(key=key):
                invalid = dict(env)
                invalid.pop(key)
                with self.assertRaises(p.PublishError):
                    p.configuration(invalid)
        for key, value in (("CI_PIPELINE_SOURCE", "merge_request_event"),
                           ("CI_COMMIT_BRANCH", "develop"),
                           ("CI_COMMIT_REF_PROTECTED", "false"),
                           ("CI_PROJECT_PATH", "fork/project"),
                           ("CI_SERVER_HOST", "other.example"),
                           ("CI_PIPELINE_IID", "bad"), ("CI_COMMIT_SHA", "short"),
                           ("CI_PIPELINE_URL", "https://other.example/pipeline")):
            with self.assertRaises(p.PublishError):
                p.configuration(dict(env, **{key: value}))

    def test_redirect_strips_token_and_rejects_http(self):
        req = urllib.request.Request(
            "https://api.github.com/asset",
            headers={"Authorization": "Bearer mock-token"},
        )
        handler = p.SafeRedirect()
        redirected = handler.redirect_request(
            req, None, 302, "Found", {}, "https://storage.example/asset"
        )
        self.assertIsNone(redirected.get_header("Authorization"))
        with self.assertRaises(p.PublishError):
            handler.redirect_request(req, None, 302, "Found", {}, "http://storage.example/asset")

    def test_asset_pagination(self):
        api = p.GitHub("mock-token")
        with patch.object(api, "request", side_effect=[[{"id": i} for i in range(100)], []]) as call:
            self.assertEqual(len(api.assets(1)), 100)
            self.assertEqual(call.call_args.args[1], "releases/1/assets?per_page=100&page=2")

    def test_api_request_hosts_headers_and_payloads(self):
        api = p.GitHub("mock-token")
        for binary in (False, True):
            response = io.BytesIO(b'{"id":100}')
            data = b"apk bytes" if binary else {"tag_name": p.TAG, "draft": True}
            with patch.object(api.opener, "open", return_value=response) as call:
                self.assertEqual(api.request("POST", "releases", data, binary=binary), {"id": 100})
                request = call.call_args.args[0]
                expected_host = "uploads.github.com" if binary else "api.github.com"
                self.assertEqual(urllib.parse.urlsplit(request.full_url).netloc, expected_host)
                self.assertEqual(request.get_header("Authorization"), "Bearer mock-token")
                self.assertEqual(request.data, data if binary else json.dumps(data).encode())
                self.assertEqual(
                    request.get_header("Content-type"),
                    "application/vnd.android.package-archive" if binary else "application/json",
                )

    def test_api_404_only_optional_reads_may_return_none(self):
        api = p.GitHub("mock-token")
        error = urllib.error.HTTPError("https://example", 404, "not found", {}, None)
        with patch.object(api.opener, "open", side_effect=error):
            self.assertIsNone(api.request("GET", "git/ref/tags/missing", missing_ok=True))
            with self.assertRaisesRegex(p.PublishError, "HTTP 404"):
                api.request("POST", "releases", {"draft": True})

    def test_download_stream_hashes_exact_bytes(self):
        api = p.GitHub("mock-token")
        with patch.object(api.opener, "open", return_value=io.BytesIO(b"apk bytes")):
            self.assertEqual(
                api.download_digest(100), (9, hashlib.sha256(b"apk bytes").hexdigest())
            )

    def test_network_diagnostics_do_not_echo_sensitive_transport_details(self):
        api = p.GitHub("mock-token")
        error = urllib.error.URLError("private-signed-url-secret")
        with patch.object(api.opener, "open", side_effect=error):
            for action in (lambda: api.request("GET", ""), lambda: api.download_digest(100)):
                with self.assertRaises(p.PublishError) as caught:
                    action()
                self.assertNotIn("private-signed-url-secret", str(caught.exception))

    def test_missing_configuration_fails_without_network(self):
        with patch.dict(p.os.environ, {}, clear=True), patch.object(p, "GitHub") as github:
            self.assertEqual(p.main(), 1)
            github.assert_not_called()


class SourceSyncTests(unittest.TestCase):
    source = "e" * 40
    previous = "a" * 40

    def api(self, readback=None):
        api = p.GitHub("mock-personal-token", p.PERSONAL_REPO)
        references = [
            {"object": {"type": "commit", "sha": self.previous}},
            {"object": {"type": "commit", "sha": readback or self.source}},
        ]
        api.request = unittest.mock.Mock(side_effect=references)
        return api

    def result(self, stdout="", returncode=0):
        return p.subprocess.CompletedProcess([], returncode, stdout, "sensitive transport detail")

    def results(self):
        return [self.result(self.source), self.result(), self.result(self.previous),
                self.result(), self.result()]

    def test_exact_non_force_main_only_with_secret_safe_scoped_git_environment(self):
        api = self.api()
        with patch.dict(p.os.environ, {"GH_TOKEN": "unrelated", "RELEASE_STORE_PASSWORD": "unrelated",
                                     "WEARTAK_GITHUB_TOKEN": "company-only"}, clear=False), \
                patch.object(p.subprocess, "run", side_effect=self.results()) as run:
            api.sync_main(self.source)
        commands = [call.args[0] for call in run.call_args_list]
        self.assertEqual(commands[-1], [
            "git", "push", "--no-follow-tags", "--recurse-submodules=no",
            f"https://github.com/{p.PERSONAL_REPO}.git", f"{self.source}:refs/heads/main",
        ])
        self.assertIn(["git", "merge-base", "--is-ancestor", self.previous, self.source], commands)
        for command in commands:
            self.assertFalse(any(flag in command for flag in ("--force", "-f", "--mirror", "--delete", "--tags")))
            self.assertNotIn("mock-personal-token", repr(command))
        env = run.call_args.kwargs["env"]
        self.assertFalse(any(key in env for key in (
            "GH_TOKEN", "WEARTAK_GITHUB_TOKEN", "WEARTAK_PERSONAL_GITHUB_TOKEN", "RELEASE_STORE_PASSWORD")))
        self.assertEqual(env["GIT_TERMINAL_PROMPT"], "0")
        self.assertEqual(env["GIT_CONFIG_KEY_2"],
                         f"http.https://github.com/{p.PERSONAL_REPO}.git.extraHeader")
        self.assertEqual(env["GIT_CONFIG_VALUE_3"], "false")

    def test_already_exact_source_requires_readback_but_no_fetch_or_push(self):
        api = self.api()
        api.request.side_effect = [{"object": {"type": "commit", "sha": self.source}}] * 2
        with patch.object(p.subprocess, "run", return_value=self.result(self.source)) as run:
            api.sync_main(self.source)
        self.assertEqual(run.call_count, 1)
        self.assertEqual(api.request.call_count, 2)

    def test_wrong_checkout_or_changed_inspection_never_pushes(self):
        for results in ([self.result("b" * 40)],
                        [self.result(self.source), self.result(), self.result("b" * 40)]):
            api = self.api()
            with patch.object(p.subprocess, "run", side_effect=results) as run:
                with self.assertRaises(p.PublishError):
                    api.sync_main(self.source)
            self.assertFalse(any(call.args[0][1] == "push" for call in run.call_args_list))

    def test_divergent_history_and_push_race_fail_without_force_retry_or_secret_output(self):
        for failing_step in (3, 4):
            api = self.api()
            results = self.results()
            results[failing_step] = self.result(returncode=1)
            with patch.object(p.subprocess, "run", side_effect=results) as run:
                with self.assertRaises(p.PublishError) as caught:
                    api.sync_main(self.source)
            self.assertNotIn("sensitive transport detail", str(caught.exception))
            self.assertEqual(run.call_count, failing_step + 1)
            self.assertFalse(any("--force" in call.args[0] for call in run.call_args_list))

    def test_unexpected_readback_is_reported_not_rolled_back(self):
        api = self.api(readback="b" * 40)
        with patch.object(p.subprocess, "run", side_effect=self.results()) as run:
            with self.assertRaisesRegex(p.PublishError, "readback"):
                api.sync_main(self.source)
        self.assertEqual(run.call_count, 5)

    def test_company_source_sync_is_never_enabled(self):
        with self.assertRaises(p.PublishError), patch.object(p.subprocess, "run") as run:
            p.GitHub("mock-company-token").sync_main(self.source)
        run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
