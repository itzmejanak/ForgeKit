## Runtime and `forgekit/1` protocol

Each action is a real process. ForgeKit writes one `invoke` line to the process’s standard input and keeps the channel available for host-mediated prompt responses. The plugin writes application messages as UTF-8 NDJSON: one JSON object followed by `\n`, flushed immediately.

### Invoke

```json
{"type":"invoke","protocol":"forgekit/1","requestId":"<job-id>","action":"greet","input":{"name":"Ada"}}
```

All values assembled by the current Android UI are JSON strings. The plugin must reject unknown actions, missing required values, invalid ranges, unsafe paths, and malformed content itself.

### Plugin-to-host messages

```json
{"type":"progress","protocol":"forgekit/1","requestId":"<job-id>","value":0.5,"message":"halfway"}
{"type":"log","protocol":"forgekit/1","requestId":"<job-id>","level":"info","message":"working"}
{"type":"result","protocol":"forgekit/1","requestId":"<job-id>","status":"success","output":{"answer":"42"}}
{"type":"error","protocol":"forgekit/1","requestId":"<job-id>","code":"INVALID_INPUT","message":"name is required"}
```

`progress.value` must be in `0.0..1.0`. `result.status` is exactly `success` or `failure`. `result` and `error` are terminal application messages. A successful job requires `result.status: "success"` and process exit code `0`. Exiting without a terminal message fails the job.

### Interactive prompts

An action may pause for one host-mediated prompt at a time:

```json
{"type":"prompt","protocol":"forgekit/1","requestId":"<job-id>","promptId":"overwrite","kind":"confirm","title":"Replace output?","message":"The destination exists.","required":true,"choices":[],"default":null,"placeholder":null}
```

Supported `kind` values are `confirm`, `text`, `password`, and `select`.

- `promptId` is 1–64 ASCII letters, digits, `.`, `_`, or `-`, beginning with a letter or digit. Keep it unique within the running job.
- `title` must be non-blank.
- `select` requires non-empty unique `choices`; other kinds must not send choices.
- `default`, when present for `select`, must be one of its choices.
- A `confirm` default is the string `"true"` or `"false"`. A `password` prompt must not have a default.
- Limits are 120 characters for title, 1,000 for message, 160 for placeholder, 32 choices, 160 characters per choice, 16,384 for a default/response, and 65,536 for one wire line.
- Only one unresolved prompt may exist per job. Different jobs can prompt concurrently and appear as tabs in ForgeKit’s bottom interaction dock.
- A prompt moves the job from `RUNNING` to `PAUSED`. A valid response moves it back to `RUNNING`.

The host responds on the same standard-input stream:

```json
{"type":"prompt_response","protocol":"forgekit/1","requestId":"<job-id>","promptId":"overwrite","status":"submitted","value":"true"}
```

or:

```json
{"type":"prompt_response","protocol":"forgekit/1","requestId":"<job-id>","promptId":"overwrite","status":"cancelled","value":null}
```

`status` is `submitted` or `cancelled`. A submitted `value` is a JSON string; a cancelled response has `null`. `confirm` submitted values are `"true"` or `"false"`; select values must be one declared choice. Password values are sent to the process but never included in prompt-resolution events or the job log. Cancellation answers the prompt; it does not automatically kill the job. The plugin must decide whether to continue or return an error. The job’s overall timeout still applies while a prompt is open.

### Stream behavior and diagnostics

The codec itself rejects unknown keys, unknown message types, wrong protocol IDs, and invalid field values. During a job, a stdout line that does not decode is currently stored as a diagnostic `stdout` log instead of immediately failing the process. Authors must still reserve stdout for protocol messages.

On Android, both plugin execution and package tools use a PTY. A PTY combines the child’s stdout and stderr into one stream, so stderr text is observed by the job manager as a non-protocol stdout diagnostic. Host JVM tests using ordinary pipes preserve separate stderr. Do not write secrets to either stream.

The job manager correlates protocol and prompt messages to the active job’s `requestId`; a mismatched ID is a protocol failure. Messages after a terminal message are invalid even if the process has not exited.

### Process environment

The runtime base environment includes:

```text
HOME PREFIX PATH LD_LIBRARY_PATH LD_PRELOAD (when libtermux-exec exists)
TMPDIR TERM TERMINFO LANG SHELL
TERMUX_APP__DATA_DIR TERMUX__ROOTFS TERMUX__HOME TERMUX__PREFIX
FORGEKIT_OUTPUT
```

Plugin actions additionally receive `FORGE_PACKAGE` (installed plugin root), `FORGE_HOME` (Termux home when using the embedded runtime), and `TERM=xterm-256color`. The working directory is the installed plugin root. The `PluginContext` type defines `FORGE_JOB_ID`, `FORGE_PLUGIN_ID`, `FORGE_WORKDIR`, and `FORGE_PROTOCOL`, but the current action-run assembly does not add those four variables; do not rely on them in `0.1.1`.

Executables and working directories are host-checked to remain under the ForgeKit app-owned root or Termux root. This is a path gate, not a per-plugin filesystem sandbox.
