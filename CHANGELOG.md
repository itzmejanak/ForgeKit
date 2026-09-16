# Changelog

All notable changes to ForgeKit are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/). The release workflow publishes the section whose
heading matches the pushed tag as the GitHub release notes.

## [Unreleased]

## [0.1.1] - 2026-09-16

### Changed

- Developer guide export is now a minimal Settings-header action instead of a Home floating
  button.
- Terminal and job/provider consoles share zoom feedback and tooltip-backed controls; terminal
  output supports long-press range selection, while log output supports native text selection,
  copy-all and pinch zoom. One stable bottom-right console action shows Copy at the live tail and
  flips to Go to latest only while the user is paused above newer output.

### Fixed

- Termux post-install relocation now rewrites large ELF and non-ELF files with bounded memory,
  preventing OpenJDK-sized runtime files from exhausting Android's app heap after otherwise
  successful package installs.
- Package presence can no longer hide an interrupted relocation/configuration phase. A durable
  reconciliation marker is repaired at startup or by idempotent provisioning before dependencies
  are reported ready.

## [0.1.0] - 2026-09-14

First public release.

### Added

- Embedded Termux runtime: bundled arm64 bootstrap, SHA-256 pinned and verified, relocated into
  the app on first start; `pkg`/`apt` against the official Termux repositories.
- `.forge` plugin packages with Ed25519 signatures, quarantine, validation and a reviewed,
  two-step import.
- Dependency provisioning for `pkg`, `pip` and `npm` with live progress and a persisted job.
- Jobs with live logs, progress, interactive prompts, cancel, swipe-to-delete and history.
- Declarative plugin UI (`forgekit.ui/v1`) rendered as native Compose screens.
- Interactive terminal on a real PTY: VT100/ANSI renderer with 256-color and 24-bit color,
  colored prompt and `--color` aliases.
- `forge-builder` and `forge-validator` command-line tools.
- Developer guide export from the Home screen.
- GPL-3.0 project licensing, third-party license/provenance records, and reproducible GitHub release
  automation with signed APKs, checksums and build attestations.

### Fixed

- Package symlinks that pointed into the Termux app's directory (for example apt's signing keys)
  are re-pointed into ForgeKit's prefix, so `apt update` verifies repositories again.

[Unreleased]: https://github.com/itzmejanak/ForgeKit/compare/v0.1.1...HEAD
[0.1.1]: https://github.com/itzmejanak/ForgeKit/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/itzmejanak/ForgeKit/releases/tag/v0.1.0
