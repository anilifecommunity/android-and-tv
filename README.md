# Anilife for Android and TV

<p align="center">
  <img src="docs/assets/anilife-logo-round-transparent.png" width="160" alt="Anilife logo">
</p>

<p align="center">
  <a href="https://github.com/anilifecommunity/android-and-tv/actions/workflows/ci.yml"><img alt="Android CI" src="https://github.com/anilifecommunity/android-and-tv/actions/workflows/ci.yml/badge.svg"></a>
  <a href="https://github.com/anilifecommunity/android-and-tv/actions/workflows/codeql.yml"><img alt="CodeQL" src="https://github.com/anilifecommunity/android-and-tv/actions/workflows/codeql.yml/badge.svg"></a>
</p>

Anilife is a community-maintained Kotlin and Jetpack Compose anime client for phones, tablets,
Android TV, Google TV, and Fire TV. The project supports Android 5.1 / Fire OS 5 (`minSdk 22`),
including 32-bit ARM devices.

> [!IMPORTANT]
> Download APKs only from this repository's GitHub Releases page. Prereleases are tester builds;
> verify the published checksums and signing information before installing them.

## Application identity

- Application ID: `io.github.anilifecommunity.anilife`
- Source namespace: `com.anilili`
- Deep links: `anilife://`
- Distribution: signed GitHub Releases; no app-store distribution

Anilife installs separately from applications using the historical `com.miruronative` package.
Those installations cannot update in place or share private app data. To migrate, export a backup,
install Anilife, review the import, and keep the previous installation until the restored data has
been verified.

## Build from source

Requirements:

- JDK 17
- Android SDK 36 and Build Tools 35.0.1
- the included Gradle 8.13 wrapper

On Windows:

```powershell
.\gradlew.bat :app:testMobileDebugUnitTest :app:testTvDebugUnitTest --max-workers=2 --no-configuration-cache
.\gradlew.bat :app:lintMobileDebug :app:lintTvDebug --max-workers=2 --no-configuration-cache
.\gradlew.bat :app:assembleMobileDebug :app:assembleTvDebug --max-workers=2 --no-configuration-cache
```

On Linux or macOS, use `./gradlew` with the same tasks. Debug builds work with every optional
integration left blank. See [developer configuration](docs/CONFIGURATION.md) for supported public
identifiers and local setup.

## Release artifacts

Each release contains six APKs:

| Form factor | Universal | ARM64 | ARMv7 |
| --- | --- | --- | --- |
| Phone/tablet | `Anilife.apk` | `Anilife_arm64-v8a.apk` | `Anilife_armeabi-v7a.apk` |
| TV | `Anilife_tv.apk` | `Anilife_tv_arm64-v8a.apk` | `Anilife_tv_armeabi-v7a.apk` |

Release bundles also include checksums, an SBOM, build provenance, signing-certificate metadata,
and R8 mapping files. The [release runbook](docs/release/runbook.md) defines the maintainer checks.

Stable updates are checked by default. Testers who install a prerelease can opt into later beta
builds in **Settings → App → Receive beta updates**; turning it off returns checks to stable
releases only. Corrective builds can retain their visible version while using a higher Android
build number, so installed users can still receive urgent fixes.

## Privacy and security

Anilife does not send analytics or usage telemetry. Diagnostic reports stay on the device unless a
user exports one, uploads one manually, or explicitly enables the off-by-default daily upload
option. Automatic reports include only events recorded after consent and are retained privately for
30 days. Optional integrations fail closed when their public identifiers are not configured.

Report security vulnerabilities privately through
[GitHub Security Advisories](https://github.com/anilifecommunity/android-and-tv/security/advisories/new).
For normal bugs, use the issue templates and remove tokens, cookies, private media URLs, account
identifiers, and personal data from logs or screenshots.

See [PRIVACY.md](PRIVACY.md), [SUPPORT.md](SUPPORT.md), and the
[compatibility guide](docs/COMPATIBILITY.md) for the current user-facing policies. Brand artwork
provenance is recorded in [docs/ASSET-LICENCES.md](docs/ASSET-LICENCES.md), and the redacted
service and release authority map is in [docs/OWNERSHIP.md](docs/OWNERSHIP.md).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for the development workflow. Current work is organized in
the [review-candidate milestone](https://github.com/anilifecommunity/android-and-tv/milestone/1)
and the [post-candidate milestone](https://github.com/anilifecommunity/android-and-tv/milestone/2).

## Licence and content boundary

Anilife is source-available under the [Anilife Source-Available License 1.0](LICENSE). Personal,
non-commercial use and contribution forks are permitted; redistribution, sale, commercial use,
independent releases, and hosted copies are not. This is not an OSI-approved open-source licence.
Earlier commits and releases keep the licence under which they were originally published.

Third-party anime, artwork, subtitles, streams, libraries, trademarks, and provider APIs remain
under their own terms. The repository licence grants no rights to that material.
