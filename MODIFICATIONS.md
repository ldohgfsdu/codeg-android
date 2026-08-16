# MODIFICATIONS

Agent Console overlay modifications to upstream `xintaofei/codeg-android`
(base `33dba91`, Apache-2.0). Upstream copyright and notices remain intact in
`LICENSE`, `NOTICE`, `THIRD_PARTY_NOTICES.md`.

| File | Nature | Reason |
|------|--------|--------|
| `app/src/main/java/app/codeg/android/agentconsole/permission/CommandClass.kt` | **added** | Command classification model (overlay) |
| `app/src/main/java/app/codeg/android/agentconsole/permission/CommandClassifier.kt` | **added** | Deterministic shell classifier (overlay) |
| `app/src/main/java/app/codeg/android/agentconsole/permission/RiskAssessor.kt` | **added** | Target-anchored risk rules (overlay) |
| `app/src/main/java/app/codeg/android/agentconsole/permission/PermissionDigest.kt` | **added** | Digest model, raw preserved for audit (overlay) |
| `app/src/main/java/app/codeg/android/agentconsole/permission/PermissionSummarizer.kt` | **added** | Digest generation, UNKNOWN fallback (overlay) |
| `app/src/main/java/app/codeg/android/agentconsole/permission/PermissionDigestCard.kt` | **added** | Digest-first permission card UI (overlay) |
| `app/src/test/java/app/codeg/android/agentconsole/permission/CommandClassifierTest.kt` | **added** | Tests |
| `app/src/test/java/app/codeg/android/agentconsole/permission/RiskAssessorTest.kt` | **added** | Tests |
| `app/src/test/java/app/codeg/android/agentconsole/permission/PermissionSummarizerTest.kt` | **added** | Tests incl. real acceptance fixture |
| `app/src/main/java/app/codeg/android/feature/sessiondetail/interactive/PermissionRequestCard.kt` | **modified** | Route shell-command permissions to the digest card (single call point); fix scroll condition to include command length / jsonPreview length (upstream bug: long commands overflowed the screen) |

Upstream-sync seam: all new code lives under `agentconsole/`; the only upstream
file touched is `PermissionRequestCard.kt` (2 minimal hunks).
