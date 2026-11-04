# Anilife Android + TV revamp progress

Status values are evidence-based: `TODO`, `IN PROGRESS`, `DONE`, `BLOCKED`, or `NOT APPLICABLE`.

| ID | Status | Evidence / next gate |
| --- | --- | --- |
| A00 Baseline | DONE | See `baseline-2026-11-03.md`. |
| A01 External ownership inventory | IN PROGRESS | Source and GitHub inventory started; secret values will never be copied into this file. |
| A02 Failure matrix | TODO | Requires mock-server and updater failure fixtures. |
| A10 Legacy usage removal | DONE | Legacy `UsagePing` implementation, startup hook and tests removed. |
| A11 Community identity | IN PROGRESS | App ID, deep link, version, updater names and visible core identity migrated; device validation remains. |
| A12 Embedded provider tokens | IN PROGRESS | Kartoons provider is retained; inherited shared tokens are replaced by blank local/mirror configuration pending authorised credentials. |
| A13 Community index | IN PROGRESS | Android points at the live `anilifecommunity/media-index` tree; degraded-mode validation remains. |
| A14 OAuth registrations | BLOCKED | Public IDs and portal registrations are owner/external-provider inputs; code must build with integrations disabled. |
| A15 Security boundaries | TODO | Exported components, imports, backup and logs need targeted review. |
| A16 Anilife brand migration | IN PROGRESS | Core UI and release identity migrated; owner-approved launcher/TV artwork remains blocked. |
| A17 Assets and external identities | BLOCKED | Owner-approved Anilife artwork and provider registrations are not yet available. Compile-safe placeholders/disabled integrations are allowed. |
| A20 CI | IN PROGRESS | Bounded SHA-pinned mobile/TV jobs and repository templates added; required-check enforcement follows green runs. |
| A21 Dependency upgrades | TODO | Deliberately separate from identity/security work. |
| A22 AGP 9 feasibility | TODO | Deprecated output API confirmed; no migration attempted yet. |
| A23 Reproducibility/native ABI | TODO | Native strip warnings observed; APK checks pending. |
| A30 Local performance | TODO | Existing diagnostics inventory pending. |
| A31 Privacy exporter | TODO | Must remain network-disabled by default. |
| A32 Targeted performance | TODO | No optimisation without measurements. |
| A33 TV accessibility | TODO | Emulator/manual checks pending. |
| A40 Signed update manifest | IN PROGRESS | Separate key generated, public key pinned, local signed fixtures added; relay publication is intentionally withheld. |
| A41 Release pipeline | IN PROGRESS | Protected release environment and manual signed-candidate/draft-only workflows added; full candidate run remains gated. |
| A42 Channels and UX | TODO | Stable/beta rules pending. |
| A43 Recovery/resilience | IN PROGRESS | Local DPAPI/ACL storage and release rollback runbook added; offline backup ceremony remains owner work. |
| A50 Validation | IN PROGRESS | Both variants compile locally; complete unit/lint/assemble and hardware evidence remain gates. |
| A51 Documentation | IN PROGRESS | README, security policy, contribution guide, credential map and release runbook migrated. |
| A52 Candidate only | BLOCKED | Requires green CI, approved assets/rights and explicit publication approval. |

