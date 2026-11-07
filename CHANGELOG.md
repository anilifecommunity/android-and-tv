# Changelog

This file records user-visible changes to Anilife for Android and TV. Dates use UTC. A version
stays marked **Unreleased** until its GitHub release is published.

## [0.1.0-beta.2] - Unreleased

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

[0.1.0-beta.2]: https://github.com/anilifecommunity/android-and-tv/compare/v0.1.0-beta.1...HEAD
[0.1.0-beta.1]: https://github.com/anilifecommunity/android-and-tv/releases/tag/v0.1.0-beta.1
