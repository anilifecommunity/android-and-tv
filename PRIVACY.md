# Privacy

Anilife does not send analytics or usage telemetry. The application makes network requests only for
features a user invokes or enables, such as catalogue data, playback sources, subtitles, account
sync, update checks, and explicitly submitted diagnostics.

## Diagnostics

Diagnostic collection is local by default. A report is transmitted only when the user uploads one
manually or explicitly enables the off-by-default daily upload option. Enabling automatic uploads
starts a new consent window: events recorded before that point are excluded, disabling it cancels
queued work and deletes staged uploads, and accepted reports are retained privately for 30 days.
Local export remains available for review before sharing. Reports must not include account tokens,
cookies, private media URLs, or unnecessary personal information.

## Accounts and configuration

Optional account integrations are disabled when their public client identifier is not configured.
Authentication tokens and preferences are stored in Android application storage. User-created
backups are exported only on request and should be protected like other personal files.

## Third-party services

Catalogue, account, subtitle, playback, and update features contact third-party services. Those
services can observe ordinary connection metadata and apply their own terms and privacy policies.
Anilife does not control their availability or data handling.

For a suspected privacy or security vulnerability, use a private
[GitHub Security Advisory](https://github.com/anilifecommunity/android-and-tv/security/advisories/new).
