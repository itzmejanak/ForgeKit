# The `.forge` Plugin Package

A `.forge` archive is a zip containing exactly these areas, in this order:

```
manifest.json      the manifest (see below) — REQUIRED, first entry
runtime/           entrypoint + everything it executes
ui/                declarative UI trees (forgekit.ui/v1)
HASHES             canonical hash manifest of every content entry
SIGNATURE.json     ed25519 signature over HASHES (absent when unsigned)
publisher.json     publisher identity + key fingerprint (with signature)
```

## Manifest (`forgekit.plugin/v1`)

```json
{
  "schema": "forgekit.plugin/v1",
  "id": "com.forgekit.hello",              // lowercase reverse-DNS
  "name": "Hello Protocol",
  "version": "1.0.0",                      // semver
  "description": "…",
  "author": "ForgeKit",
  "tags": ["demo"],
  "runtime": { "type": "python", "version": ">=3.6" },
  "entrypoint": "runtime/main.py",         // must live under runtime/
  "ui": { "entry": "ui/main.json" },       // optional
  "dependencies": {
    "termux": ["python", "ffmpeg"],        // resolved via pkg/apt
    "python": [ { "name": "r2pipe", "version": "==1.9.8", "source": "pypi" } ],
    "node": []
  },
  "permissions": ["ui.progress"],          // dotted lowercase, see SECURITY.md
  "actions": [
    {
      "id": "greet",                       // ^[a-z][a-z0-9_-]*$
      "title": "Greet",
      "description": "…",
      "inputs":  [ { "id": "name", "label": "Name", "type": "text", "required": true } ],
      "options": [ { "id": "arch", "label": "…", "type": "select",
                     "choices": ["ARM", "ARM64"], "default": "ARM" } ],
      "outputs": [ { "id": "greeting", "type": "value" } ]
    }
  ]
}
```

Validation is strict: unknown ids, malformed versions, paths escaping their
area, entrypoints outside `runtime/`, duplicate action ids — all rejected with
the exact violation before anything is installed.

## Lifecycle

1. **Quarantine** — the archive is copied out of reach of any execution path.
2. **Archive verification** — zip structure + entry CRCs + HASHES manifest.
3. **Manifest validation** — schema, ids, permissions, action wiring.
4. **Signature verification** — ed25519 over the canonical hash manifest.
5. **Review** — identity, provisioning and runtime-permission facts are shown
   to the user (import review screens).
6. **Approval** — install into `<pluginsRoot>/<id>/` with POSIX modes
   preserved; declared KNOWN permissions are granted, everything else stays
   sandboxed.

## Dependencies

Declared dependencies are resolved against the *live* runtime (never assumed):
the resolver inspects which tools exist in `$PREFIX/bin`, plans installs
(TERMUX via `pkg/apt`, PIP, NPM) for what is missing, executes them, and
re-verifies. The language runtime itself (`python`, `node`) is a TERMUX
dependency when the runtime does not already provide it.

## Building and inspecting

```bash
# build + sign (deterministic; byte-identical rebuilds)
./gradlew :tools:forge-builder:run --args="build plugin-examples/hello \
    --out hello.forge --key <64-hex-ed25519-seed> --name MyPublisher"

# inspect a package: identity, deps, permissions, warnings, verdict
./gradlew :tools:forge-validator:run --args="inspect hello.forge"
```

Exit codes: `forge inspect` → 0 valid, 1 invalid, 2 usage error.
