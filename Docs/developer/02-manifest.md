## Manifest contract

The decoder is strict: an unknown key at any nested level, wrong JSON type, or missing required field rejects the manifest. Defaults below apply only where shown.

### Complete shape

```json
{
  "schema": "forgekit.plugin/v1",
  "id": "com.example.hello",
  "name": "Hello",
  "version": "1.0.0",
  "description": "Optional description",
  "author": "Optional author label",
  "tags": ["example"],
  "runtime": { "type": "python", "version": ">=3.11" },
  "entrypoint": "runtime/main.py",
  "ui": { "entry": "ui/main.json" },
  "dependencies": {
    "termux": ["python"],
    "python": [{ "name": "requests", "version": "==2.32.3", "source": "pypi" }],
    "node": []
  },
  "permissions": ["network", "ui.progress"],
  "actions": [
    {
      "id": "greet",
      "title": "Greet",
      "description": "Return a greeting",
      "inputs": [
        {
          "id": "name",
          "label": "Name",
          "type": "text",
          "required": true,
          "choices": [],
          "constraints": null,
          "description": "Who to greet"
        }
      ],
      "options": [
        {
          "id": "style",
          "label": "Style",
          "type": "select",
          "choices": ["short", "formal"],
          "default": "short",
          "mapsTo": null
        }
      ],
      "outputs": [
        { "id": "greeting", "label": "Greeting", "type": "value", "description": null }
      ]
    }
  ]
}
```

### Top-level fields

| Field | Required | Enforced contract |
|---|---:|---|
| `schema` | Yes | Exactly `forgekit.plugin/v1`. |
| `id` | Yes | Lowercase reverse-domain ID with at least two segments. Each segment begins with `a-z`; remaining characters are `a-z`, `0-9`, or `_`. Hyphens are not accepted. |
| `name` | Yes | Non-blank string. |
| `version` | Yes | Semantic version accepted by ForgeKit’s `Version` value type: `major.minor.patch`, with supported prerelease/build syntax. Use a plain three-part version for portability. |
| `description`, `author` | No | Free strings; no additional validation. |
| `tags` | No | String list; defaults to empty. |
| `runtime` | Yes | Runtime object described below. |
| `entrypoint` | Yes | Relative canonical path whose first segment is `runtime`; it must exist in the archive. |
| `ui` | No | `{ "entry": "ui/..." }`; path must exist when declared. |
| `dependencies` | No | Defaults to all-empty provider lists. |
| `permissions` | No | Unique lowercase dotted IDs; every ID must also exist in the fixed catalog to pass package validation. |
| `actions` | No | Defaults to empty. A package with no actions is valid. |

There is no `rules`, `protocol`, minimum ForgeKit version, initialization hook, service, background mode, secret, source registry, or Android component field in `forgekit.plugin/v1`. Adding any such field currently rejects the manifest.

### Runtime

`runtime.type` must be exactly `python`, `node`, `bash`, or `binary`.

- `python`, `node`, and `bash` are launched through `$PREFIX/bin/<type>` with the absolute entrypoint as the only argument.
- `binary` launches the entrypoint directly and therefore requires an executable POSIX mode in the archive.
- `runtime.version` is optional. Its syntax is one optional comparator (`>=`, `<=`, `>`, `<`, or `=`) plus one to three numeric components.

The Android app currently creates its package validation context without detected runtime versions. Consequently, syntactically valid runtime requirements are not checked during import, and the dependency resolver does not verify installed runtime versions. Treat a runtime version as author metadata until that hardening gap is closed.

### Dependencies

- `termux` is a list of package names matching `[a-z0-9][a-z0-9+.-]*`. Duplicates are rejected. No Termux version pin field exists.
- `python` is a list of objects, never bare strings. `name` follows Python package-name characters. Optional `version` begins with `==`, `>=`, `<=`, `>`, `<`, or `=` and contains word/dot characters. Optional `source` is accepted as a string.
- `node` is a list of objects. `name` must be non-blank. Optional `version` may begin with `~`, `^`, `==`, `>=`, `<=`, `>`, `<`, or `=`. Optional `source` is accepted as a string.

Python and Node duplicate names are not rejected today; author rule: declare each once. Provider `source` is displayed as metadata but not used to select an index or registry during installation.

### Actions, inputs, options, and outputs

Action, input, option, output, and UI block IDs must begin with lowercase `a-z` and continue with lowercase letters, digits, `_`, or `-`. Action IDs are unique across the manifest. Input IDs and option IDs are unique within their action.

Supported input `type` values are:

```text
text password number select checkbox switch slider file directory date time
```

A `select` input requires non-empty `choices`. Other input types may carry choices but the renderer ignores them. `constraints` accepts any JSON object but current invocation paths do not enforce min/max/pattern. Do not depend on it for safety.

An option requires non-empty `choices`. `type` defaults to `select`, but the validator does not currently reject other strings. `default` is not checked against choices. `mapsTo` is parsed but not applied: protocol input is always keyed by the option’s own `id`. Author rules: use `type: "select"`, keep defaults inside choices, and leave `mapsTo` absent.

Output `type` must be `file`, `directory`, `value`, or `table`. Output IDs are format-checked but duplicates are not currently rejected. Author rule: make them unique. The host stores returned outputs as strings; it does not verify that a `file` or `directory` path exists or that a table has a particular shape.
