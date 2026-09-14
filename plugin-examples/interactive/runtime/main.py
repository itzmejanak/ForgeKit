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
        return 1
    request_id = request["requestId"]
    send({
        "protocol": "forgekit/1",
        "type": "prompt",
        "requestId": request_id,
        "promptId": "access-token",
        "kind": "password",
        "title": "Access token",
        "message": "Enter the one-use token supplied by your provider.",
        "required": True,
    })
    response_line = sys.stdin.readline()
    response = json.loads(response_line) if response_line else None
    if not response or response.get("type") != "prompt_response":
        send({"protocol": "forgekit/1", "type": "error", "requestId": request_id,
              "code": "BAD_RESPONSE", "message": "expected prompt_response"})
        return 1
    if response.get("promptId") != "access-token" or response.get("status") != "submitted":
        send({"protocol": "forgekit/1", "type": "error", "requestId": request_id,
              "code": "CANCELLED", "message": "authorization was cancelled"})
        return 1
    # Use the value in memory only. Never echo it to stdout/stderr or output.
    accepted = bool(response.get("value"))
    send({"protocol": "forgekit/1", "type": "result", "requestId": request_id,
          "status": "success", "output": {"accepted": str(accepted).lower()}})
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
