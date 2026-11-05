# Anilife release runbook

There is no public release authorisation in this runbook. It defines the evidence required before
the owner makes that separate decision.

## Candidate

1. Merge only reviewed changes with all required checks green.
2. Confirm provider identifiers are Anilife-owned and present in the protected `release` environment.
3. Confirm approved artwork and its rights record. Placeholder or inherited artwork blocks release.
4. Run `Signed release candidate` manually from the intended commit.
5. Download the candidate bundle: six APKs, two commit-bound R8 mappings, CycloneDX SBOM,
   build-provenance and SBOM attestation bundles, signing-certificate record, and `SHA256SUMS`.
6. Verify every checksum, APK signature, package ID, version, form factor, ABI, and min SDK on a
   separate trusted machine. Confirm every APK has the protected release certificate fingerprint,
   and verify the GitHub artifact attestations with `gh attestation verify`.
7. Install and smoke-test the universal mobile and TV APKs, including API 22 or equivalent Fire OS
   coverage, account flows, playback, downloads, updates, backup import, and accessibility.

The candidate workflow uploads a private workflow artifact for seven days. It cannot create a
GitHub release.

## Draft release

After the candidate evidence is accepted, run `Draft GitHub release` from `main`. It rebuilds and
re-verifies the assets behind the same release-environment review gate, regenerates and attests the
SBOM and build provenance, then creates a draft prerelease. A draft is not public and must be
inspected before publication.

Publishing the draft requires a fresh explicit owner decision. Do not publish while any asset,
rights, legal, provider, hardware, CI, or signing gate is unresolved.

## After publication

Record the public tag and commit, re-download all assets, compare checksums, and verify the APK
certificate. Publish the signed `anilife-update` Nostr manifest only after the GitHub release is
confirmed. Verify GitHub and Nostr resolve to the same version and assets.

If validation fails, keep or return the release to draft where possible, disable update promotion,
open a security incident if trust material may be involved, and issue a higher-version corrective
release. Never reuse a compromised signing authority.
