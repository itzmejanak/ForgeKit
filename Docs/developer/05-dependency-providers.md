## Dependency providers

ForgeKit resolves only three dependency kinds in `0.1.0`. Resolution always inspects first, emits a plan, installs missing items sequentially, and verifies provider state after each attempt. The UI prevents more than one provisioning flow from running at once.

| Manifest group | Provider label | Presence check | Install command |
|---|---|---|---|
| `dependencies.termux` and missing non-binary runtime | `TERMUX` | `dpkg-query --admindir=$PREFIX/var/lib/dpkg -W -f ${Status} <name>` ends in `installed` | `$PREFIX/bin/pkg install -y <name>`; falls back to `$PREFIX/bin/apt install -y <name>` when `pkg` is absent. |
| `dependencies.python` | `PIP` | `$PREFIX/bin/pip show <name>` contains `Name:` | `$PREFIX/bin/pip install <name><versionRequirement>` |
| `dependencies.node` | `NPM` | `$PREFIX/bin/npm ls -g --parseable <name>` returns non-blank output | `$PREFIX/bin/npm install -g <name><versionRequirement>` |

The runtime family is automatically added as a Termux package when `runtime.type` is not `binary`, the corresponding executable was not reported by runtime inspection, and the same name was not explicitly listed in `dependencies.termux`.

### Provider ownership and author duties

ForgeKit owns plan ordering, progress events, one-at-a-time orchestration, failure reporting, and final verification. The actual package managers own repository configuration, transitive resolution, scripts, native builds, network traffic, upgrades, and their package databases.

Plugin authors must:

- declare every command/package required on a clean ForgeKit runtime;
- include the language runtime explicitly when clarity matters, even though it can be auto-planned;
- use provider-native version syntax accepted by the manifest parser;
- test installation on the bundled arm64 Termux suite, not only desktop Linux;
- assume network/package repositories can be unavailable and return actionable protocol errors;
- license and audit transitive dependencies;
- never run an undeclared ad-hoc installer from the action as a substitute for provisioning.

### Current provider limitations

- Python packages install into the shared prefix with global `pip`; ForgeKit does not create/use `PluginLayout.pythonVenv` yet.
- Node packages install globally with `npm -g`; ForgeKit does not use the per-plugin `node_modules` placeholder yet.
- The Python/Node `source` field is shown in facts but does not change pip/npm arguments. Custom indexes, Git sources, local wheels/tarballs, integrity pins, lockfiles, and hashes are not provider features today.
- Termux package version pins are unsupported. Runtime and dependency versions are not compared after presence is confirmed.
- Dependency reference counting and removal are not implemented. Removing a plugin never uninstalls shared Termux/pip/npm dependencies.
- Provider operations have a default two-minute command timeout in the embedded runtime. A timeout or failed verification leaves the plugin installed but `UNRESOLVED`.
- Package-manager output is classified into `INSPECTING`, `FETCHING`, `UNPACKING`, `CONFIGURING`, and `VERIFYING` from real lines. The UI’s fractional bar is a coarse function of completed/current dependency count, not byte or time progress.

### No-dependency packages

A `binary` plugin with no declared dependencies has an empty plan, which is satisfied without running a package manager. The binary still must be arm64-compatible, executable, dynamically compatible with Android/Termux, and self-contained or linked against libraries already present.
