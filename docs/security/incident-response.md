# Security incident response

This document is the maintainer procedure for a credential exposure, signing concern, vulnerable
release, or compromised external integration.

## Contain

1. Stop affected publication or automation without deleting evidence.
2. Revoke exposed provider credentials or sessions and rotate the corresponding GitHub secret.
3. Disable update promotion if release or update-signing integrity is uncertain.
4. Open a private GitHub Security Advisory for coordination and evidence.

## Assess

Record the affected commits, workflow runs, artifacts, versions, time window, and exposed authority.
Do not paste secret values into issues, pull requests, logs, or the advisory. Determine whether the
APK signing key, update-manifest key, provider configuration, user data, or only a public identifier
was affected.

## Recover

- A bad but correctly signed release is superseded with a higher version; never reuse a version.
- A compromised update-manifest key requires a trust-root migration before update publication.
- A suspected APK-signing compromise blocks publication until a migration and user communication
  plan is approved.
- Rebuilt artifacts must pass the normal checksum, signature, SBOM, provenance, and hardware gates.

## Communicate and review

Coordinate disclosure after containment and a fix are available. Publish only the information users
need to identify affected versions and recover safely. Afterward, document the root cause and add a
test, permission boundary, or runbook check that prevents recurrence.
