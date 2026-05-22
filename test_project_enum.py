#!/usr/bin/env python3
"""
Smoke-check current runtime config endpoints against the local dev app.
"""

import json
import time

import requests

BASE_URL = "http://localhost:8088"


def dump_json_response(response: requests.Response) -> None:
    print(f"status: {response.status_code}")
    if response.status_code == 200:
        print(json.dumps(response.json(), indent=2, ensure_ascii=False))
    else:
        print(response.text)


def test_project_enum() -> None:
    print("=== project list ===")
    dump_json_response(requests.get(f"{BASE_URL}/api/v1/runtime/config/projects"))


def test_config_page() -> None:
    print("\n=== configs route ===")
    response = requests.get(f"{BASE_URL}/resources/configs")
    print(f"status: {response.status_code}")
    if response.status_code == 200:
        if '<div id="app"></div>' in response.text:
            print("ok: backend-hosted SPA shell detected")
        else:
            print("warn: SPA shell marker not found")
    else:
        print(response.text)


if __name__ == "__main__":
    print("starting config smoke checks...")
    print("waiting for app startup...")
    time.sleep(5)

    test_project_enum()
    test_config_page()

    print("\nsmoke checks complete")
