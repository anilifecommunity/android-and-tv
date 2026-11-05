# Anilife for Android and TV

<p align="center">
  <img src="docs/assets/icon.png" width="160" alt="Anilife AL play-mark logo">
</p>

<p align="center">
  <a href="https://github.com/anilifecommunity/android-and-tv/actions/workflows/ci.yml"><img alt="Android CI" src="https://github.com/anilifecommunity/android-and-tv/actions/workflows/ci.yml/badge.svg"></a>
  <a href="https://github.com/anilifecommunity/android-and-tv/actions/workflows/codeql.yml"><img alt="CodeQL" src="https://github.com/anilifecommunity/android-and-tv/actions/workflows/codeql.yml/badge.svg"></a>
</p>

Anilife is a community-maintained native Kotlin and Jetpack Compose anime client for phones,
tablets, Android TV, Google TV, and Fire TV. One codebase produces `mobile` and `tv` flavours while
preserving Android 5.1 / Fire OS 5 (`minSdk 22`) and 32-bit ARM support.

> [!IMPORTANT]
> The Anilife rebrand and ownership migration are in progress. There is no approved public Anilife
> release yet. Do not download APKs claiming to be official unless they appear in this repository's
> GitHub Releases and pass the documented signing and checksum checks.

## Current release identity

- Product: **Anilife**
- Canonical repository: <https://github.com/anilifecommunity/android-and-tv>
- Android application ID: `io.github.anilifecommunity.anilife`
- Source namespace: `com.anilili` (retained to avoid a high-risk internal rename)
- Deep links: `anilife://`
- Distribution: signed GitHub Releases only; no app stores

Anilife is a new signing and install identity. Historical `com.miruronative` installations do not
update in place and cannot share private app data. Migration is user-controlled: export a backup
from the old app, install Anilife separately, review the import, then restore it. Never uninstall
the old app until the new installation and backup have been verified.

## Build from source

Requirements:

- JDK 17
- Android SDK 36 and Build Tools 35.0.1
- the included Gradle 8.13 wrapper

On Windows:

```powershell
.\gradlew.bat :app:testMobileDebugUnitTest :app:testTvDebugUnitTest
.\gradlew.bat :app:lintMobileDebug :app:lintTvDebug
.\gradlew.bat :app:assembleMobileDebug :app:assembleTvDebug
```

On Linux or macOS, use `./gradlew` with the same tasks. CI runs mobile and TV jobs independently
with bounded memory and worker counts.

Debug builds do not require third-party credentials. Optional integrations remain disabled until
their Anilife-owned public identifiers are configured in the ignored `.env.infrastructure.local`
file or equivalent Gradle/environment values:

- `ANILIST_CLIENT_ID`
- `MAL_CLIENT_ID`
- `SIMKL_CLIENT_ID`
- `DISCORD_APPLICATION_ID`
- `OPEN_SUBTITLES_API_KEY` (optional; extractable from the APK)
- `KARTOONS_ACCOUNT_TOKENS` (optional; comma-separated and extractable from the APK)
- `KARTOONS_TOKENS_MIRROR_URL` (optional authorised refresh document)
- `RIFT_FIREBASE_API_KEY` (optional; authorised AnimeRift Android client key)
- `TMDB_READ_TOKEN` (server-side Media Index work only; do not ship in Android)

Do not add OAuth client secrets, Discord bot tokens, provider user credentials, APK signing
material, or update-signing private keys to source control.

Kartoons is retained, including its Hindi provider ordering and token failover. The inherited shared
account tokens are not recommitted because they are exposed credentials. A local build can enable
the provider with accounts whose owners explicitly permit distribution; blank values fail closed.
Rift is likewise retained, but its inherited third-party Firebase key is not distributed; an
authorised replacement is required to enable that provider.

## Release artifacts

The release pipeline must produce exactly these six APKs:

| Form | Universal | ARM64 | ARMv7 |
| --- | --- | --- | --- |
| Phone/tablet | `Anilife.apk` | `Anilife_arm64-v8a.apk` | `Anilife_armeabi-v7a.apk` |
| TV | `Anilife_tv.apk` | `Anilife_tv_arm64-v8a.apk` | `Anilife_tv_armeabi-v7a.apk` |

Publishing is gated on green CI, verified package/flavour/ABI metadata, checksums, APK signature
verification, signed update metadata, R8 mapping retention, owner-approved artwork and rights, and
explicit human approval. Candidate workflows must not silently publish a public release.

## Privacy and security

- The inherited `UsagePing` endpoint and persistent account/device correlation are removed.
- Remote analytics are off by default and have no active endpoint during this phase.
- Manual diagnostic sharing remains user-initiated and should be reviewed before attachment.
- Historical provider account tokens are not accepted as community credentials.
- Report vulnerabilities privately through
  [GitHub Security Advisories](https://github.com/anilifecommunity/android-and-tv/security/advisories/new).

For normal bugs, use the repository's issue templates and remove tokens, cookies, private media
URLs, account identifiers, and personal data from logs or screenshots.

## Project status

The evidence baseline and task-by-task programme live under `docs/revamp/` and in the
[Anilife Android + TV Revamp milestone](https://github.com/anilifecommunity/android-and-tv/milestone/1).
Current blockers are tracked explicitly; authorised provider credentials, hardware tests, or
legal/rights review are never reported as completed work.

## Licence and content boundary

Repository code is available under MIT No Attribution (`MIT-0`). That licence does not grant
rights to third-party anime, artwork, subtitles, streams, trademarks, or provider APIs. Direct
GitHub distribution still requires compliance with applicable licences, service terms, privacy
obligations, and copyright law.
