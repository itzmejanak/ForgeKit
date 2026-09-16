## Security, trust, permissions, and storage

### Package integrity and trust

Unsigned packages are accepted with an `UNSIGNED` warning and require explicit user approval. A signed package must have canonical hashes, a valid Ed25519 signature, a 32-byte public key in `publisher.json`, and a fingerprint equal to SHA-256 of that key. A valid but unpinned key is `SIGNED_UNKNOWN` and still requires approval.

The Android composition root currently creates an empty in-memory `TrustStore`, so no publisher becomes `SIGNED_TRUSTED` in normal app startup. Trust decisions, publisher identity, imported archive SHA-256, and signature status are not persisted with the installed tree; a descriptor reconstructed from disk reports unsigned/unknown provenance. Signing is still useful for import-time integrity and independent verification, but the installed-registry trust model is incomplete.

Signing does not attest code quality, safety, dependency behavior, or publisher identity outside the key itself.

### Permission catalog

Only these manifest IDs pass package validation:

| Permission | Risk | Catalog description |
|---|---|---|
| `network` | dangerous | Outbound network access. |
| `files.read` | normal | Read files inside the plugin’s own sandbox. |
| `files.write` | normal | Write files inside the plugin’s own sandbox. |
| `artifact.read` | normal | Read job artifacts. |
| `artifact.write` | normal | Write job artifacts. |
| `ui.progress` | normal | Emit progress events to the running job UI. |
| `network.install` | dangerous | Install packages from the network. |
| `package.install` | dangerous | Modify the runtime package database. |
| `terminal.access` | dangerous | Open an interactive terminal session. |
| `runtime.exec` | system | Execute arbitrary binaries in the runtime. |
| `storage.all` | system | Whole shared-storage access. |

Import approval grants every known permission the manifest requested. It also grants default `files.read`, `files.write`, `artifact.write`, and `ui.progress` entries even when undeclared. Grants live in the ViewModel’s in-memory `PermissionManager`; snapshot/restore APIs exist but the app does not persist or restore them.

Most importantly, the current action execution, dependency resolver, network, and filesystem paths do not call `PermissionManager.require`. These grants are not an enforcement boundary yet. The app itself holds Android `INTERNET`, and the embedded runtime can use it. Do not tell users that denying/omitting a ForgeKit permission blocks the underlying operation in `0.1.1`.

### Actual isolation

- Every plugin process runs as the ForgeKit Android app UID in the shared embedded prefix.
- Executable and working-directory paths are restricted to ForgeKit/Termux app-owned roots before process start.
- Archive extraction rejects traversal and symlinks.
- Installed plugins have separate directories, but ordinary Unix permissions do not isolate same-UID processes from sibling plugin trees or the shared Termux home/prefix.
- There are no per-plugin users, mount namespaces, containers, seccomp filters, syscall policy, or network firewall.
- Package install scripts execute inside the shared prefix and can affect every plugin.

### Paths visible to a plugin

The installed plugin root contains the package plus host-created `data`, `cache`, `environment`, `logs`, and `bin` directories. `FORGE_PACKAGE` points to this root. `FORGE_HOME` points to shared Termux home. `FORGEKIT_OUTPUT` points to the public `/sdcard/ForgeKit` output tree configured by the platform layer.

User-selected inputs are copied from an Android content URI into ForgeKit’s app cache and passed as real paths. The app does not retain the original content-URI permission as the plugin contract. Treat these copies as temporary and read them during the job.

The developer guide download is separate: ForgeKit writes `Download/ForgeKit/Docs.md`. Android 10+ uses the `MediaStore.Downloads` collection and publishes after the bytes are complete; Android 7–9 writes a temporary file and replaces it after flush, requiring legacy write permission.

### Secrets

There is a pattern-based `SecretRedactor` for common JSON-style key/value strings. The import provisioning timeline and its persisted provider lines use it, but it is not applied to every general plugin log and no production secret store exists. A manifest `password` input only masks the screen; its value enters the job input record today and can be persisted in SQLite. Do not pass secrets through manifest inputs until persistence/redaction is hardened.

The runtime `password` prompt is different: its response remains only in Compose memory long enough to be written to the process, and prompt events persist metadata/status without the response value. The plugin must still avoid printing or returning it. Never include secrets in logs, results, filenames, environment variables, committed signing material, or command-line arguments.
