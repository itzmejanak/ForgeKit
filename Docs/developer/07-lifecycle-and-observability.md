## Import, plugin, job, and process lifecycle

### Import and installation

The shipped local-import flow is:

```text
Android document picker
→ cache quarantine copy
→ ZIP/manifest open
→ package validation and trust/permission/dependency facts
→ explicit user approval
→ staged extraction and installed-tree swap
→ sequential dependency provisioning
→ verification
→ READY marker or UNRESOLVED
```

Nothing from the selected package executes before approval. Validation may read and hash every package file. Rejected packages remain in the manager’s in-memory pending reviews so the screen can show findings, but the pending map does not survive process death.

Installed plugin state is mostly disk-derived. `READY` means `.forgekit-ready` exists; otherwise the installed tree is `UNRESOLVED`. Disable/enable domain methods exist, but the current Android UI does not persist a disabled state. Removal deletes the plugin tree and in-memory permission grants, but it does not remove shared dependencies.

### Job lifecycle

Each action or provisioning operation creates a SQLite job record immediately. Legal states are:

```text
QUEUED → PREPARING → INSTALLING_DEPENDENCIES → STARTING → RUNNING
RUNNING → PAUSED → RUNNING
non-terminal state → CANCELLING → CANCELLED
eligible non-terminal state → FAILED
RUNNING → COMPLETED
```

Provisioning-only jobs finish from `INSTALLING_DEPENDENCIES` as `COMPLETED` or `FAILED` and never start a plugin process. Action jobs provision first, then start only when no dependencies remain missing.

The Android store uses `android.database.sqlite` with `jobs` and append-only `job_events` tables, WAL mode, and foreign keys. Records include plugin/action/input/output/error/process ID and timestamps. Events include state transitions, logs, progress, prompt lifecycle metadata, completion, failure, and cancellation. Prompt response values are not persisted.

### Realtime provisioning detail

The import screen receives structured events for the full plan, provider phases, selected package-manager status lines, and verification result. The detail card shows per-dependency state plus a bounded, redacted event timeline. It is real observation, not simulated package-install percentages. Full raw apt/pip/npm output is not guaranteed: the runtime forwards lines classified as status-bearing and truncates each line.

### Cancellation, timeout, and recovery

Action cancellation asks the runtime for `SIGTERM`, waits five seconds in the embedded runtime, then escalates to `SIGKILL`. The default whole-job timeout is ten minutes. Dependency commands have their own two-minute default timeout.

There is no Android foreground service for plugin jobs in `0.1.1`. Navigating between screens keeps the ViewModel process job alive, but Android may kill the process in background. Job records survive; execution continuity does not. At next startup, a non-terminal record is failed when its runtime process is provably gone. Runtime process tracking itself is in-memory, so this is not a durable process reattachment design.

### Output semantics

Protocol `log` becomes a `plugin:<level>` event. Non-protocol process lines become diagnostic logs. Protocol progress is persisted. Result output JSON elements are converted to string values in the job record; nested objects/arrays are stored as JSON text, while quoted strings have surrounding quotes trimmed.

Output declarations are metadata only. ForgeKit does not copy returned paths into a managed artifact directory, validate output types, or ensure a path stays in an approved output root. Authors should write durable user results below `$FORGEKIT_OUTPUT`, use collision-safe filenames, flush/close before returning, and return the final absolute path.
