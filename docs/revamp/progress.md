# Anilife Android + TV revamp progress

Status values are evidence-based: `TODO`, `IN PROGRESS`, `DONE`, `BLOCKED`, or `NOT APPLICABLE`.

| ID | Status | Evidence / next gate |
| --- | --- | --- |
| A00 Baseline | DONE | See `baseline-2026-11-03.md`. |
| A01 External ownership inventory | IN PROGRESS | Source and GitHub inventory started; secret values will never be copied into this file. |
| A02 Failure matrix | TODO | Requires mock-server and updater failure fixtures. |
| A10 Legacy usage removal | TODO | Old worker references remain at baseline. |
| A11 Community identity | TODO | Proposed ID: `io.github.anilifecommunity.anilife`. |
| A12 Embedded provider tokens | TODO | Inherited provider credentials remain at baseline. |
| A13 Community index | TODO | Verify live static paths and degraded-mode behaviour. |
| A14 OAuth registrations | BLOCKED | Public IDs and portal registrations are owner/external-provider inputs; code must build with integrations disabled. |
| A15 Security boundaries | TODO | Exported components, imports, backup and logs need targeted review. |
| A16 Anilife brand migration | TODO | Visible source still says Anilili. |
| A17 Assets and external identities | BLOCKED | Owner-approved Anilife artwork and provider registrations are not yet available. Compile-safe placeholders/disabled integrations are allowed. |
| A20 CI | TODO | No workflows exist at baseline. |
| A21 Dependency upgrades | TODO | Deliberately separate from identity/security work. |
| A22 AGP 9 feasibility | TODO | Deprecated output API confirmed; no migration attempted yet. |
| A23 Reproducibility/native ABI | TODO | Native strip warnings observed; APK checks pending. |
| A30 Local performance | TODO | Existing diagnostics inventory pending. |
| A31 Privacy exporter | TODO | Must remain network-disabled by default. |
| A32 Targeted performance | TODO | No optimisation without measurements. |
| A33 TV accessibility | TODO | Emulator/manual checks pending. |
| A40 Signed update manifest | TODO | New separate update key authorised; implementation and hostile-input tests pending. |
| A41 Release pipeline | TODO | Candidate/release workflows and protected environment pending. |
| A42 Channels and UX | TODO | Stable/beta rules pending. |
| A43 Recovery/resilience | TODO | Key recovery and rollback documentation pending. |
| A50 Validation | TODO | Baseline is red; clean CI is the gate. |
| A51 Documentation | TODO | Existing README is historical Anilili content. |
| A52 Candidate only | BLOCKED | Requires green CI, approved assets/rights and explicit publication approval. |

