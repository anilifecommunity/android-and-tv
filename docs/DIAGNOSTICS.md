# Diagnostics and privacy

Diagnostic collection is local by default. A report leaves the device only when the user exports
or uploads one manually, approves the crash-report prompt, or explicitly enables the separate
**Automatically upload diagnostics** setting. That setting is off by default.

Enabling automatic upload starts a new consent window: events recorded before consent are excluded.
The app schedules at most one daily report, retains a staged report unchanged across safe retries,
and accepts success only when the service returns the same report ID. Turning the setting off
cancels queued work and removes staged reports. Accepted uploads are private and expire after 30
days. Local export remains available for review or sharing without enabling uploads.

Manual reports ask the user to describe the problem. A JPEG, PNG or WebP screenshot up to 5 MB can
also be selected explicitly; it is never captured automatically. The description and screenshot
are included inside the diagnostic ZIP. Crash reports offer the same optional context, while the
slow-start action and launcher shortcut remain usable without it. TV sharing depends on the share
targets installed by the device vendor.

Reports can include app/device configuration, Android version, memory and CPU samples, thermal and
frame-jank windows, playback/decoder/buffering statistics, resolver lifecycle, provider hosts and
HTTP timing/status data, WorkManager state, app logs, Java/Kotlin crash details, and Android native
exit traces when the OS exposes them.

Before a report is created, the app centrally redacts the written description plus passwords,
cookies, authorization headers,
access and refresh tokens, JWTs, email addresses, signed URL paths/query parameters, anime titles,
slugs and other sensitive content identifiers. Screenshots can contain whatever is visible in the
selected image, so the UI warns users to attach only content they intend to send. Raw IP addresses
are not stored.

Generated share-sheet ZIPs remain in Android's disposable app cache for up to 24 hours so the
selected target can read them. Android may clear them sooner under storage pressure, and the app
removes old reports the next time it creates one. Once the user shares a ZIP through another app,
retention and delivery are controlled by that app.
