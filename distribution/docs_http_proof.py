"""Finite GET-only witness for documentation served from a supplied frontend."""
from __future__ import annotations

from pathlib import Path
import re
import urllib.error
import urllib.request

from distribution.archive_tools import DOCUMENTATION_PAGES, documentation_page


def _request(base: str, path: str) -> tuple[int, bytes, str]:
    try:
        with urllib.request.urlopen(base + path, timeout=5) as response:
            return response.status, response.read(), response.headers.get_content_type()
    except urllib.error.HTTPError as error:
        return error.code, error.read(), error.headers.get_content_type()


def verify(base: str, frontend: Path, expected_commit: str | None = None) -> dict:
    pages = {"/docs": "docs/index.html", "/docs/": "docs/index.html",
             **{"/" + path: path for path in DOCUMENTATION_PAGES}}
    assets = set()
    for route, path in pages.items():
        expected = (frontend / path).read_bytes()
        commit, references = documentation_page(expected, path)
        if not commit or not re.fullmatch(r"[0-9a-f]{40}", commit):
            raise AssertionError("Documentation has no complete build identity: " + path)
        expected_commit = expected_commit or commit
        if commit != expected_commit:
            raise AssertionError("Documentation build identity differs: " + path)
        status, content, content_type = _request(base, route)
        if status != 200 or content_type != "text/html" or content != expected:
            raise AssertionError("Documentation page is not served from the supplied frontend: " + route)
        assets.update(references)
    if not any(path.endswith(".js") for path in assets) or not any(path.endswith(".css") for path in assets):
        raise AssertionError("Documentation has no local script or stylesheet")
    for path in sorted(assets):
        expected = (frontend / path).read_bytes()
        status, content, content_type = _request(base, "/" + path)
        if status != 200 or content != expected or content_type == "text/html":
            raise AssertionError("Documentation asset is not served from the supplied frontend: " + path)
    for path in ("/docs/missing-page.html", "/docs/assets/missing.js"):
        if _request(base, path)[0] != 404:
            raise AssertionError("Missing documentation resource was swallowed by a page fallback: " + path)
    return {"pages": len(pages), "assets": len(assets), "gitCommit": expected_commit}
