# Anilife release runbook

This runbook defines the evidence required for maintainers to prepare and publish a release.

## Candidate

1. Merge only reviewed changes with all required checks green.
2. Confirm provider identifiers are authorised for distribution and present in the protected
   `release` environment.
3. Confirm that distributed artwork and other assets have an appropriate licence or rights record.
4. Run `Signed release candidate` manually from the intended commit.
5. Download the candidate bundle: six APKs, two commit-bound R8 mappings, CycloneDX SBOM,
   build-provenance and SBOM attestation bundles, signing-certificate record, and `SHA256SUMS`.
6. Verify every checksum, APK signature, package ID, version, form factor, ABI, and min SDK on a
   separate trusted machine. Confirm every APK has the protected release certificate fingerprint,
   and verify the GitHub artifact attestations with `gh attestation verify`.
7. Record any device checks that were run and list unverified hardware coverage in the release
   notes. Do not describe an untested path as passing.

The candidate workflow uploads a private workflow artifact for seven days. It cannot create a
GitHub release.

## Draft release

After the candidate evidence is accepted, run `Draft GitHub release` from `main`. It rebuilds and
re-verifies the assets behind the same release-environment review gate, regenerates and attests the
SBOM and build provenance, then creates or refreshes a draft prerelease. Refreshing is allowed only
while the release remains a draft; a published tag is never overwritten. A draft is not public and
must be inspected before publication.

Publishing requires an authorised maintainer's explicit approval. Asset, rights, legal, provider,
CI, signing, and update-integrity gates apply to every release.

## Beta validation

A beta may be published as a GitHub prerelease before the full device matrix is complete when its
release notes identify the unverified hardware paths and link to the beta feedback template. Beta
testers provide the real-device evidence for phone, TV, Fire OS, API 22, playback, updates, backup,
and accessibility. Testers enable **Settings → App → Receive beta updates** to keep following the
beta channel; the setting is off by default, so other installs check only stable releases. Stable
publication remains blocked until the required device evidence is recorded or an explicit
compatibility limitation is documented.

## After publication

Record the public tag and commit, re-download all assets, compare checksums, and verify the APK
certificate. Run `Publish signed update manifest` only after the GitHub release is confirmed,
selecting `beta` for a prerelease or `stable` for a full release. The protected workflow validates
the release state, checksums, package metadata, and APK signer before publishing the canonical
signed event. Verify GitHub and Nostr resolve to the same version and assets.

An unpublished draft may be rebuilt without changing its version. After a build is public, Android
updates require a higher `versionCode`; a corrective build may keep the same user-visible
`versionName`, but it must publish the higher build number in the signed update manifest.

## Recovery cases

- **Bad release:** do not replace published assets or move its tag. Publish a corrected APK with a
  higher `versionCode`, then supersede the channel manifest with the corrected build and a higher
  sequence. Mark the affected release clearly and preserve its evidence.
- **Manifest revocation:** a signed event cannot be recalled from every relay or device. Supersede
  it with a higher-sequence event; use a short expiry while containing an incident. Never lower the
  sequence or point a manifest at an APK that has not passed the normal signer and checksum checks.
- **Update-key loss or compromise:** stop manifest publication. If the key is merely unavailable,
  restore it from the controlled offline backup. Otherwise, publish an APK signed by the unchanged
  Android certificate with a new embedded update public key and require users to install it from a
  verified GitHub release. Existing installations cannot trust the replacement key by themselves.
- **APK-key loss or compromise:** stop releases. Android cannot accept an in-place update signed by
  an unrelated certificate; recover the controlled backup or plan a new application identity and
  an explicit user-data migration. Do not present the new identity as an ordinary update.
- **GitHub unavailable:** verify a previously built APK locally with `apksigner`, its recorded
  checksum, package/version metadata, and the expected Android certificate. Host that exact file on
  an HTTPS mirror, then use the offline update-key backup with `scripts/nostr_update.py publish` to
  publish a higher-sequence manifest. When GitHub returns, reconcile the release and evidence
  without changing the bytes users already received.

If validation fails, keep or return the release to draft where possible, disable update promotion,
open a security incident if trust material may be involved, and issue a higher-version corrective
release. Never reuse a compromised signing authority.
