# Changelog

This file records user-visible changes to Anilife for Android and TV. Dates use UTC. A version
stays marked **Unreleased** until its GitHub release is published.

## [Unreleased]

## [0.1.2] - 2026-11-14

### Fixed

- Added the active profile avatar to Cinema's navigation rail so phone, tablet and TV rail layouts
  can open the existing guarded profile switcher without leaving Anilife. Legacy remains unchanged.
- Reused Discord's registered mobile callback for TV sign-in instead of the unregistered loopback
  path that Discord rejected with `Invalid OAuth2 redirect_uri`.

## [0.1.1] - 2026-11-13

### Changed

- Made Cinema the default layout for new installs and unset preferences while preserving an
  existing explicit Legacy selection.

### Fixed

- Completed AniList and MyAnimeList sign-in inside Anilife instead of allowing the public web app
  and a second navigation rail to open inside the login window.
- Persisted each pending OAuth state and MyAnimeList PKCE verifier securely across activity and
  process recreation, and rejected callbacks whose host, path, service, or state does not match.
- Suppressed every native navigation surface while an authentication screen owns the app chrome.
- Added the device-side adult catalogue to Hentai genre browsing when the viewer has explicitly
  turned off **Hide adult content**; the safety setting remains on by default.

## [0.1.0] - 2026-11-13

First stable Anilife release for Android phones, tablets, Android TV, Google TV, and Fire TV. It
includes the complete beta.1 and beta.2 feature sets below plus the final tester fixes in this
section.

### Added

- Documented and verified the existing external-player hand-off for mpvEx, mpv-android, VLC, and
  other compatible Android players. The built-in player remains the default; custom Anime4K
  enhancement stays in the external player's configuration.

### Changed

- Restored the smoother beta 1 launch reveal, shortened every theme variant to 2.7 seconds, and
  bounded the complete hand-off to 2.7-3.2 seconds while preserving tap-to-skip.
- Enlarged the phone Home wordmark slightly and made its emblem follow the selected theme.
- Confirmed AndroidX edge-to-edge behavior across supported Android versions and retained the
  existing in-player screen lock and profile PIN instead of adding duplicate controls.

### Fixed

- Reframed phone and TV launcher icons around the boy-and-leaf emblem without the nested green
  square, excess empty space, or colour remaining in the monochrome variant.
- Released the Android TV video surface when Anilife stops so switching back to an HDMI input does
  not leave a black SurfaceView over audible television content.

## [0.1.0-beta.2] - 2026-11-07

### Added

- Optional Cinema navigation for phones and TV, while the existing Legacy layout remains the
  default.
- Theme-matched startup animations and a lightweight theme-aware loading indicator.
- Signed in-app project announcements and an off-by-default diagnostic detail selector with
  Standard and Enhanced modes.
- Manual diagnostic export and upload, plus an optional off-by-default daily upload. Accepted
  reports are private and retained for 30 days.
- Public installation, update, account, notification, diagnostic, compatibility, and
  troubleshooting documentation at `docs.anilife.cc`.

### Changed

- Refined the round phone and TV launcher artwork, black startup surface, wordmark sizing, About
  copy, Discord destination, and Anilife website links.
- Expanded diagnostic correlation for startup, home, detail, network, playback, buffering, and
  upload operations while keeping sensitive values out of reports.
- Updated release automation to build and verify six signed APKs with checksums, R8 mappings,
  CycloneDX SBOM, provenance attestations, and 16 KiB native-library alignment checks.

### Fixed

- Removed the duplicate startup logo and green launch gradient.
- Prevented automatic and manual diagnostic uploads from racing, duplicating scheduled work, or
  retaining staged reports after consent is revoked.
- Preserved retryable diagnostic reports when an upload receipt is ambiguous.

### Security and privacy

- Diagnostics default to Standard detail and remain local unless the user explicitly exports or
  uploads a report.
- Added bounded diagnostic fields, sensitive-value redaction, upload rate limits, Turnstile for
  the diagnostic console, private object storage, and a 30-day retention boundary.
- Updated CI actions and runner images, dependency review, CodeQL, signed update checks, and
  supply-chain attestations.

## [0.1.0-beta.1] - 2026-11-06

### Added

- First Anilife tester prerelease for Android phones, tablets, Android TV, Google TV, and Fire TV.
- Anilife logo, launcher artwork, wordmark, TV banner, and startup animation.
- Black-and-green Anilife appearance with the previous monochrome look retained as Legacy
  Midnight.
- Lightweight logo water-fill for full-screen loading and standard indicators for smaller waits.
- Optional beta update channel under **Settings > App > Receive beta updates**, off by default.
- Universal, ARM64, and ARMv7 APKs for both phone and TV.

### Security and distribution

- Signed update metadata validates checksums, package and build metadata, ABI, form factor, and
  signing identity before Android is asked to install an update.
- Release assets include checksums, signing-certificate metadata, an SBOM, build provenance, and
  R8 mapping files.

Beta.1 was a Discord tester build and was not claimed as real-device or stable hardware
validation.

[Unreleased]: https://github.com/anilifecommunity/android-and-tv/compare/v0.1.2...HEAD
[0.1.2]: https://github.com/anilifecommunity/android-and-tv/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/anilifecommunity/android-and-tv/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/anilifecommunity/android-and-tv/compare/v0.1.0-beta.2...v0.1.0
[0.1.0-beta.2]: https://github.com/anilifecommunity/android-and-tv/compare/v0.1.0-beta.1...v0.1.0-beta.2
[0.1.0-beta.1]: https://github.com/anilifecommunity/android-and-tv/releases/tag/v0.1.0-beta.1
