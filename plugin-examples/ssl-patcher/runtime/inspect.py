#!/usr/bin/env python3
import hashlib
import json
import os
import sys
import zipfile


ARCH_DIRS = {"Auto detect": None, "ARM": "lib/armeabi-v7a", "ARM64": "lib/arm64-v8a", "x86": "lib/x86", "x64": "lib/x86_64"}


def send(message):
    sys.stdout.write(json.dumps(message, separators=(",", ":")) + "\n")
    sys.stdout.flush()


def main():
    line = sys.stdin.readline()
    request = json.loads(line) if line else None
    if not request or request.get("type") != "invoke":
        return 1
    request_id = request["requestId"]
    target = request.get("input", {}).get("target", "")
    arch_choice = request.get("input", {}).get("arch", "Auto detect")
    if not target or not os.path.isfile(target):
        send({"protocol": "forgekit/1", "type": "error", "requestId": request_id, "code": "TARGET_MISSING", "message": "target is not a readable file"})
        return 1
    digest = hashlib.sha256()
    with open(target, "rb") as stream:
        for chunk in iter(lambda: stream.read(65536), b""):
            digest.update(chunk)
    try:
        archive = zipfile.ZipFile(target)
    except zipfile.BadZipFile:
        send({"protocol": "forgekit/1", "type": "error", "requestId": request_id, "code": "NOT_AN_APK", "message": "target is not a zip archive"})
        return 1
    with archive:
        names = archive.namelist()
        selected = ARCH_DIRS.get(arch_choice)
        if selected is None:
            abis = sorted({name.split("/")[1] for name in names if name.startswith("lib/") and name.count("/") >= 2})
            abi = abis[0] if abis else "none"
            selected = "lib/" + abi if abis else ""
        else:
            abi = selected.split("/")[-1]
        libs = sorted(os.path.basename(name) for name in names if selected and name.startswith(selected + "/") and not name.endswith("/"))
    plan_path = os.path.abspath(target + ".forgekit-plan.json")
    with open(plan_path, "w", encoding="utf-8") as stream:
        json.dump({"schema": "forgekit.patchplan/v1", "targetSha256": digest.hexdigest(), "abi": abi, "libraries": libs}, stream, indent=2)
    send({"protocol": "forgekit/1", "type": "result", "requestId": request_id, "status": "success", "output": {"report": f"{len(libs)} libs ({abi})", "plan": plan_path}})
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
