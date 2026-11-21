#!/usr/bin/env python3
"""Flyway 迁移纪律校验。

规则：
1. 版本号唯一（全局），文件名形如 V<3位数字>__<描述>.sql；
2. 版本段与模块归属一致：identity 1xx / payment 2xx / platform 3xx / llm 4xx / metering 5xx；
   6xx 预留给跨模块/内核增量（目前未使用）；
3. 已发布的迁移内容不可变：`--verify` 与 docs/generated/flyway-checksums.json 比对 sha256；
   新增迁移必须显式 `--write-manifest` 更新清单（评审时能看到新增版本）。

用法：
    python3 tools/flyway_discipline.py --verify
    python3 tools/flyway_discipline.py --write-manifest
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "docs/generated/flyway-checksums.json"
MIGRATION_GLOB = "server/*/src/main/resources/db/migration/*/V*.sql"
FILENAME_RE = re.compile(r"^V(\d{3})__(.+)\.sql$")

# 模块 → (版本段起, 版本段止)
SEGMENTS = {
    "identity": (100, 199),
    "payment": (200, 299),
    "platform": (300, 399),
    "llm": (400, 499),
    "metering": (500, 599),
}
CROSS_MODULE_START, CROSS_MODULE_END = 600, 699


def module_of(path: Path) -> str:
    # server/atry-identity/src/main/resources/db/migration/identity/V100__x.sql
    parts = path.relative_to(ROOT).parts
    module_dir = parts[1]  # atry-identity
    return module_dir.removeprefix("atry-")


def collect() -> tuple[dict[str, dict], list[str]]:
    problems: list[str] = []
    migrations: dict[str, dict] = {}
    for path in sorted(ROOT.glob(MIGRATION_GLOB)):
        match = FILENAME_RE.match(path.name)
        if not match:
            problems.append(f"迁移文件名不合规：{path.relative_to(ROOT)}")
            continue
        version = int(match.group(1))
        module = module_of(path)
        migration_dir = path.parent.name
        if migration_dir != module:
            problems.append(f"{path.relative_to(ROOT)} 目录({migration_dir})与模块({module})不一致")
        if module not in SEGMENTS:
            problems.append(f"{path.relative_to(ROOT)} 未知模块 {module}")
        else:
            low, high = SEGMENTS[module]
            if not (low <= version <= high):
                problems.append(
                    f"{path.relative_to(ROOT)} 版本 {version} 不在 {module} 段 {low}-{high}"
                )
        if path.stem in migrations:
            problems.append(f"版本重复：{path.name}")
        migrations[path.stem] = {
            "path": str(path.relative_to(ROOT)),
            "module": module,
            "version": version,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        }
    if not migrations:
        problems.append("未找到任何迁移文件")
    return migrations, problems


def load_manifest() -> dict[str, str]:
    if not MANIFEST.exists():
        return {}
    payload = json.loads(MANIFEST.read_text(encoding="utf-8"))
    return payload.get("migrations", {})


def verify(migrations: dict[str, dict], problems: list[str]) -> list[str]:
    manifest = load_manifest()
    if not manifest:
        problems.append(f"缺少校验清单：{MANIFEST.relative_to(ROOT)}（先运行 --write-manifest）")
        return problems
    for name, item in migrations.items():
        known = manifest.get(name)
        if known is None:
            problems.append(f"新增迁移未登记：{name}（运行 --write-manifest 并提交）")
        elif known != item["sha256"]:
            problems.append(f"已发布迁移被修改：{name}（禁止修改历史迁移，新增一个迁移）")
    for name in manifest:
        if name not in migrations:
            problems.append(f"清单里的迁移已不存在：{name}")
    return problems


def write_manifest(migrations: dict[str, dict]) -> None:
    payload = {"migrations": {name: item["sha256"] for name, item in sorted(migrations.items())}}
    MANIFEST.parent.mkdir(parents=True, exist_ok=True)
    MANIFEST.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"written: {MANIFEST.relative_to(ROOT)} ({len(migrations)} migrations)")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--verify", action="store_true", help="校验（CI 用）")
    parser.add_argument("--write-manifest", action="store_true", help="重写校验清单")
    args = parser.parse_args()

    migrations, problems = collect()
    if args.write_manifest:
        if problems:
            for problem in problems:
                print(f"  - {problem}", file=sys.stderr)
            return 1
        write_manifest(migrations)
        return 0
    if not args.verify:
        parser.error("需要 --verify 或 --write-manifest")
    problems = verify(migrations, problems)
    if problems:
        print("Flyway 迁移纪律检查失败：", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1
    print(f"Flyway 迁移纪律检查通过：{len(migrations)} 个迁移，版本段与校验和均合规")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
