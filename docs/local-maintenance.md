# Local maintenance 0.2.1-dev

No server required. Open Local maintenance, create a device/app snapshot, or select a Williams diagnostic ZIP or a candidate APK through Android's document picker. Save a bounded JSON report and manually attach it to ChatGPT. The original ZIP is not transmitted or included in the JSON. Attach it separately only after reviewing its privacy contents. The two visible Williams packages are fixed; there is no package-wide discovery.

APK preflight requires the expected SHA-256 from a trusted CI artifact and compares candidate package, version, minimum SDK and current signer fingerprints. This is NOT complete APK signature verification or CI attestation. Update remains BLOCKED regardless of result, because live trading readiness and provenance are unverified. No installation, shell, accessibility, background access or trading operation.

The existing local MCP and HTTPS gateway remain unchanged. No Sites deployment or live ChatGPT binding exists. The local report is an independent, manually transferred workflow, not remote control. Debug signing keys may vary between runners; do not uninstall an existing app to bypass signature mismatch.

Limits: input 128 MiB, expanded ZIP 512 MiB, 256 flat entries, metadata 64 KiB. Temporary input is deleted after inspection, but abrupt process death may leave a private cache file. No raw logs are exported. Archive integrity is not source attestation. Physical Android and SAF tests remain required.

0.2.1: snapshot freshness, single in-flight send, document-picker state restoration, background package lookup, bounded strict ZIP metadata and dedicated JVM tests. An interrupted running file check is not restored; select the file again. No live phone tests yet.
