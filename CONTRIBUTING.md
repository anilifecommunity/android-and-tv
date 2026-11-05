# Contributing to Anilife

Start with an issue in the `Anilife Android + TV Revamp` milestone for changes that affect identity,
security, providers, update trust, or release behaviour. Small fixes can use the bug template.

## Development setup

- JDK 17
- Android SDK 36 with Build Tools 35.0.1
- the checked-in Gradle wrapper

Run the relevant mobile and TV checks before opening a pull request:

```powershell
.\gradlew.bat :app:testMobileDebugUnitTest :app:testTvDebugUnitTest --max-workers=2 --no-configuration-cache
.\gradlew.bat :app:lintMobileDebug :app:lintTvDebug --max-workers=2 --no-configuration-cache
.\gradlew.bat :app:assembleMobileDebug :app:assembleTvDebug --max-workers=2 --no-configuration-cache
```

Keep `minSdk 22`, both form-factor flavours, and 32-bit ARM support unless an approved issue changes
those product constraints. The internal `com.anilili` namespace is intentionally retained during
the rebrand.

## Credentials and data

Copy identifiers only into the ignored `.env.infrastructure.local` file. Never commit filled
values, keystores, private keys, OAuth client secrets, bot tokens, provider accounts, cookies, or
diagnostics containing personal data. Debug builds must continue to compile with all optional
integrations blank.

## Pull requests

Use a focused branch, explain user-visible and security effects, list verification evidence, and
call out hardware or provider checks that were not run. A green local build is not enough: required
GitHub checks and review must also pass. Public releases are handled only by the protected release
environment and are never an ordinary pull-request side effect.
