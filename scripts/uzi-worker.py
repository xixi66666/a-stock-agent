#!/usr/bin/env python3
"""Run the official UZI entry point and normalize its artifacts.

The Java service invokes this file with a fixed, bounded argument set.  It is
also useful as a small local smoke-test command after ``setup-uzi.ps1``.
Secrets are intentionally read only by the official UZI process from the
operator's environment; this wrapper never accepts or prints credentials.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable


SCHEMA = "uzi-bundle-v1"
MAX_JSON_BYTES = 10_000_000
MAX_CACHE_DEPTH = 5
DIMENSIONS = {
    "companyProfile": ("companyProfile", "company_profile", "0_basic", "basic", "company"),
    "competitors": ("competitors", "competitiveLandscape", "competitive_landscape", "5_competitor", "competitor"),
    "earningsForecast": ("earningsForecast", "profitForecast", "profit_forecast", "forecast", "earnings"),
    "structuredValuation": ("structuredValuation", "structured_valuation", "valuation", "dcf", "comps", "valuation_models"),
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run UZI and emit uzi-bundle-v1")
    parser.add_argument("--uzi-root", required=True, type=Path)
    parser.add_argument("--code", required=True)
    parser.add_argument("--depth", default="medium", choices=("lite", "medium", "deep"))
    parser.add_argument("--school", default="")
    parser.add_argument("--output-dir", required=True, type=Path)
    return parser.parse_args()


def validate(args: argparse.Namespace) -> tuple[Path, Path, str]:
    if not re.fullmatch(r"\d{6}", args.code):
        raise ValueError("证券代码无效")
    if args.school and not re.fullmatch(r"[A-I]", args.school):
        raise ValueError("UZI 投资流派无效")
    root = args.uzi_root.expanduser().resolve()
    output = args.output_dir.expanduser().resolve()
    run_py = root / "run.py"
    if not root.is_dir() or not run_py.is_file() or run_py.is_symlink():
        raise ValueError("UZI_ROOT_NOT_FOUND")
    output.mkdir(parents=True, exist_ok=True)
    if output.is_symlink():
        raise ValueError("UZI 输出目录不可为符号链接")
    return root, output, args.school


def run_official(root: Path, args: argparse.Namespace, output: Path) -> None:
    command = [
        sys.executable,
        str(root / "run.py"),
        args.code,
        "--depth",
        args.depth,
        "--no-browser",
        "--output-dir",
        str(output),
    ]
    if args.school:
        command.extend(("--school", args.school))
    environment = os.environ.copy()
    environment["PYTHONIOENCODING"] = "utf-8"
    environment["UZI_CLI_ONLY"] = "1"
    environment["UZI_NO_AUTO_OPEN"] = "1"
    completed = subprocess.run(
        command,
        check=False,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        env=environment,
    )
    if completed.returncode != 0:
        raise RuntimeError(f"官方 UZI 返回非零状态: {completed.returncode}")


def read_json(path: Path) -> Any | None:
    if not path.is_file() or path.is_symlink() or path.stat().st_size > MAX_JSON_BYTES:
        return None
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def artifact(root: Path, output: Path, code: str, filename: str) -> Any | None:
    cache_roots = [
        root / ".cache",
        root / "scripts" / ".cache",
        root / "skills" / "deep-analysis" / "scripts" / ".cache",
    ]
    for cache in cache_roots:
        candidates = [output / filename, cache / code / filename]
        for candidate in candidates:
            value = read_json(candidate)
            if value is not None:
                return value
        if not cache.is_dir() or cache.is_symlink():
            continue
        for path in cache.glob("**/" + filename):
            try:
                relative_depth = len(path.relative_to(cache).parts)
            except ValueError:
                continue
            if relative_depth <= MAX_CACHE_DEPTH:
                value = read_json(path)
                if value is not None:
                    return value
    return None


def first(mapping: Any, names: Iterable[str]) -> Any | None:
    if not isinstance(mapping, dict):
        return None
    for name in names:
        value = mapping.get(name)
        if value is not None:
            return value
    return None


def structure(dimensions: Any, raw_data: Any, gaps: list[str]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for output_key, aliases in DIMENSIONS.items():
        value = first(dimensions, aliases)
        if value is not None:
            result[output_key] = value
    if isinstance(dimensions, dict):
        result["dimensions"] = dimensions
    elif isinstance(raw_data, dict):
        result["rawData"] = raw_data
    for key in DIMENSIONS:
        if key not in result:
            gaps.append(key)
    return result


def sources(raw_data: Any, dimensions: Any) -> list[dict[str, str]]:
    source_rows = first(raw_data, ("sources", "data_sources", "references"))
    if source_rows is None:
        source_rows = first(dimensions, ("sources", "data_sources", "references"))
    if not isinstance(source_rows, list):
        return []
    result = []
    for row in source_rows:
        if not isinstance(row, dict):
            continue
        provider = text(row, ("provider", "source", "publisher"))
        url = text(row, ("url", "link", "uri"))
        if provider is None and url is None:
            continue
        result.append(
            {
                "dimension": text(row, ("dimension", "section", "name")) or "unknown",
                "provider": provider or "unknown",
                "url": url or "",
                "observedAt": text(row, ("observedAt", "observed_at", "date")) or "",
            }
        )
    return result


def text(mapping: Any, names: Iterable[str]) -> str | None:
    value = first(mapping, names)
    return value if isinstance(value, str) and value.strip() else None


def generated_at(metadata: Any) -> str:
    return text(metadata, ("generatedAt", "generated_at", "createdAt", "created_at")) or datetime.now(timezone.utc).isoformat()


def emit_bundle(root: Path, output: Path, args: argparse.Namespace) -> None:
    raw_data = artifact(root, output, args.code, "raw_data.json")
    dimensions = artifact(root, output, args.code, "dimensions.json")
    panel = artifact(root, output, args.code, "panel.json")
    synthesis = artifact(root, output, args.code, "synthesis.json")
    metadata = artifact(root, output, args.code, "report.meta.json")
    gaps = [name for name, value in (("rawData", raw_data), ("dimensions", dimensions), ("panel", panel), ("synthesis", synthesis)) if value is None]
    structured = structure(dimensions, raw_data, gaps)
    references = sources(raw_data, dimensions)
    if not references:
        gaps.append("sources")
    bundle = {
        "schema": SCHEMA,
        "ticker": args.code,
        "generatedAt": generated_at(metadata),
        "rawData": raw_data,
        "dimensions": dimensions,
        "panel": panel,
        "synthesis": synthesis,
        "structured": structured,
        "sources": references,
        "dataGaps": list(dict.fromkeys(gaps)),
        # The Java API returns this bundle to a browser; keep the report locator
        # relative so the host filesystem path is never exposed to the client.
        "reportPath": "index.html" if (output / "index.html").is_file() else None,
    }
    descriptor, temporary_name = tempfile.mkstemp(prefix="uzi-", suffix=".tmp", dir=output)
    os.close(descriptor)
    temporary = Path(temporary_name)
    try:
        temporary.write_text(json.dumps(bundle, ensure_ascii=False, indent=2), encoding="utf-8")
        os.replace(temporary, output / "bundle.json")
    finally:
        temporary.unlink(missing_ok=True)


def main() -> int:
    try:
        args = parse_args()
        root, output, school = validate(args)
        args.school = school
        run_official(root, args, output)
        emit_bundle(root, output, args)
        return 0
    except (OSError, ValueError, RuntimeError, json.JSONDecodeError) as failure:
        print(str(failure), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
