# ForgeKit

[![CI](https://github.com/itzmejanak/ForgeKit/actions/workflows/ci.yml/badge.svg)](https://github.com/itzmejanak/ForgeKit/actions/workflows/ci.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

ForgeKit is an Android app, built with Kotlin and Jetpack Compose, that embeds a real
Termux-based Linux runtime. Plugins — including AI-generated ones — get a native Android
interface over a complete POSIX environment: shell, packages, Python, Node and native binaries.

ForgeKit owns orchestration, UX, policy, packages, permissions and lifecycle. The embedded
Termux runtime owns Linux execution.

## Features

- **Embedded runtime** — the Termux bootstrap is bundled, SHA-256 pinned, verified and relocated
  on first start. `pkg`/`apt` work against the official Termux repositories.
- **Plugins as `.forge` packages** — signed (Ed25519) archives with a manifest, declarative UI and
  runtime entrypoint. Every import is quarantined, validated and reviewed before installation.
- **Dependency provisioning** — packages declare `pkg`/`pip`/`npm` dependencies; ForgeKit installs
  them with live progress and a persisted, auditable job.
- **Jobs** — every action runs as a persisted job with live logs, progress, prompts and cancel.
- **Declarative plugin UI** — `forgekit.ui/v1` documents render as native Compose screens.
- **Terminal** — an interactive bash on a real PTY with a VT100/ANSI renderer, 256-color and
  24-bit color support.

## Install

1. Download `ForgeKit-<version>.apk` from the [latest release](https://github.com/itzmejanak/ForgeKit/releases/latest)
   and check it against `SHA256SUMS`.
2. Allow installing apps from your browser or file manager, then open the APK.
3. First launch verifies and extracts the runtime; duration depends on the device.

Requirements: an **arm64** device with **Android 7.0 (API 24)** or newer.

ForgeKit targets API 28 because Android 10 removes execute permission from an app's writable home
directory when it targets API 29 or later, while this runtime executes its package binaries from
app-private storage. Releases are therefore distributed outside Google Play, and Android may warn
that the app targets an older version. See [`Docs/UPSTREAM.md`](Docs/UPSTREAM.md) for the exact
constraint and pinned Termux provenance.

## Build from source

Requirements:

- JDK 21
- Android SDK: platform 35, build-tools 35, NDK `27.0.12077973`, CMake `3.22.1`
- Gradle 8.10.2 comes with the wrapper

```bash
export ANDROID_HOME=/path/to/android-sdk   # or write sdk.dir=... into local.properties
./gradlew test                             # full JVM test suite
./gradlew :app:assembleDebug               # debug APK -> app/build/outputs/apk/debug/
./gradlew :tools:forge-builder:installDist :tools:forge-validator:installDist
```

`./gradlew :app:assembleRelease` builds an unsigned release APK unless release signing is
configured through environment variables — see [`Docs/RELEASING.md`](Docs/RELEASING.md).

## Write a plugin

A plugin is a source directory with a `manifest.json`, a runtime entrypoint and an optional
`ui/main.json`. Working examples live in [`plugin-examples/`](plugin-examples/)
(`hello`, `interactive`, `ssl-patcher`).

```bash
# build an unsigned package for local inspection
tools/forge-builder/build/install/forge-builder/bin/forge-builder build plugin-examples/hello --out hello.forge
# validate it the way the app does
tools/forge-validator/build/install/forge-validator/bin/forge-validator inspect hello.forge
```

Pass `--key <64-hex-character Ed25519 seed>` and `--name <publisher>` to sign a distributable
package. Key handling, schemas, lifecycle rules and the complete handoff checklist are in
[`Docs/developer/`](Docs/developer/), also exportable as one `Docs.md` file from the app's Home
screen.

## Documentation

| Document | Purpose |
|---|---|
| [`Docs/ARCHITECTURE.md`](Docs/ARCHITECTURE.md) | Architecture specification — read first |
| [`STRUCTURE.md`](STRUCTURE.md) | Modules, packages, dependency rules, build order |
| [`Docs/RULES.md`](Docs/RULES.md) | Rules every change must satisfy |
| [`Docs/PLUGIN_SPEC.md`](Docs/PLUGIN_SPEC.md) | `.forge` package and manifest format |
| [`Docs/PROTOCOL.md`](Docs/PROTOCOL.md) | `forgekit/1` NDJSON job protocol |
| [`Docs/UI_SPEC.md`](Docs/UI_SPEC.md) | Declarative UI and app design system |
| [`Docs/SECURITY.md`](Docs/SECURITY.md) | Trust model, permissions, signing, isolation |
| [`Docs/UPSTREAM.md`](Docs/UPSTREAM.md) | Termux runtime relationship |
| [`Docs/RELEASING.md`](Docs/RELEASING.md) | Release process and repository setup |

## Project layout

```
app/                Android application, composition root and screens
core/               common, model, database, logging, security, filesystem
platform/           Android integration: environment, notifications, storage, permissions
runtime/            runtime API, process bridge, bootstrap, embedded Termux runtime
plugins/            manifest, installer, validator, resolver, protocol, manager, registry
jobs/               job API, manager, persistence
ui/                 design system, declarative plugin UI, terminal
termux/embedded/    NDK/JNI PTY harness (C)
plugin-examples/    acceptance plugins used by the integration tests
tools/              forge-builder and forge-validator command-line tools
tests/integration/  end-to-end acceptance tests
third-party/        third-party notices and license texts
```

## Contributing

Contributions are welcome — see [`CONTRIBUTING.md`](CONTRIBUTING.md) and the
[Code of Conduct](CODE_OF_CONDUCT.md). Report security issues privately as described in
[`SECURITY.md`](SECURITY.md).

## License

ForgeKit is free software licensed under the [GNU General Public License v3.0](LICENSE).
The APK bundles third-party components under their own licenses; see
[`third-party/THIRD_PARTY_NOTICES.md`](third-party/THIRD_PARTY_NOTICES.md).

ForgeKit is an independent project and is not affiliated with or endorsed by the Termux project.
