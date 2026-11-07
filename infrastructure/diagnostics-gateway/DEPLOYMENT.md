# Cloudflare Workers deployment

## 1. Storage

The production bucket is private:

```text
hf://buckets/anilifecommunity/anilife-storage
```

Use an S3 credential restricted to this bucket. A Hugging Face write credential can also read its
allowed resources, so it must not be reused for unrelated repositories or buckets.

## 2. Cloudflare resources

`wrangler.jsonc` defines the Worker, `diagnostics.anilifecommunity.org` custom domain, static
dashboard assets, KV binding, and hourly retention cron. The `workers.dev` and preview endpoints are
disabled. Wrangler creates or reuses the bound KV namespace. Secret values are supplied only during
deployment:

```text
HF_S3_ACCESS_KEY_ID
HF_S3_SECRET_ACCESS_KEY
ADMIN_ACCESS_KEY
RATE_LIMIT_SECRET
TURNSTILE_SECRET
```

Deploy from `infrastructure/diagnostics-gateway` with an authenticated Wrangler session:

```powershell
npm run worker:types
npm test
npm run typecheck
npm run deploy:dry-run
npx wrangler deploy --secrets-file C:\secure\anilife-diagnostics-secrets.json
```

The secrets JSON must never be stored in this repository. Delete it after Wrangler confirms the
deployment and encrypted secret bindings.

## 3. Production gates

Do not put the endpoint into the Android build until every check passes:

1. `GET /health` returns HTTP 200 with `storage: private-hf-bucket`.
2. The console loads its public Turnstile site key, while no secret is returned to the browser.
3. A valid synthetic report returns `status: accepted` and its matching report ID.
4. The bucket contains exactly one ZIP and one JSON sidecar for that report.
5. Malformed, encrypted, path-traversing, or over-limit archives are rejected with HTTP 400/413.
6. Anonymous list, download, and delete requests return HTTP 401.
7. Administrator login requires a fresh Turnstile proof, then can list, download, and permanently
   delete the synthetic report.
8. Downloaded bytes match the uploaded synthetic ZIP.
9. A 429 response remains queued by Android WorkManager.
10. A cold-start upload succeeds within the Android client's three-minute call timeout.

## 4. Android rollout

Build with the verified origin only; do not include `/v1/reports` because the client adds that path:

```powershell
./gradlew assembleRelease -PdiagnosticsUploadUrl=https://diagnostics.anilifecommunity.org
```

Run targeted Android tests and Samsung/TV smoke tests before release. This infrastructure change
does not require or imply an application version bump.

## 5. Rollback

Set `diagnosticsUploadUrl` back to blank in the next build to disable sending without changing report
generation, local redaction, Save to Downloads, crash capture, or the pending-report queue. Existing
reports remain private in the bucket until manually deleted or removed by retention cleanup.
