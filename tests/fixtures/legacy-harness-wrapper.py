#!/usr/bin/env python
"""Shared harness-call helper for this test run.

The python client prints a short confirmation line followed by the full receipt
object, so json.loads on the whole stdout fails; this scans for the last
complete JSON object that carries a "tool" field.
"""
import json
import os
import subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
CONN = "D:/123/.minecraft/versions/All the Mods 9 - To the Sky - atm9sky/config/logistics-companion/harness-connection.json"
PY = "C:/Python314/python.exe"
CLIENT = "D:/Companion mod/companion-mod/harness-client.py"
RECEIPTS = os.path.join(HERE, "..", "receipts")


def parse(out):
    """Return the receipt object.

    0.7.8.36 supports --json, so the client prints one JSON object per line
    (ack line first, full receipt second). Parsing line by line avoids the
    brace-counting trap: `result` is an escaped JSON string whose inner braces
    unbalance a naive scanner.
    """
    for line in reversed(out.splitlines()):
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            cand = json.loads(line)
        except Exception:
            continue
        if isinstance(cand, dict) and "tool" in cand:
            return cand
    # fallback: the whole stdout may still be a single object
    try:
        cand = json.loads(out)
        if isinstance(cand, dict):
            return cand
    except Exception:
        pass
    return None


def call(tool, args, rid, wait=25, save=True):
    af = os.path.join(HERE, "_tmp_args.json")
    with open(af, "w", encoding="utf-8") as f:
        json.dump(args, f)
    p = subprocess.run([PY, "-X", "utf8", CLIENT, "--connection", CONN, "action", tool,
                        "--args-file", af, "--request-id", rid, "--wait", str(wait), "--json"],
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    obj = parse(p.stdout.strip())
    if p.returncode != 0 or obj is None:
        payload = {"error": None, "reason": None}
        if isinstance(obj, dict):
            payload["error"] = obj.get("error")
            payload["reason"] = obj.get("reason")
        if not payload["error"]:
            payload["error"] = "harness client exited with code %d and printed no parseable JSON" % p.returncode
        if not payload["reason"]:
            payload["reason"] = ("exit_%d" % p.returncode) if p.returncode else "unparseable_output"
        tail = (p.stderr or "").strip()
        if tail:
            payload["stderr_tail"] = tail[-400:]
        print(json.dumps(payload, ensure_ascii=False))
        raise SystemExit(1)
    if save:
        os.makedirs(RECEIPTS, exist_ok=True)
        with open(os.path.join(RECEIPTS, rid + ".json"), "w", encoding="utf-8") as f:
            json.dump(obj, f, ensure_ascii=False, indent=2)
    return obj


def inner(result):
    if isinstance(result, str):
        s = result.strip()
        if s.startswith("{") or s.startswith("["):
            try:
                return json.loads(s)
            except Exception:
                return None
    return result
