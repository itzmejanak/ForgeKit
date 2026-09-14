## Minimal working plugin

This is the smallest useful source tree. It needs no custom UI: ForgeKit builds the action form from the manifest.

```text
hello/
├── manifest.json
└── runtime/
    └── main.py
```

`manifest.json`:

```json
{
  "schema": "forgekit.plugin/v1",
  "id": "com.example.hello",
  "name": "Hello",
  "version": "1.0.0",
  "runtime": { "type": "python" },
  "entrypoint": "runtime/main.py",
  "actions": [
    {
      "id": "greet",
      "title": "Greet",
      "inputs": [
        { "id": "name", "label": "Name", "type": "text", "required": true }
      ],
      "outputs": [
        { "id": "greeting", "label": "Greeting", "type": "value" }
      ]
    }
  ]
}
```

`runtime/main.py`:

```python
#!/usr/bin/env python3
import json
import sys


def send(message):
    sys.stdout.write(json.dumps(message, separators=(",", ":")) + "\n")
    sys.stdout.flush()


line = sys.stdin.readline()
request = json.loads(line) if line else None
if not request or request.get("protocol") != "forgekit/1" or request.get("type") != "invoke":
    raise SystemExit(2)  # no safe requestId exists for a protocol error

request_id = request["requestId"]
if request.get("action") != "greet":
    send({"protocol": "forgekit/1", "type": "error", "requestId": request_id,
          "code": "UNKNOWN_ACTION", "message": "expected greet"})
    raise SystemExit(1)

name = str(request.get("input", {}).get("name", "")).strip()
if not name:
    send({"protocol": "forgekit/1", "type": "error", "requestId": request_id,
          "code": "MISSING_INPUT", "message": "name is required"})
    raise SystemExit(1)

send({"protocol": "forgekit/1", "type": "result", "requestId": request_id,
      "status": "success", "output": {"greeting": "Hello " + name}})
```

Build and inspect it from the ForgeKit repository:

```bash
./gradlew :tools:forge-builder:run --args="build /absolute/path/to/hello --out /absolute/path/to/hello.forge"
./gradlew :tools:forge-validator:run --args="inspect /absolute/path/to/hello.forge"
```

The manifest’s `python` runtime causes the resolver to plan the Termux `python` package when the executable is missing. After import and approval, ForgeKit provisions it before the first action process starts.

### Adding one interactive step

After reading `invoke`, emit a prompt and then read exactly one response line:

```python
send({
    "protocol": "forgekit/1",
    "type": "prompt",
    "requestId": request_id,
    "promptId": "overwrite",
    "kind": "confirm",
    "title": "Replace existing output?",
    "required": True
})

response = json.loads(sys.stdin.readline())
if (response.get("protocol") != "forgekit/1" or
        response.get("type") != "prompt_response" or
        response.get("requestId") != request_id or
        response.get("promptId") != "overwrite"):
    send({"protocol": "forgekit/1", "type": "error", "requestId": request_id,
          "code": "BAD_RESPONSE", "message": "invalid prompt response"})
    raise SystemExit(1)

if response.get("status") == "cancelled" or response.get("value") != "true":
    send({"protocol": "forgekit/1", "type": "error", "requestId": request_id,
          "code": "CANCELLED", "message": "output was not replaced"})
    raise SystemExit(1)
```

Never infer approval from missing fields, and never echo a password response. Continue to exactly one terminal `result` or `error` after the response.
