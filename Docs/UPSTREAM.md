# Upstream relationship: Termux

## What ForgeKit embeds

ForgeKit bundles the official arm64 asset from Termux packages release
[`bootstrap-2025.03.28-r1+apt-android-7`](https://github.com/termux/termux-packages/releases/tag/bootstrap-2025.03.28-r1%2Bapt-android-7).
The upstream file is renamed from `bootstrap-aarch64.zip` to
`app/src/main/assets/bootstrap/bootstrap-arm64-v8a.zip`; its bytes are unchanged.

| Fact | Value |
|---|---|
| Upstream commit | [`5a6d1c1eb868795dce83a6c269387b9f82d21805`](https://github.com/termux/termux-packages/tree/5a6d1c1eb868795dce83a6c269387b9f82d21805) |
| SHA-256 | `c8d702b6f742935001c37cda81b8ac69504a95d5cf28f2899532dd8cd4b057eb` |
| Size | 29,388,903 bytes |
| Contents | 3,490 ZIP entries, 1,146 declared symlinks, 75 installed packages |

The hash and size are pinned in `platform/android/.../AndroidEnvironment.kt`. Exact provenance,
the upstream checksum, source links and the per-package inventory live in
[`third-party/termux-bootstrap/`](../third-party/termux-bootstrap/).

ForgeKit does **not** embed the Termux Android app or its UI. The PTY harness in
`termux/embedded/src/main/c/forgekit_pty.c` is ForgeKit code, not copied Termux JNI code.

## Licensing and source

The bootstrap is a collection of independently licensed packages. Their license texts are present
inside the archive under `share/LICENSES/`; the exact package-to-license registry and corresponding
source/build-recipe links are in
[`third-party/termux-bootstrap/PACKAGES.md`](../third-party/termux-bootstrap/PACKAGES.md).
ForgeKit's own source remains under GPL-3.0.

ForgeKit is an independent project and is not affiliated with or endorsed by Termux.

## Bootstrap handling

1. The app pins and verifies the raw file SHA-256 and size, checks the ZIP central directory, then
   performs a deep CRC pass before extraction.
2. Extraction preserves POSIX modes and creates links declared by `SYMLINKS.txt`; the extractor
   also supports legacy ZIP entries marked with `S_IFLNK`.
3. Relocation rewrites the bootstrap's compiled Termux paths to ForgeKit's equally sized app path.
4. Runtime repair can wipe, re-verify, re-extract and reinitialize the prefix without reinstalling
   the APK.
5. On-device package management uses the bootstrap's own `pkg`, `apt` and `dpkg` against the
   repositories configured in the official archive.

## Android target SDK constraint

ForgeKit targets API 28 because Android 10 removes execute permission from an app's writable home
directory for apps targeting API 29 or later. The embedded runtime executes its package binaries
from app-private storage, so this is a runtime constraint, not a Play Store configuration choice.
ForgeKit releases are therefore distributed outside Google Play. See Android's
[Android 10 behavior change](https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission).

## Upgrade policy

A bootstrap upgrade must use a named official Termux release asset. The upgrade changes the asset,
its pinned hash and size, `third-party/termux-bootstrap/`, and this document in one commit. CI must
verify the upstream checksum, all real-bootstrap tests must pass, and the package/license inventory
must be regenerated before a release is tagged.
