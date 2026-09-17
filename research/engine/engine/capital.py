"""Minimal read-only Capital.com demo API client (market details, prices)."""

from __future__ import annotations

import json
import urllib.request
from pathlib import Path

DEMO_BASE = "https://demo-api-capital.backend-capital.com/api/v1"


def _read_env(env_path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in env_path.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip().strip('"').strip("'")
    return values


class DemoClient:
    def __init__(self, env_path: Path):
        env = _read_env(env_path)
        body = json.dumps({"identifier": env["CAPITAL_API_USER"], "password": env["CAPITAL_API_PASSWORD"]}).encode()
        request = urllib.request.Request(
            DEMO_BASE + "/session",
            data=body,
            headers={"X-CAP-API-KEY": env["CAPITAL_API_KEY"], "Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(request) as response:
            self._headers = {"CST": response.headers["CST"], "X-SECURITY-TOKEN": response.headers["X-SECURITY-TOKEN"]}

    def get(self, path: str) -> dict:
        request = urllib.request.Request(DEMO_BASE + path, headers=self._headers)
        with urllib.request.urlopen(request) as response:
            return json.load(response)
