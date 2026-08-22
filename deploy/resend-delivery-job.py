#!/usr/bin/env python3
import json
import sys
import urllib.error
import urllib.request


def load_env_value(path, key):
    with open(path, encoding="utf-8") as env_file:
        for raw_line in env_file:
            line = raw_line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            name, value = line.split("=", 1)
            if name.strip() == key:
                return value.strip().strip('"').strip("'")
    raise RuntimeError(f"{key} is not configured")


if len(sys.argv) != 2:
    raise SystemExit("usage: resend-delivery-job.py JOB_ID")

job_id = sys.argv[1]
admin_password = load_env_value(
    "/home/lmo0317/apps/kidsnote/.env", "ANALYTICS_ADMIN_PASSWORD"
)
request = urllib.request.Request(
    f"http://127.0.0.1:9000/api/delivery/jobs/{job_id}/resend",
    data=b"{}",
    headers={
        "Content-Type": "application/json",
        "x-analytics-password": admin_password,
    },
    method="POST",
)

try:
    with urllib.request.urlopen(request, timeout=30) as response:
        result = json.load(response)
        print(json.dumps(result, ensure_ascii=False))
except urllib.error.HTTPError as error:
    body = error.read().decode("utf-8", errors="replace")
    raise SystemExit(f"resend failed ({error.code}): {body}")
