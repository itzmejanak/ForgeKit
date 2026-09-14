# Termux bootstrap provenance

This record pins the exact third-party runtime archive distributed inside ForgeKit.

| Field | Value |
|---|---|
| Termux release | [`bootstrap-2025.03.28-r1+apt-android-7`](https://github.com/termux/termux-packages/releases/tag/bootstrap-2025.03.28-r1%2Bapt-android-7) |
| Build-recipe commit | [`5a6d1c1eb868795dce83a6c269387b9f82d21805`](https://github.com/termux/termux-packages/tree/5a6d1c1eb868795dce83a6c269387b9f82d21805) |
| Upstream asset | [`bootstrap-aarch64.zip`](https://github.com/termux/termux-packages/releases/download/bootstrap-2025.03.28-r1%2Bapt-android-7/bootstrap-aarch64.zip) |
| Upstream checksum file | [`CHECKSUMS-sha256.txt`](https://github.com/termux/termux-packages/releases/download/bootstrap-2025.03.28-r1%2Bapt-android-7/CHECKSUMS-sha256.txt) |
| SHA-256 | `c8d702b6f742935001c37cda81b8ac69504a95d5cf28f2899532dd8cd4b057eb` |
| Size | 29,388,903 bytes |
| ZIP entries | 3,490 |
| Declared symlinks | 1,146 (`SYMLINKS.txt`) |
| Installed packages | 75 (`var/lib/dpkg/status`) |

The asset is stored at `app/src/main/assets/bootstrap/bootstrap-arm64-v8a.zip`. Only its filename
is changed; its bytes match the upstream asset and checksum exactly.

Verify the checked-in asset from the repository root:

```bash
(cd third-party/termux-bootstrap && sha256sum -c SHA256SUMS)
```

## Corresponding source

[`PACKAGES.md`](PACKAGES.md) maps every installed binary package to its exact version, declared
license and build recipe at the pinned Termux commit. Each recipe records the upstream source URL,
source checksum, patches and build instructions used by Termux. The unmodified license collection
is also present inside the bootstrap under `share/LICENSES/`.

ForgeKit changes the extracted installation path at runtime but does not patch or rebuild these
packages. A bootstrap update is incomplete until this provenance record, the checksum and the full
package registry are updated together.
