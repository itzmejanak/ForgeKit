# The `forgekit/1` Wire Protocol

The only application-level channel between the platform and plugin processes.
One JSON document per line (NDJSON) on the plugin's **stdout**; the platform
writes the invoke request to the plugin's **stdin**. stderr carries human
diagnostics and never participates in the protocol.

## Messages

Every message carries `protocol` (`forgekit/1`) and `requestId` (correlates
events with the invocation).

| type | direction | fields |
|---|---|---|
| `invoke` | platform → plugin | `action`, `input` (values keyed by control/option id) |
| `progress` | plugin → platform | `value` (0.0–1.0), `message?` |
| `log` | plugin → platform | `level` (info/warn/error), `message` |
| `result` | plugin → platform, terminal | `status` (success/failure), `output` (values keyed by declared output id) |
| `error` | plugin → platform, terminal | `code`, `message` |

`result` and `error` are terminal: the first one ends the invocation. A
message after a terminal event is a protocol violation.

## Example exchange

```jsonc
→ stdin
{"protocol":"forgekit/1","requestId":"job-7","type":"invoke","action":"greet","input":{"name":"ForgeKit"}}

← stdout (one per line, flushed immediately)
{"protocol":"forgekit/1","type":"progress","requestId":"job-7","value":0.25,"message":"reading input"}
{"protocol":"forgekit/1","type":"log","requestId":"job-7","level":"info","message":"greeting 8 character name"}
{"protocol":"forgekit/1","type":"progress","requestId":"job-7","value":1.0,"message":"done"}
{"protocol":"forgekit/1","type":"result","requestId":"job-7","status":"success","output":{"greeting":"Hello ForgeKit"}}
```

## Framing rules

- The codec is strict: unknown keys are rejected, wire id must match, values
  validated (`progress` bounded, `result.status` enumerated).
- Blank lines are skipped; everything else must parse or the stream fails
  loudly (`ProtocolError`), never silently.
- UTF-8 throughout; the parser tolerates split multibyte sequences across
  chunk boundaries.

## Lifecycle integration

The job manager writes the invoke line, collects stdout lines until the
terminal message (or process exit / timeout / kill), maps `progress` events to
job progress, `log` events to the job log, and the terminal message to
COMPLETED/FAILED. **Cancellation is host-side**: the platform kills the
process (SIGTERM, escalating to SIGKILL per §53) and records the job as
CANCELLED — there is deliberately no protocol-level cancel message.

## Environment

Plugin processes run with the Termux environment (`HOME`, `PATH`, `LD_LIBRARY_PATH`)
plus `FORGE_PACKAGE` (the plugin's install root) and `FORGE_HOME` (the Termux
home), `cwd` = the plugin root. Python plugins are launched as
`$PREFIX/bin/python <installRoot>/<entrypoint>`.
