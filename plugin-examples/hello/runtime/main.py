#!/usr/bin/env python3
import json
import sys


def send(message):
    sys.stdout.write(json.dumps(message, separators=(",", ":")) + "\n")
    sys.stdout.flush()


def main():
    line = sys.stdin.readline()
    request = json.loads(line) if line else None
    if not request or request.get("type") != "invoke":
        send({"protocol": "forgekit/1", "type": "error", "requestId": "",
              "code": "BAD_REQUEST", "message": "expected invoke"})
        return 1
    request_id = request["requestId"]
    name = str(request.get("input", {}).get("name", "")).strip()
    if not name:
        send({"protocol": "forgekit/1", "type": "error", "requestId": request_id,
              "code": "MISSING_INPUT", "message": "the 'name' input is required"})
        return 1
    send({"protocol": "forgekit/1", "type": "progress", "requestId": request_id,
          "value": 0.25, "message": "reading input"})
    send({"protocol": "forgekit/1", "type": "log", "requestId": request_id,
          "level": "info", "message": "building greeting"})
    send({"protocol": "forgekit/1", "type": "progress", "requestId": request_id,
          "value": 1.0, "message": "done"})
    send({"protocol": "forgekit/1", "type": "result", "requestId": request_id,
          "status": "success", "output": {"greeting": "Hello " + name}})
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
