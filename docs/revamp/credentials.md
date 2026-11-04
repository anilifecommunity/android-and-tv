# Anilife credential map

No secret values belong in this document. Local Android identifiers go in the ignored
`.env.infrastructure.local` file.

## Android identifiers

| Name | Classification | Required now | Notes |
| --- | --- | --- | --- |
| `ANILIST_CLIENT_ID` | Public OAuth client ID | For AniList login | No client secret in Android. |
| `MAL_CLIENT_ID` | Public OAuth client ID | For MAL login | PKCE flow; no client secret in Android. |
| `SIMKL_CLIENT_ID` | Public OAuth client ID | For Simkl login | No client secret in Android. |
| `DISCORD_APPLICATION_ID` | Public application ID | For Discord presence | Never use a bot token. |
| `OPEN_SUBTITLES_API_KEY` | Extractable API consumer key | Optional | Restrict and monitor at the provider. |
| `KARTOONS_ACCOUNT_TOKENS` | Extractable provider account tokens | Optional | Comma-separated; use only with explicit account-owner permission. |
| `KARTOONS_TOKENS_MIRROR_URL` | Public refresh URL | Optional | JSON token mirror; blank disables remote refresh. |

Redirect names are recorded in the local template so provider-console configuration can be audited.
The app's canonical custom scheme is `anilife://`.

## Server-side and deferred values

`TMDB_READ_TOKEN` is for the Media Index build only and is deliberately not compiled into Android.
Cloudflare, GitHub App, analytics, object-storage, admin, and rate-limit credentials are deferred
until the corresponding service has an approved design and owner.

## Signing authority

The Android keystore and update-manifest key are separate authorities. Local copies are held under
the owner's Windows profile with access restricted to that account. Android passwords are stored
with Windows DPAPI. The protected GitHub `release` environment contains only these encrypted names:

- `ANDROID_SIGNING_KEYSTORE_BASE64`
- `ANDROID_SIGNING_STORE_PASSWORD`
- `ANDROID_SIGNING_KEY_PASSWORD`
- `UPDATE_SIGNING_PRIVATE_KEY`
- environment variable `ANDROID_SIGNING_KEY_ALIAS`

The same environment holds the four public OAuth/application IDs as environment variables and the
extractable OpenSubtitles consumer key as `OPEN_SUBTITLES_API_KEY`. Pull-request CI has no access to
the environment. `TMDB_READ_TOKEN` is a repository secret only on `anilifecommunity/media-index`,
where its scheduled workflow consumes it.

The release environment requires owner review. Rotating either signing key is a release migration
and must not be performed as routine secret maintenance.
