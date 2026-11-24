# Boundary and acceptance
Version 0.1.0-dev is a first vertical slice, not the full maintenance product.
- A: repository and commit are checkable.
- B: CI build, signer verification, emulator and physical launch are distinct.
- C/D/E/F: local gateway/MCP tests cover only the read-only subset; not a production security audit.
- G–L: require device, hosted gateway, actual bot adapter, owner-confirmed installation, ChatGPT connection and measurements.
- No APK installation, UI control, screen reading, ADB, Shizuku, background polling or trading in this slice. No secrets, raw application logs or diagnostic files collected.
- Credentials are independent, expire after one hour, hashed in SQLite; manual provisioning only. No refresh, certificate pinning, OAuth, rate limiting at transport, automated retention or pairing proof yet. Do not expose the development server publicly.
- Android uses an explicit preview and one-shot upload; no secret is saved. Local stop cannot retract bytes already transmitted. Server-side revoke is separate. Device health is self-reported, not cryptographic attestation.
- Android sandbox: package visibility requires declared queries; another app's private files require an explicit cooperative interface or owner export. PackageInstaller can require user confirmation. Doze and foreground service limits prevent assuming 24/7 polling.
- MCP stdio is local only. A remote HTTPS OAuth resource server and supported account connection are required for ChatGPT. Official help: https://help.openai.com/en/articles/12584461-connecting-custom-mcp-servers-in-chatgpt
- Later cooperative SDK should expose narrow signed/caller-checked interfaces. Williams update readiness must fail closed unless positions/STOP and reconciliation are freshly confirmed by the bot. No trade mutations or merge.
- No third-party source copied. Research candidates: KDE Connect (TLS pairing concepts), scrcpy/ADB/Appium/UI Automator (lab testing, not a public shell), Shizuku (optional explicit privilege), Termux (local tooling), Syncthing (bounded transfer concepts), Tailscale/WireGuard (private transport), Android Enterprise (managed devices only), FCM (optional wakeup, not guaranteed), MCP (typed tools). Full license and battery assessment pending.
