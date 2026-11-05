# Security policy

## Supported versions

There is no approved public Anilife release yet. After the first release, only the latest version
published by `anilifecommunity/android-and-tv` will receive security fixes.

## Report a vulnerability

Use a private [GitHub Security Advisory](https://github.com/anilifecommunity/android-and-tv/security/advisories/new).
Do not open a public issue for a vulnerability, leaked credential, signing concern, or bypass that
could put users at risk.

Include the affected version or commit, device and Android version, reproduction steps, impact, and
the smallest useful diagnostic sample. Remove account tokens, cookies, private stream URLs, media
history, and personal identifiers. We will acknowledge a usable report, investigate it privately,
and coordinate disclosure after a fix is available.

## Credential exposure

Treat any credential committed to Git or printed in public CI as compromised. Revoke it at the
provider, rotate the GitHub secret, invalidate derived sessions where possible, and document the
incident without copying the secret into an issue or pull request.

The Android application must not contain OAuth client secrets, Discord bot tokens, provider user
accounts, the APK signing keystore, or the update-manifest private key.

Maintainers should follow the [incident-response procedure](docs/security/incident-response.md) for
credential exposure, signing concerns, or a vulnerable release.
