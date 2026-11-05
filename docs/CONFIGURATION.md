# Developer configuration

Anilife debug builds work without external service configuration. Optional integrations read public
identifiers from the ignored `.env.infrastructure.local` file, Gradle properties, or environment
variables. Never commit filled values.

| Name | Purpose | Behaviour when blank |
| --- | --- | --- |
| `ANILIST_CLIENT_ID` | Public AniList OAuth client ID | AniList sign-in is disabled. |
| `MAL_CLIENT_ID` | Public MyAnimeList OAuth client ID | MyAnimeList sign-in is disabled. |
| `SIMKL_CLIENT_ID` | Public Simkl OAuth client ID | Simkl sign-in is disabled. |
| `DISCORD_APPLICATION_ID` | Public Discord application ID | Discord presence is disabled. |
| `OPEN_SUBTITLES_API_KEY` | Extractable OpenSubtitles consumer key | OpenSubtitles search is disabled. |
| `KARTOONS_ACCOUNT_TOKENS` | Optional, distributable provider tokens | The provider fails closed. |
| `KARTOONS_TOKENS_MIRROR_URL` | Optional authorised refresh document | Remote token refresh is disabled. |
| `RIFT_FIREBASE_API_KEY` | Optional authorised Firebase Android API key | Rift is disabled. |

Android clients must not contain OAuth client secrets, Discord bot tokens, signing material, or
credentials that are not authorised for redistribution. The canonical callback scheme is
`anilife://`.

`TMDB_READ_TOKEN` belongs to the separate Media Index build and must not be compiled into the
Android application.

## Release configuration

Release builds use the protected GitHub `release` environment. Pull-request workflows cannot read
that environment. APK signing and update-manifest signing use separate keys; rotating either key is
a release migration and must follow the recovery procedures in the release runbook.
