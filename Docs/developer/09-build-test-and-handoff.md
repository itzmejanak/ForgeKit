## Build, inspect, test, and hand off

Run repository commands from the ForgeKit repository root. Use a local JDK compatible with the Gradle build; do not copy a machine-specific `JAVA_HOME` into scripts or package metadata.

### Build an unsigned package

```bash
./gradlew :tools:forge-builder:run --args="build /absolute/path/to/plugin-source --out /absolute/path/to/plugin.forge"
```

Always pass `--out`. Without it, the current CLI places `<source-directory-name>.forge` inside the source directory.

### Build a signed package

```bash
./gradlew :tools:forge-builder:run --args="build /absolute/path/to/plugin-source --out /absolute/path/to/plugin.forge --key <64-hex-private-seed> --name <publisher-name>"
```

`--key` is exactly 32 raw seed bytes represented as 64 hexadecimal characters. ForgeKit has no key-generation/storage CLI yet. Passing a seed on the command line can expose it in shell history and process listings; use this interface only in a controlled local/CI environment until secure key input exists. A publisher name containing JSON-special characters is not safely escaped by the current builder; use a simple alphanumeric label or omit `--name`.

### Inspect the artifact

```bash
./gradlew :tools:forge-validator:run --args="inspect /absolute/path/to/plugin.forge"
```

The actual subcommand is `inspect`, not `validate`.

Exit codes:

- `0`: package passes current static validation; output says whether approval is still required;
- `1`: package rejected or unreadable;
- `2`: CLI usage error.

Static inspection does not query the Android embedded runtime, fully cross-validate the UI, provision dependencies, execute package tests, or run actions. It reports dependency facts as missing because no live app runtime is supplied.

### Repository verification

The repository contains validator-backed, real-process examples in `plugin-examples/hello`, `plugin-examples/interactive`, and `plugin-examples/ssl-patcher`. The integration task builds and runs them through the production package/import/job seams; use them as the starting point instead of inventing schema fields.

```bash
./gradlew :plugin:manifest:test
./gradlew :plugin:installer:test
./gradlew :plugin:validator:test
./gradlew :plugin:protocol:test
./gradlew :plugin:resolver:test
./gradlew :job:manager:test
./gradlew :tests:integration:test
./gradlew test
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
```

The current `tools:forge-test` area has no executable compatibility-test CLI. Files under a package’s `tests/` directory are packaged but not automatically run. Authors must maintain and run their own tests plus exercise a real imported action on an arm64 Android device.

### Minimum action test matrix

- valid input returns the declared result and exit `0`;
- each required input missing/blank returns a typed `error`;
- malformed file/content returns a typed `error`, never a crash or silent result;
- progress stays within `0..1` and is monotonic where meaningful;
- cancellation terminates child processes and does not leave partial output advertised as complete;
- dependency-missing/offline/provider-timeout failures remain actionable;
- repeated execution avoids filename collisions and stale state;
- large output does not flood stdout with non-protocol data;
- every prompt kind used by the plugin submits and cancels correctly;
- a prompt waiting during navigation/rotation remains associated with the same job;
- secrets never appear in logs, results, filenames, command arguments, or handed-off evidence;
- output paths exist, are closed, and point to the final artifact.

### Handoff checklist

Give the user/operator:

- the immutable `.forge` file;
- its full SHA-256 from builder/validator output;
- source code and exact source revision;
- manifest/UI/protocol contract versions;
- required architecture (`arm64-v8a` today), runtime family, Termux packages, Python/Node packages, network needs, expected install size/time where measured, and offline behavior;
- every requested permission with a plain-language reason, plus a warning that `0.1.0` permissions are not a complete sandbox;
- supported actions, inputs, outputs, interactive prompts, and output location;
- validation output and real-device test evidence;
- known limitations, destructive behavior, external services/endpoints, bundled licenses, and update/removal implications;
- recovery instructions for `UNRESOLVED`, failed jobs, partial output, and runtime repair.

Never hand off the private signing seed, tokens, passwords, private registry credentials, personal cache paths, or unredacted logs.
