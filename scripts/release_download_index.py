"""Build the public web download index from a verified Anilife release."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any


EXPECTED_ASSETS = (
    ("mobile-universal", "mobile", "universal", True, "Anilife.apk"),
    ("mobile-arm64", "mobile", "arm64-v8a", False, "Anilife_arm64-v8a.apk"),
    ("mobile-armv7", "mobile", "armeabi-v7a", False, "Anilife_armeabi-v7a.apk"),
    ("tv-universal", "tv", "universal", True, "Anilife_tv.apk"),
    ("tv-arm64", "tv", "arm64-v8a", False, "Anilife_tv_arm64-v8a.apk"),
    ("tv-armv7", "tv", "armeabi-v7a", False, "Anilife_tv_armeabi-v7a.apk"),
)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _checksums(path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        digest, separator, name = line.partition("  ")
        name = name.removeprefix("*")
        if separator and len(digest) == 64 and all(char in "0123456789abcdef" for char in digest.lower()):
            result[name] = digest.lower()
    return result


def build_index(
    release: dict[str, Any],
    event: dict[str, Any],
    checksums: dict[str, str],
    asset_dir: Path,
    repository: str,
) -> dict[str, Any]:
    if release.get("draft") or release.get("prerelease"):
        raise ValueError("web downloads may publish only a stable release")

    manifest = json.loads(event["content"])
    version = str(manifest["version"])
    version_code = int(manifest["versionCode"])
    sequence = int(manifest["sequence"])
    tag = str(release["tag_name"])
    if version_code <= 0 or sequence <= 0 or tag != f"v{version}" or sequence < version_code:
        raise ValueError("release tag and signed update manifest disagree")

    for field, length in (("id", 64), ("pubkey", 64), ("sig", 128)):
        value = str(event[field])
        if len(value) != length or any(char not in "0123456789abcdef" for char in value.lower()):
            raise ValueError(f"signed update event has invalid {field}")

    release_assets = {asset["name"]: asset for asset in release.get("assets", [])}
    assets: list[dict[str, Any]] = []
    for asset_id, form_factor, abi, recommended, name in EXPECTED_ASSETS:
        release_asset = release_assets.get(name)
        path = asset_dir / name
        digest = checksums.get(name)
        if release_asset is None or not path.is_file() or digest is None:
            raise ValueError(f"release is missing required asset {name}")
        expected_url = f"https://github.com/{repository}/releases/download/{tag}/{name}"
        size = path.stat().st_size
        if release_asset.get("browser_download_url") != expected_url:
            raise ValueError(f"unexpected download URL for {name}")
        if int(release_asset.get("size", -1)) != size:
            raise ValueError(f"release size mismatch for {name}")
        if _sha256(path) != digest:
            raise ValueError(f"checksum mismatch for {name}")
        assets.append(
            {
                "id": asset_id,
                "formFactor": form_factor,
                "abi": abi,
                "recommended": recommended,
                "name": name,
                "url": expected_url,
                "sha256": digest,
                "sizeBytes": size,
            }
        )

    return {
        "schema": 1,
        "channel": "stable",
        "version": version,
        "versionCode": version_code,
        "sequence": sequence,
        "tag": tag,
        "publishedAt": release["published_at"],
        "releaseUrl": release["html_url"],
        "updateEvent": {
            "id": str(event["id"]),
            "pubkey": str(event["pubkey"]),
            "sig": str(event["sig"]),
        },
        "assets": assets,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-json", type=Path, required=True)
    parser.add_argument("--event", type=Path, required=True)
    parser.add_argument("--checksums", type=Path, required=True)
    parser.add_argument("--asset-dir", type=Path, required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    index = build_index(
        json.loads(args.release_json.read_text(encoding="utf-8")),
        json.loads(args.event.read_text(encoding="utf-8")),
        _checksums(args.checksums),
        args.asset_dir,
        args.repository,
    )
    args.output.write_text(json.dumps(index, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
