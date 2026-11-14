import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from release_download_index import EXPECTED_ASSETS, build_index


REPOSITORY = "anilifecommunity/android-and-tv"


class ReleaseDownloadIndexTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.assets = Path(self.temporary.name)
        self.checksums: dict[str, str] = {}
        release_assets = []
        for _, _, _, _, name in EXPECTED_ASSETS:
            body = f"signed-{name}".encode()
            (self.assets / name).write_bytes(body)
            self.checksums[name] = hashlib.sha256(body).hexdigest()
            release_assets.append(
                {
                    "name": name,
                    "size": len(body),
                    "browser_download_url":
                        f"https://github.com/{REPOSITORY}/releases/download/v0.1.2/{name}",
                }
            )
        self.release = {
            "draft": False,
            "prerelease": False,
            "tag_name": "v0.1.2",
            "published_at": "2026-11-14T18:24:23Z",
            "html_url": f"https://github.com/{REPOSITORY}/releases/tag/v0.1.2",
            "assets": release_assets,
        }
        self.event = {
            "id": "1" * 64,
            "pubkey": "2" * 64,
            "sig": "3" * 128,
            "content": json.dumps({"version": "0.1.2", "versionCode": 5, "sequence": 5}),
        }

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def build(self):
        return build_index(self.release, self.event, self.checksums, self.assets, REPOSITORY)

    def test_builds_exact_six_asset_stable_index(self):
        index = self.build()

        self.assertEqual(("stable", "0.1.2", 5), (index["channel"], index["version"], index["sequence"]))
        self.assertEqual(6, len(index["assets"]))
        self.assertEqual(
            {"mobile-universal", "tv-universal"},
            {asset["id"] for asset in index["assets"] if asset["recommended"]},
        )

    def test_rejects_prerelease_missing_asset_and_wrong_url(self):
        self.release["prerelease"] = True
        with self.assertRaisesRegex(ValueError, "stable release"):
            self.build()
        self.release["prerelease"] = False

        missing = self.release["assets"].pop()
        with self.assertRaisesRegex(ValueError, "missing required asset"):
            self.build()
        self.release["assets"].append(missing)

        self.release["assets"][0]["browser_download_url"] = "https://example.invalid/Anilife.apk"
        with self.assertRaisesRegex(ValueError, "unexpected download URL"):
            self.build()

    def test_rejects_checksum_and_manifest_disagreement(self):
        self.checksums["Anilife.apk"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            self.build()
        self.checksums["Anilife.apk"] = hashlib.sha256(b"signed-Anilife.apk").hexdigest()

        self.event["content"] = json.dumps({"version": "0.1.1", "versionCode": 5, "sequence": 4})
        with self.assertRaisesRegex(ValueError, "disagree"):
            self.build()

    def test_rejects_malformed_signed_event(self):
        self.event["id"] = "not-a-valid-event-id"
        with self.assertRaisesRegex(ValueError, "invalid id"):
            self.build()


if __name__ == "__main__":
    unittest.main()
