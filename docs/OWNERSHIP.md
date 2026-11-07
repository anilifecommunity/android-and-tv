# Ownership and external dependencies

This map records which parts of Anilife are controlled by the project and which are external. It
names public configuration fields and trust boundaries, never secret values.

## Project-controlled services

| Surface | Authority | Notes |
| --- | --- | --- |
| Android source, issues and releases | `anilifecommunity/android-and-tv` on GitHub | GitHub release assets are the primary APK and signed-manifest mirror. |
| Static media metadata | `anilifecommunity/media-index` on GitHub, served through jsDelivr | Cached locally and treated as fail-open enrichment, not a launch dependency. |
| Diagnostics intake and console | `diagnostics.anilifecommunity.org` on Cloudflare | Upload is optional and off by default; reports are private and retained for 30 days. |
| Private diagnostic objects | `anilifecommunity/anilife-storage` on Hugging Face | Worker write and maintainer read credentials are separate and are never compiled into the app. |
| Community and product domains | Anilife Community | `anilifecommunity.org` is the infrastructure domain. `anilife.cc` is the watch site. |

Provider websites, streaming hosts, relays, CDNs, DNS resolvers and account APIs are not owned or
operated by Anilife. Their current origins are declared beside their clients under
`app/src/main/java/com/anilili/data/remote`, `data/auth`, `data/update`, and `data/announce` so a
code review sees any origin change.

## Public integration identifiers

Release builds may contain the following public identifiers or origins. Their values are stored as
protected repository variables and local ignored configuration; blank values disable the feature.

| Configuration name | External administrator | Capability |
| --- | --- | --- |
| `ANILIST_CLIENT_ID` | AniList application owner | User-authorised AniList library access. |
| `MAL_CLIENT_ID` | MyAnimeList API client owner | User-authorised MyAnimeList library access. |
| `SIMKL_CLIENT_ID` | Simkl API client owner | User-authorised Simkl library access. |
| `DISCORD_APPLICATION_ID` | Anilife Discord application owner | Optional user-authorised Rich Presence. |
| `DISCORD_INVITE_URL` | Anilife Discord server administrator | Public community invite shown in About and presence. |
| `DIAGNOSTICS_UPLOAD_URL` | Anilife Cloudflare account owner | Fixed optional diagnostics origin. |
| `OPEN_SUBTITLES_API_KEY` | OpenSubtitles consumer owner | Subtitle search; extractable by design and not an account secret. |

The app also consumes public APIs from AniList, MyAnimeList, Simkl, TMDB, Jikan, AniZip, AniSkip,
OpenSubtitles and runtime-selected content providers. Anilife does not control their availability,
content, privacy terms or rate limits. No remote dependency is authorised to change the APK or its
update trust roots.

## Release and update authorities

APK signing and update publication are intentionally separate:

- The Android signing private key is restricted to the protected GitHub `release` environment.
  Android accepts an in-place update only when its APK certificate matches the installed app.
- Update manifests and announcements are Nostr events signed by a separate project key. The app
  embeds only its public key and verifies the event hash, signature, kind and channel tag.
- GitHub is the primary artifact host and mirror, while multiple public Nostr relays provide a
  signed recovery channel. Neither GitHub nor a relay can forge an installable update without both
  authorities.
- Package identity remains `io.github.anilifecommunity.anilife`. Changing the package or either
  signing authority is a migration, not a routine release.

## Exported Android entry points

| Component | Why it is exported | Boundary |
| --- | --- | --- |
| `MainActivity` and mobile icon aliases | Phone/TV launcher entry | Android launcher intents only; aliases select the local icon variant. |
| `AnililiLinkActivity` | Public `anilife://` deep links | Parses supported routes and forwards validated values to the app. The historical class name is retained for compatibility. |
| `DiscordOAuthCallbackActivity` | Browser OAuth return | Accepts only the configured application-specific scheme and callback path. |
| `ShareDiagnosticsActivity` | Launcher shortcut | Creates the local user-visible share flow; it does not upload automatically. |
| `ReminderRescheduleReceiver` | Boot and package-replaced broadcasts | Handles only the declared system actions and restores local reminders. |
| `MediaTransferReceiver` (mobile only) | Android/Google Cast session transfer | Library receiver required by the platform; absent from the TV flavour. |

Playback, proxy, download, WorkManager and scheduler services, file providers, reminder receivers
and profile restart components are not exported. The manifest is the source of truth for every
build flavour.

## Data and licence ownership

- Anilife-authored code in the current tree uses the Anilife Source-Available License 1.0 in
  `LICENSE`. Historical copies keep the licence under which they were received.
- Contributors retain copyright and grant the project the rights described in `CONTRIBUTING.md`.
- Dependencies keep their own licences; the release SBOM and dependency licence report record them.
- Maintainer-supplied logo and launch assets, and mechanical derivatives, are recorded in
  `docs/ASSET-LICENCES.md`.
- Posters, backdrops, subtitles, catalogue data and streamed media remain third-party material and
  are fetched at runtime. The repository licence does not claim rights over them.

Secrets, private keys, personal access tokens, OAuth access tokens, cookies and raw diagnostics are
deliberately excluded from this map and from the repository.
