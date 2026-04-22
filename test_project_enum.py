#!/usr/bin/env python3
"""
Smoke-check a few current config-related HTTP endpoints against the local dev app.
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
    dump_json_response(requests.get(f"{BASE_URL}/api/config/projects"))

    print("\n=== project codes ===")
    dump_json_response(requests.get(f"{BASE_URL}/api/config/projects/codes"))

    print("\n=== validate demoApp ===")
    dump_json_response(requests.get(f"{BASE_URL}/api/config/projects/validate/demoApp"))


def test_add_project() -> None:
    print("\n=== add project ===")
    payload = {
        "code": "testApp",
        "name": "test-app",
    }
    response = requests.post(
        f"{BASE_URL}/api/config/projects",
        headers={"Content-Type": "application/json"},
        json=payload,
    )
    dump_json_response(response)

    print("\n=== project list after add ===")
    dump_json_response(requests.get(f"{BASE_URL}/api/config/projects"))


def test_delete_project() -> None:
    print("\n=== delete project ===")
    dump_json_response(requests.delete(f"{BASE_URL}/api/config/projects/testApp"))

    print("\n=== delete default project demoApp ===")
    dump_json_response(requests.delete(f"{BASE_URL}/api/config/projects/demoApp"))


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
    test_add_project()
    test_delete_project()
    test_config_page()

    print("\nsmoke checks complete")
