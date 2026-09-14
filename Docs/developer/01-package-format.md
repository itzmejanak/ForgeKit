## The `.forge` package

A `.forge` file is a ZIP archive. The filename extension is a convention used by the picker and tooling; the importer opens the bytes as ZIP.

### Allowed archive locations

| Path | Status | Meaning |
|---|---|---|
| `manifest.json` | Required | Strict `forgekit.plugin/v1` manifest. |
| `runtime/**` | Effectively required | The declared entrypoint must exist here. |
| `ui/**` | Optional | Declarative UI documents. |
| `dependencies/**` | Optional data | Packaged dependency metadata/files; ForgeKit does not execute these automatically. |
| `assets/**` | Optional data | Plugin assets. |
| `docs/**` | Optional data | Plugin-owned documentation. |
| `tests/**` | Optional data | Plugin tests; the app and current CLIs do not execute them automatically. |
| `HASHES` | Optional for unsigned packages | Canonical content hash document. Required for a signed package. |
| `SIGNATURE.json` | Optional | Detached Ed25519 signature over `HASHES`. |
| `publisher.json` | Optional for unsigned packages | Required in practice for signature verification because it carries the public key. |

No other root file or top-level area is accepted. `forge.json` is tolerated as an otherwise allowed root filename but is not read as the manifest; `manifest.json` remains mandatory. Directory entries are ignored. Regular file names must be relative, canonical `/`-separated paths with no blank segment, `.` segment, `..` segment, leading slash, trailing slash, or backslash.

### Structural and content gates

The current importer enforces:

- readable ZIP structure;
- at least one regular file;
- a parseable and valid `manifest.json`;
- declared entrypoint present and no larger than 8 MiB;
- total archive no larger than 256 MiB;
- declared UI file present and containing JSON with `schema: "forgekit.ui/v1"`;
- no ZIP symlink entries;
- all archive entries under the allowed locations;
- signed-package hash coverage, canonical formatting, public-key fingerprint, and signature validity.

Hidden path segments are warnings, not rejection. A `.so` outside `runtime/` is warned when such a path is otherwise allowed. Import-time validation currently performs only a shallow UI schema check; the full UI cross-validation happens when the installed interface opens. This limitation is tracked in the hardening section.

### Install behavior

Approval extracts the complete archive into a staging directory, rechecks paths against ZIP-slip, reapplies recorded POSIX permission bits where supported, and writes the original manifest bytes to the staged root. An existing plugin directory with the same ID is moved aside before the new tree is atomically moved into place. If the swap fails, the old tree is restored. After extraction, dependency provisioning runs. A `.forgekit-ready` marker is written only when resolution is satisfied.

If provisioning fails, the new plugin tree remains installed without a ready marker and appears `UNRESOLVED`. Installing a replacement therefore does not roll back merely because dependency provisioning failed; the rollback protects the filesystem swap itself.

The installer creates these additional per-plugin directories whether or not the archive contained them:

```text
data/
cache/
environment/
logs/
bin/
```

Do not place a root `bin/` area in the source package: the archive gate does not accept it. Put shipped executables under `runtime/`; the installed `bin/` directory is host-managed space.

### Deterministic builder behavior

`forge-builder` parses the manifest before writing output. It includes `manifest.json`, then regular files under `runtime`, `ui`, `dependencies`, `assets`, `docs`, and `tests` in fixed area/path order. Other source-root files are ignored. Entries are stored without ZIP compression, timestamps are fixed by the writer, and POSIX modes are recorded, so identical source bytes and modes produce identical archives.

Unsigned builds omit `HASHES`, `SIGNATURE.json`, and `publisher.json`. Signed builds add all three. The canonical `HASHES` format is:

```text
forgekit.hashes/v1
sha256 <64 lowercase hex> <relative path>
```

Paths are bytewise sorted. `HASHES`, `SIGNATURE.json`, and `publisher.json` are not themselves listed in the signed hash document.
