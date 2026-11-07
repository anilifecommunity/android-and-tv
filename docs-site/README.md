# Mintlify repository source

This directory is the repository-backed source for `docs.anilife.cc`. It mirrors the public site
that was first published through the Mintlify MCP.

## Connect the repository

1. Open the `anilifecommunity/anilifecommunity` project in the Mintlify dashboard.
2. In the project source or Git settings, install or authorize the Mintlify GitHub App for the
   `anilifecommunity` organization.
3. Select `anilifecommunity/android-and-tv` as the repository.
4. Set the production branch to `main` and the documentation directory to `docs-site`.
5. Confirm the preview contains the same eight pages before replacing the existing artifact-backed
   source.
6. Preserve the active `docs.anilife.cc` custom domain and TLS configuration.

Do not select the repository's existing `docs/` directory. It contains maintainer and protocol
references rather than the Mintlify site.
