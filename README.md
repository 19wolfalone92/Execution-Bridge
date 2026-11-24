# Execution Bridge — 0.1.0-dev
First read-only vertical slice. Android user opens the app, previews a limited device-health snapshot, enters a trusted HTTPS gateway address and temporary device credential, and presses Send once. Gateway operator queues a device-health request, then fetches the actual result by operation ID. No background collection, shell, screen capture, arbitrary app access, trade control or APK installation. No production deployment. Never paste credentials into chat.

## Local gateway
Python 3.11+, standard library only. Set up a private working directory (outside Git), run:
```
umask 077
python gateway.py --db /private/bridge.db provision
python gateway.py --db /private/bridge.db serve --host 127.0.0.1 --port 8080
```
Provision prints two independent short-lived credentials and a device ID. Keep them private. A trusted HTTPS reverse proxy (or serve with --cert and --key) is required for Android. Do not expose the plain HTTP loopback port. Do not enable production deployment before review. Revoke with `python gateway.py --db /private/bridge.db revoke DEVICE_ID`. New enrollment is required after token expiry. Tokens are intentionally not persisted on Android. This is manual bootstrap, not the requested final pairing protocol.

## Local MCP
Run `python mcp_stdio.py` from an MCP client with BRIDGE_URL, BRIDGE_OPERATOR_TOKEN and BRIDGE_DEVICE_ID in its private environment. Only stdio, not a ChatGPT remote MCP endpoint. Operations: enqueue_health, get_operation_status. Queue acknowledgment is not phone evidence.

## Tests
`python -m unittest discover -s tests -v`. Android: Gradle 8.9, JDK 17, SDK 35, `gradle :app:lintDebug :app:testDebugUnitTest :app:assembleDebug`. Debug APK is a test candidate only. CI verifies signing and SHA-256, never stores signing secrets. Debug signer may differ across builds; no uninstall workaround.

See docs/limits.md for acceptance boundaries. Williams Bot and other repositories are unchanged.
