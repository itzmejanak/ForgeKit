## Known limitations and hardening gates

This section is part of the contract. An AI agent must not “fill in” these missing features by assumption.

### Package/schema correctness

- Full `forgekit.ui/v1` validation is deferred until interface open; import checks only JSON plus schema ID.
- Archive duplicate filenames are not explicitly rejected.
- Option type/default/mapsTo semantics, output-ID uniqueness, Python/Node duplicate dependency names, constraints, and declared output paths need stronger validation.
- Runtime version requirements are syntactic metadata in the shipped app and are not checked against the provisioned runtime.
- The builder’s publisher JSON needs robust string escaping and secure key input.

### Permission and trust enforcement

- Permission grants are in-memory and not consulted by action execution, dependency installation, network, or file access.
- Normal default grants are added even when the manifest omits them.
- The trust store is empty/non-persistent in the Android composition root; installed descriptors lose import-time trust/hash/publisher evidence.
- Security audit entries are in-memory/logcat-oriented, not a durable audit database.
- There is no OS-level per-plugin sandbox, dependency integrity pinning, repository certificate pinning, secret store, or comprehensive log/input redaction.

### UI/input/protocol

- Declarative `progress` and `log` blocks are not connected to live job events.
- Directory picking, actual Markdown rendering, date/time validation, manifest constraints, action confirmation, and `helper` text are incomplete.
- The generic run-draft UI renders most input kinds as plain text and validates only required values.
- Android PTY execution merges stderr with stdout. Non-protocol lines are accepted as diagnostic output even though authors are required to keep stdout protocol-only.
- General plugin log lines are not comprehensively secret-redacted. Runtime password-prompt values are excluded from events, but manifest password inputs are still part of the persisted job input map.
- Prompt interactions are host-mediated and versioned, but arbitrary raw terminal applications are not embedded inside a toast. Use the Terminal tab for unrestricted TTY interaction.

### Dependencies and lifecycle

- pip/npm environments are global, not plugin-local; shared changes can break another plugin.
- Provider `source` values, lockfiles, hashes, and reference-counted removal are not implemented.
- Provisioning installs the new plugin tree before dependency success; a failed update can leave the replacement `UNRESOLVED` instead of restoring the older ready version.
- Pending imports, permission grants, trust metadata, provisioning UI buffers, and runtime process registry are not durable across process death.
- No foreground service, durable scheduler, service manager, pause signal, backup/restore, or process reattachment is shipped.

### Distribution/platform

- Only arm64 is bundled/tested.
- `targetSdk 28` is required by the current exec-in-app-data strategy and prevents a normal current Google Play release. This must be treated as a distribution constraint, not hidden.
- `$FORGEKIT_OUTPUT` uses legacy broad shared-storage behavior and may be unavailable when permission/storage state does not allow it.
- Registry/Git/URL/private source classes are not wired to user-facing screens.
- Android capability bridges and notifications/background permissions proposed by the architecture are not shipped plugin APIs.

### Required release gate for a plugin

A package is ready to hand off only when all of the following are true:

1. It builds deterministically and passes the current validator.
2. Its full UI opens and every action input/option is exercised.
3. Clean-device provisioning succeeds against the actual bundled runtime.
4. Action success, error, cancellation, timeout, prompt submit/cancel, output durability, and repeat runs are device-tested.
5. The permission/security limitations above are acceptable for the code and data involved.
6. Dependencies and bundled assets have license/security review.
7. The handoff includes limitations and evidence, not only the `.forge` file.

If any condition is false, label the package experimental or blocked; do not imply that static validation alone proves production readiness.
