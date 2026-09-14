# ForgeKit — Hard Rules

Non-negotiable constraints for every change to this repository. These encode
the architectural contract of `Docs/ARCHITECTURE.md`; violations are defects
regardless of what else works.

## R1 — Real behavior only

No mocks, stubs or fakes in the acceptance path: real processes (fork/exec),
real PTYs, real zip central-directory parsing, real SHA-256 verification,
real ed25519 signatures, real dpkg/apt inspection, real SQLite persistence.
Tests that cannot run the real thing must say so loudly — never silently fake it.

## R2 — Dependency direction

`app` may see everything. Every other module depends only on what
`STRUCTURE.md` grants it. `runtime` never knows about plugins; plugins never
know about the app. The `forgekit/1` protocol is the only channel between the
platform and plugin processes.

## R3 — The UI renders, never invents

Every screen renders state that exists (registry, import pipeline, jobs,
runtime health). No hardcoded capabilities, no fake data, no placeholder
screens that pretend to work. If the backend has no data, the screen shows an
honest empty state.

## R4 — Import is a gate, not a hope

Every `.forge` archive passes: quarantine → archive verification → manifest
validation → signature verification → facts shown to the user → registration.
Nothing executes before the user approves. Unsigned packages import as
UNSIGNED (approval required); broken packages are rejected with precise
violations.

## R5 — targetSdk 28 is deliberate

Android 10+ SELinux forbids `exec()` of binaries in app data when
targetSdk ≥ 29. The embedded Termux runtime requires exactly that capability,
for the same reason upstream Termux pins 28 and ships outside Play. The lint
disable for `ExpiredTargetSdkVersion` is a policy override, not negligence.

## R6 — arm64-v8a only

The locked distribution target. Bootstrap, PTY harness, dex, everything.
Other ABIs would need their own verified bootstrap — not a flag flip.

## R7 — Bootstrap is verified, never trusted

The bundled bootstrap zip is pinned by SHA-256 and re-verified at extract
time (structure + deep CRC). Repair means wipe → re-verify → re-extract →
re-init. Never require an app reinstall to fix a runtime.

## R8 — stdout is protocol, stderr is diagnostics

Between the platform and a plugin, stdout carries `forgekit/1` NDJSON only.
Human diagnostics go to stderr. A plugin that prints garbage to stdout fails
loudly, not silently.

## R9 — Deterministic packages

`forge build` produces byte-identical archives for identical inputs:
fixed area order, canonical manifest hashing, real POSIX mode preservation.
Two builds of the same tree must have the same SHA-256.

## R10 — Structure changes flow through STRUCTURE.md

Every module, package and dependency must match `STRUCTURE.md`. If the change
needs a new place, the document changes first, then the tree.
