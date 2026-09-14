# ForgeKit Developer and AI Agent Guide

Contract scope: ForgeKit application `0.1.0`; manifest `forgekit.plugin/v1`; UI `forgekit.ui/v1`; wire protocol `forgekit/1`; runtime capabilities `forgekit.runtime/v1`.

This guide describes what the current ForgeKit code accepts and does. It is not a promise that every proposal in `ARCHITECTURE.md` has shipped. When this guide says **required**, the current parser, validator, installer, or runner enforces it. When it says **author rule**, the platform may not enforce it yet, but violating it produces unsafe or non-portable behavior. The final section lists those enforcement gaps explicitly.

ForgeKit is an Android host around an embedded Termux-derived prefix. The Android side owns package import, validation, native UI, persisted job records, file picking, and runtime orchestration. The embedded prefix owns executable processes and its `pkg`/`apt`, `pip`, and `npm` ecosystems. A plugin is untrusted executable code running with the ForgeKit app UID; it is not an Android APK and cannot ship an Activity.

## The shortest correct workflow

1. Create a source directory with `manifest.json`, the declared file below `runtime/`, and optionally a declared `ui/` JSON document.
2. Use only manifest fields and values defined in this guide. Unknown JSON keys are rejected.
3. Make the entrypoint read exactly one `invoke` NDJSON message from standard input.
4. Write protocol messages as one compact JSON object per line and flush after every line.
5. End with one `result` or `error`, then exit. Successful `result` requires process exit code `0`.
6. Build with the repository’s `forge-builder`; do not assemble the ZIP by hand for release.
7. Inspect the built archive with `forge-validator` and treat exit code `1` as a hard rejection.
8. Import it through ForgeKit’s document picker, review every permission/dependency/trust fact, approve, and wait for real provisioning to finish.
9. Test the installed action and inspect its persisted job timeline and logs.
10. Hand off the source, `.forge` artifact, SHA-256, validator output, requirements, limitations, and test evidence. Never hand off a private signing seed.

## Ownership boundary

| Owner | Current responsibility |
|---|---|
| Plugin author | Manifest truth, executable behavior, protocol correctness, declared dependencies, declared outputs, safe handling of inputs, tests, and licensing of bundled content. |
| ForgeKit package layer | ZIP path gate, strict manifest parsing, size/content checks, optional Ed25519 integrity verification, extraction, rollback of a replaced tree, and ready marker. |
| ForgeKit application | Native screens, explicit import approval, dependency orchestration, job persistence, process start/cancel, terminal, and Android document picking. |
| Embedded Termux runtime | Real fork/exec through a PTY, shell and prefix environment, `pkg`/`apt`/`dpkg`, global `pip`, global `npm`, signals, and runtime health checks. |
| Android OS | App UID/SELinux boundary, storage permission on legacy devices, document provider access, public Downloads publication, lifecycle limits, and process termination under resource pressure. |

## A necessary security warning

ForgeKit `0.1.0` does not provide per-plugin Linux users, namespaces, seccomp, a network firewall, or a fully enforced permission sandbox. Manifest permissions are validated and recorded in an in-memory grant engine, but plugin execution does not currently consult that engine. Review source and dependencies as you would for any local command-line program. Signing proves integrity and key continuity; it does not prove safety.
