# ForgeKit Security Model

## Trust levels

| level | meaning | how it arises |
|---|---|---|
| `SIGNED_TRUSTED` | signed by a key in the trust store | publisher fingerprint match |
| `SIGNED_UNKNOWN` | valid signature, unknown publisher | verification passes, trust store miss |
| `UNSIGNED` | no SIGNATURE.json | import requires explicit approval |

Every package reports its level in the import review; approval is always an
explicit user action (§72 two-phase import). Trust is per-publisher-key, never
per-file.

## Package signing

`forge build` signs the canonical hash manifest (HASHES) with ed25519
(`core/security` PackageSigner). Verification: re-derive the canonical
manifest from package content → byte-compare → verify the signature against
the publisher key in `publisher.json`. Canonicalization means an attacker
cannot reorder entries or mutate modes without breaking the hash chain — the
same bytes build the same signature (deterministic packaging).

## Permissions

Permissions are dotted lowercase ids from a fixed catalog
(`PermissionCatalog`), split into two classes:

- **Runtime permissions** (e.g. `artifact.read`, `artifact.write`,
  `ui.progress`) — enforced while a plugin executes.
- **Provision permissions** (e.g. `network.install`, `package.install`) —
  grant the plugin's *setup* the right to change the environment.

The grant engine (`PermissionManager`) starts every plugin in a sandbox
default; the import review shows the KNOWN declared permissions and the user
approves exactly those (with expiry); everything else stays denied. Violations
surface as `SecurityViolation` with the precise missing set. Grants are
revocable per-plugin or all-at-once, and snapshot/restore survives restarts.

## Isolation

- Plugins run as the app uid, inside the app's data root — no more, and the
  SELinux targetSdk-28 exec policy is what makes the runtime possible at all.
- The plugin tree is extracted with **modes preserved** and validated before
  anything executes; content classification flags scripts/binaries in review
  (CONTENT_UNCLASSIFIED warnings).
- Execution goes through the PTY/process harness with the Termux environment;
  stdin carries only the protocol invoke, stdout only protocol output.

## Package integrity pipeline

quarantine → zip structure + deep CRC → manifest schema validation →
content classification → canonical hash manifest → ed25519 signature →
facts to the user → approval. A defect at any stage stops the import with a
precise violation (never a silent skip).

## What v0.1.0 does not claim

No per-plugin seccomp/namespace sandboxing yet, no permission-gated network
firewall, no certificate pinning for dependency downloads. The trust model,
signature chain, grant engine and review pipeline are real; OS-level
hardening is the next stage.
