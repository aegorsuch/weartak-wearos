"""Verify host-services AAR has no public SDK class duplicates."""

from hashlib import sha256
from io import BytesIO
import json
from pathlib import Path
import zipfile


ROOT = Path(__file__).resolve().parents[1]
SDK_AAR = ROOT / ".gradle" / "sdk"
HOST_AAR = (
    ROOT
    / "weartak-plugin-host-services-sdk"
    / "build"
    / "outputs"
    / "aar"
    / "weartak-plugin-host-services-sdk-release.aar"
)


def class_entries(aar_path):
    with zipfile.ZipFile(aar_path) as aar:
        names = [name for name in aar.namelist() if name.endswith(".jar")]
        if names.count("classes.jar") != 1:
            raise RuntimeError(f"AAR must contain exactly one classes.jar: {aar_path}")
        classes_jar = aar.read("classes.jar")
        class_names = set()
        for name in names:
            contents = classes_jar if name == "classes.jar" else aar.read(name)
            with zipfile.ZipFile(BytesIO(contents)) as classes:
                entries = {
                    entry for entry in classes.namelist() if entry.endswith(".class")
                }
            duplicates = class_names & entries
            if duplicates:
                raise RuntimeError(
                    f"AAR contains duplicate classes across jars: "
                    f"{', '.join(sorted(duplicates)[:10])}"
                )
            class_names.update(entries)
    return classes_jar, class_names


def verify_no_duplicate_classes(public_sdk_aar, host_services_aar):
    _, public_classes = class_entries(public_sdk_aar)
    host_jar, host_classes = class_entries(host_services_aar)
    duplicates = sorted(public_classes & host_classes)
    if duplicates:
        raise RuntimeError(
            "Host-services AAR duplicates public SDK classes: "
            + ", ".join(duplicates[:10])
        )
    return sha256(host_jar).hexdigest(), len(host_classes)


def main():
    pin = json.loads((ROOT / "ci" / "plugin-sdk.json").read_text(encoding="utf-8"))
    identity = f"{pin['commit'][:12]}-{pin['overlay']['source_sha256'][:12]}"
    public_sdk_aar = (
        SDK_AAR
        / identity
        / "build"
        / "outputs"
        / "aar"
        / "weartak-plugin-sdk-release.aar"
    )
    digest, class_count = verify_no_duplicate_classes(public_sdk_aar, HOST_AAR)
    checksum = HOST_AAR.with_name("verified-classes.sha256")
    checksum.write_text(f"{digest}  classes.jar\n", encoding="ascii")
    print(
        f"Verified host-services AAR: {class_count} classes, no public SDK "
        f"duplicates; classes.jar SHA-256 {digest}"
    )


if __name__ == "__main__":
    main()
