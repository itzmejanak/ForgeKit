## Shipped platform capability matrix

### Android/runtime envelope

| Capability | Current state |
|---|---|
| Android UI | Kotlin/Jetpack Compose app; compile SDK 35, minimum SDK 24. |
| Distribution target | `targetSdk 28`, deliberately retained for execution of embedded runtime binaries from app data; this is not compatible with current Play target-SDK policy. |
| CPU ABI | `arm64-v8a` only. Other ABI packages/native entrypoints are unsupported. |
| Embedded environment | Bundled Termux `apt-android-7` arm64 bootstrap, verified and relocated into ForgeKit’s private data area. No external Termux app is required. |
| Process execution | Real `fork`/PTY/`execve`; pipe-style plugin sessions use a raw PTY, terminal uses a cooked PTY. |
| Terminal | Interactive shared embedded Bash with resize, ANSI/VT rendering, extra keys, scrollback, selection/copy, paste, pinch zoom, and signals. |
| Runtime families in manifest | `python`, `node`, `bash`, `binary`. Other installed interpreters can be used indirectly from a Bash action, but cannot be declared as the v1 runtime type. |
| Package providers | Termux `pkg`/`apt`, global `pip`, global `npm`. |
| Local plugin import | Shipped in the app through Android document picker. |
| Public plugin output | `$FORGEKIT_OUTPUT` resolves to `/sdcard/ForgeKit` when legacy storage access is granted/available. |
| Developer guide export | One merged `Download/ForgeKit/Docs.md`. |
| Persistence | Android SQLite job records/events; installed plugin files on private disk. Plugin registry metadata/trust/grants are not fully persisted. |

### Plugin source providers in code

The plugin registry module implements these provider classes:

- local directory scan and copy;
- direct HTTP/HTTPS URL download;
- strict HTTP registry index plus SHA-256 artifact verification;
- bearer-token private HTTP registry;
- Git clone/scan using a real host `git` executable.

These are library implementations, not all shipped app surfaces. The Android UI currently exposes only local document import. There is no configured official registry endpoint, marketplace screen, URL form, Git source form, or private-registry credential UI. Do not hand users a registry URL and claim ForgeKit can install it from the current UI.

The HTTP registry follows `GET <base>/index.json` with schema `forgekit.registry/v1`, then downloads the entry’s relative `file` and verifies its SHA-256. It does not follow redirects. Direct URL downloads do follow normal redirects and compute a digest, but have no publisher/source-published expected digest before full package validation. Git sources use the environment’s `git`, not the embedded runtime automatically.

### Android APIs not exposed to plugins

No plugin protocol bridge currently exposes notifications, camera, microphone, clipboard, share sheets, location, Bluetooth, sensors, contacts, arbitrary `Context`, or Android Activities. `terminal.access`, `network`, and storage-related permission IDs do not create such bridges. A plugin is a command-line process plus declarative Compose UI, not an Android extension package.

### Runtime inspection and repair

The runtime reports architecture, prefix/home/shell, package-manager availability, and a fixed probe set of executable names only when each exists. It runs `--version` to collect version text when possible. Health checks cover Bash executability, required environment, writable home/tmp, dpkg database, shell/dynamic-linker execution, and a native `env` execution. Repair wipes/re-extracts/reinitializes the runtime area from the bundled verified bootstrap; it does not restore plugin trees or user output.
