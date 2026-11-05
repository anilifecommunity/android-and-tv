# Contributing to Anilife

Use an existing issue or open a focused proposal before changing application identity, security
boundaries, providers, update trust, or release behaviour. Small fixes can use the bug template.

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

Preserve `minSdk 22`, both form-factor flavours, the `com.anilili` source namespace, and 32-bit ARM
support unless an accepted issue changes those compatibility requirements.

## Configuration and data

Follow [docs/CONFIGURATION.md](docs/CONFIGURATION.md). Never commit filled configuration values,
keystores, private keys, OAuth client secrets, bot tokens, provider accounts, cookies, or
diagnostics containing personal data. Debug builds must continue to compile with all optional
integrations blank.

## Pull requests

Use a focused branch. Explain user-visible, compatibility, security, and privacy effects; list the
exact verification performed; and call out hardware or provider checks that were not run. Required
GitHub checks and review must pass before merge. Release workflows are manually dispatched through
the protected `release` environment and are not a pull-request side effect.
