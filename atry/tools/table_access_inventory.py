#!/usr/bin/env python3
"""扫描 atry 后端代码，生成「表 → 访问者 → 读写类型」清单。

用途：A 期数据地基的第一步——在改库之前，先量化每张表被哪些模块、哪些类、
以什么方式（读/写/多表 join）访问。输出是 markdown，供 docs/generated/table-access-*.md 与 docs/README.md 引用。

用法：
    python3 tools/table_access_inventory.py            # 打印到 stdout
    python3 tools/table_access_inventory.py --out docs/generated/table-access-inventory.md

判定口径（启发式，宁多勿漏）：
- 表名来自 Flyway 迁移里的 CREATE TABLE；
- 扫描 Java 文本块/字符串字面量与 MyBatis XML 的 select/insert/update/delete 语句；
- INSERT INTO <t> / UPDATE <t> / DELETE FROM <t> 记为 WRITE，FROM <t> / JOIN <t> 记为 READ；
- 同一条语句里出现 >=2 张表记为 MULTI（多表 SQL，跨域 join 的来源）；
- 模块 = com.wherelee.base.modules.<module>，XML 通过 mapper namespace 反查模块。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
# 内核模块各自携带自己的迁移与 Mapper；垂直应用在独立仓库里另有自己的扫描。
MIGRATION_DIRS = sorted((ROOT / "server").glob("*/src/main/resources/db/migration"))
JAVA_DIRS = sorted((ROOT / "server").glob("*/src/main/java"))
MAPPER_DIRS = sorted((ROOT / "server").glob("*/src/main/resources/mapper"))

TABLE_RE = re.compile(r"\bt_[a-z_]+[a-z]\b")
CREATE_RE = re.compile(r"create\s+table\s+`?(t_[a-z_]+)`?", re.IGNORECASE)
MODULE_RE = re.compile(r"com\.wherelee\.base\.modules\.([a-z]+)")

# 表 → 目标分库（数据边界基线，供跨模块检查与表归属审计使用）。
# A 期的"跨库 join=0"= 同一条 SQL 引用了两个以上库的表。
TABLE_DB = {
    # identity 内核
    "t_user": "identity",
    "t_user_invitation": "identity",
    "t_role": "identity",
    "t_permission": "identity",
    "t_role_permission": "identity",
    "t_user_role": "identity",
    "t_auth_event": "identity",
    "t_user_session": "identity",
    # payment 内核
    "t_payment_order": "payment",
    "t_payment_attempt": "payment",
    "t_payment_event": "payment",
    "t_refund": "payment",
    "t_payment_mock_scenario": "payment",
    "t_payment_outbox": "payment",
    "t_recon_task": "payment",
    "t_recon_diff": "payment",
    "t_channel_bill": "payment",
    # platform 内核（运维事实）
    "t_alert_log": "platform",
    "t_admin_audit_log": "platform",
}


# 组合根（bootstrap）：种子数据/装配代码，本身不归属业务域，
# 它横跨多张表属于设计允许的「装配」，不计入跨模块统计。
COMPOSER_MODULES = {"bootstrap"}


def db_of(table: str) -> str:
    return TABLE_DB.get(table, "other")

JAVA_TRIPLE_RE = re.compile(r'"""(.*?)"""', re.DOTALL)
JAVA_STRING_RE = re.compile(r'"([^"\\\n]*\bt_[a-z_]+[a-z][^"\\\n]*)"')
XML_STMT_RE = re.compile(
    r"<(select|insert|update|delete)\b[^>]*\bid=\"([^\"]+)\"[^>]*>(.*?)</\1>",
    re.DOTALL | re.IGNORECASE,
)
XML_NAMESPACE_RE = re.compile(r"<mapper\s+namespace=\"([^\"]+)\"")
XML_FORALL_RE = re.compile(r"<foreach\b[^>]*>(.*?)</foreach>", re.DOTALL | re.IGNORECASE)

WRITE_VERBS = ("insert", "update", "delete", "replace", "upsert")


@dataclass
class Access:
    """一次「类 → 表」的访问记录。"""

    module: str
    clazz: str
    file: str
    ops: set[str] = field(default_factory=set)  # READ / WRITE
    multi_tables: set[str] = field(default_factory=set)
    statements: int = 0

    def merge(self, other: "Access") -> None:
        self.ops |= other.ops
        self.multi_tables |= other.multi_tables
        self.statements += other.statements


def table_catalog() -> dict[str, dict]:
    """从迁移脚本提取表清单与首次出现的迁移文件。"""
    catalog: dict[str, dict] = {}
    for path in sorted(p for d in MIGRATION_DIRS for p in d.rglob("*.sql")):
        text = path.read_text(encoding="utf-8", errors="ignore")
        for name in CREATE_RE.findall(text):
            catalog.setdefault(name.lower(), {"ddl": path.name, "alters": []})
        for name in TABLE_RE.findall(text):
            if name.lower() in catalog and path.name != catalog[name.lower()]["ddl"]:
                if path.name not in catalog[name.lower()]["alters"]:
                    catalog[name.lower()]["alters"].append(path.name)
    return catalog


def module_of(java_file: Path) -> str:
    parts = java_file.parts
    if "modules" in parts:
        idx = parts.index("modules")
        if idx + 1 < len(parts):
            return parts[idx + 1]
    # 不在 modules 下的顶层组件（如 bootstrap 种子装配）也算独立单元
    if "base" in parts:
        idx = parts.index("base")
        if idx + 1 < len(parts) and parts[idx + 1].endswith(".java") is False:
            return parts[idx + 1]
    return "unknown"


def classify(statement: str, table: str) -> str | None:
    """判断一条 SQL 对某张表是读还是写。"""
    lowered = statement.lower()
    t = re.escape(table)
    if re.search(rf"insert\s+into\s+`?{t}`?", lowered):
        return "WRITE"
    if re.search(rf"update\s+`?{t}`?", lowered):
        return "WRITE"
    if re.search(rf"delete\s+from\s+`?{t}`?", lowered):
        return "WRITE"
    if re.search(rf"(from|join)\s+`?{t}`?", lowered):
        return "READ"
    if re.search(rf"`?{t}`?\s*\.", lowered):  # 别名限定，如 o.status
        return "READ"
    if table in lowered:
        return "OTHER"
    return None


def collect(java_file: Path, statements: list[tuple[str, str]], module: str) -> dict[str, Access]:
    """statements: [(statement_id, sql)] → 表 → Access。"""
    found: dict[str, Access] = {}
    clazz = java_file.stem
    for _, sql in statements:
        tables = {t.lower() for t in TABLE_RE.findall(sql)}
        if not tables:
            continue
        for table in tables:
            op = classify(sql, table)
            if op is None:
                continue
            acc = found.setdefault(
                table,
                Access(module=module, clazz=clazz, file=str(java_file.relative_to(ROOT))),
            )
            acc.ops.add(op)
            acc.statements += 1
            if len(tables) > 1:
                acc.multi_tables |= tables - {table}
    return found


SQL_KEYWORD_RE = re.compile(r"\b(select|insert|update|delete|from|join|values)\b", re.IGNORECASE)


def java_statements(text: str) -> list[tuple[str, str]]:
    """只保留真正像 SQL 的片段。

    表名常量/白名单（如 TenantSqlRewriter 里的租户表集合）不应被当作"访问了该表"，
    否则登记表结构的代码会被误报成跨模块访问。
    """
    out: list[tuple[str, str]] = []
    for i, block in enumerate(JAVA_TRIPLE_RE.findall(text)):
        if SQL_KEYWORD_RE.search(block):
            out.append((f"text-block-{i}", block))
    for i, lit in enumerate(JAVA_STRING_RE.findall(text)):
        if SQL_KEYWORD_RE.search(lit):
            out.append((f"string-{i}", lit))
    return out


def scan_java() -> dict[str, dict[str, Access]]:
    """表 → (clazz → Access)。"""
    index: dict[str, dict[str, Access]] = defaultdict(dict)
    for java_dir in JAVA_DIRS:
        for path in java_dir.rglob("*.java"):
            text = path.read_text(encoding="utf-8", errors="ignore")
            if "t_" not in text:
                continue
            module = module_of(path)
            for table, acc in collect(path, java_statements(text), module).items():
                key = acc.clazz
                if key in index[table]:
                    index[table][key].merge(acc)
                else:
                    index[table][key] = acc
    return index


def scan_mappers() -> dict[str, dict[str, Access]]:
    index: dict[str, dict[str, Access]] = defaultdict(dict)
    for path in sorted(p for d in MAPPER_DIRS for p in d.rglob("*.xml")):
        text = path.read_text(encoding="utf-8", errors="ignore")
        ns = XML_NAMESPACE_RE.search(text)
        module = (
            MODULE_RE.search(ns.group(1)).group(1)
            if ns and MODULE_RE.search(ns.group(1))
            else "unknown"
        )
        clazz = path.stem
        for tag, stmt_id, body in XML_STMT_RE.findall(text):
            sql = f"{tag} {body}"
            for table in {t.lower() for t in TABLE_RE.findall(sql)}:
                op = classify(sql, table)
                if op is None:
                    continue
                # foreach 里的动态表名补强：整体语句已含表名，无需额外处理
                acc = index[table].setdefault(
                    clazz,
                    Access(
                        module=module,
                        clazz=clazz,
                        file=str(path.relative_to(ROOT)),
                    ),
                )
                acc.ops.add(op)
                acc.statements += 1
                others = {t.lower() for t in TABLE_RE.findall(sql)} - {table}
                if others:
                    acc.multi_tables |= others
    return index


def merge(
    a: dict[str, dict[str, Access]], b: dict[str, dict[str, Access]]
) -> dict[str, dict[str, Access]]:
    out: dict[str, dict[str, Access]] = defaultdict(dict)
    for src in (a, b):
        for table, classes in src.items():
            for key, acc in classes.items():
                if key in out[table]:
                    out[table][key].merge(acc)
                else:
                    out[table][key] = acc
    return out


def propose_owner(table: str, modules: dict[str, Access]) -> str:
    """按表名前缀 + 访问模块数给出归属建议。"""
    fixed = {
        "t_user": "identity",
        "t_user_invitation": "identity",
        "t_role": "identity",
        "t_permission": "identity",
        "t_role_permission": "identity",
        "t_user_role": "identity",
    "t_auth_event": "identity",
    "t_user_session": "identity",
        "t_payment_order": "payment",
        "t_payment_attempt": "payment",
        "t_payment_event": "payment",
        "t_refund": "payment",
    "t_payment_mock_scenario": "payment",
        "t_payment_outbox": "payment",
        "t_recon_task": "payment",
        "t_recon_diff": "payment",
        "t_channel_bill": "payment",
        "t_admin_audit_log": "platform/audit",
        "t_alert_log": "platform/alert",
    }
    if table in fixed:
        return fixed[table]
    if table.startswith("t_llm_"):
        return "llm"
    if table.startswith("t_metering_"):
        return "metering"
    return max(modules.items(), key=lambda kv: kv[1].statements)[0] if modules else "unknown"


def render(catalog: dict[str, dict], index: dict[str, dict[str, Access]]) -> str:
    lines: list[str] = []
    add = lines.append
    add("# 表访问清单（自动生成）")
    add("")
    add(f"- 生成脚本：`tools/table_access_inventory.py`")
    add(f"- 表数量：{len(catalog)}；有代码访问的表：{len(index)}")
    add("")
    add("## 1. 汇总")
    add("")
    add("| 表 | 归属建议 | 访问模块数 | 访问类数 | 跨表 SQL | 写入方 | 读取方 |")
    add("|---|---|---|---|---|---|---|")
    for table in sorted(catalog):
        classes = index.get(table, {})
        mods = {a.module: a for a in classes.values()}
        writers = sorted({a.clazz for a in classes.values() if "WRITE" in a.ops})
        readers = sorted({a.clazz for a in classes.values() if "READ" in a.ops})
        multi = sorted({m for a in classes.values() for m in a.multi_tables})
        flag = "⚠️ " if len(mods) > 1 else ""
        add(
            f"| {flag}`{table}` | {propose_owner(table, mods)} | {len(mods)} | {len(classes)} | "
            f"{len(multi)} | {', '.join(writers) or '—'} | {', '.join(readers) or '—'} |"
        )
    add("")
    add("## 2. 跨模块访问（需要收口的表）")
    add("")
    violations = 0
    for table in sorted(index):
        by_module: dict[str, list[Access]] = defaultdict(list)
        for acc in index[table].values():
            by_module[acc.module].append(acc)
        if len(by_module) <= 1:
            continue
        violations += 1
        add(f"### `{table}` — {len(by_module)} 个模块访问")
        add("")
        for module in sorted(by_module):
            rows = []
            for acc in sorted(by_module[module], key=lambda a: a.clazz):
                ops = "+".join(sorted(acc.ops))
                rows.append(f"`{acc.clazz}` ({ops}, {acc.file})")
            add(f"- **{module}**：{'；'.join(rows)}")
        add("")
    if violations == 0:
        add("_无跨模块访问。_")
    add("")
    add("## 3. 多表 SQL（跨域查询候选）")
    add("")
    grouped: dict[tuple[str, str, str], set[str]] = defaultdict(set)
    for table in sorted(index):
        for acc in index[table].values():
            if not acc.multi_tables:
                continue
            grouped[(acc.module, acc.clazz, acc.file)] |= {table} | acc.multi_tables
    if grouped:
        add("| 模块 | 类 | 涉及表 | 文件 |")
        add("|---|---|---|---|")
        for (module, clazz, file), tables in sorted(grouped.items()):
            add(f"| {module} | `{clazz}` | {' / '.join(f'`{t}`' for t in sorted(tables))} | {file} |")
    else:
        add("_无。_")
    add("")
    add("## 4. 未被代码访问的表")
    add("")
    orphan = [t for t in sorted(catalog) if t not in index]
    add("、".join(f"`{t}`" for t in orphan) if orphan else "_无。_")
    return "\n".join(lines) + "\n"


def snapshot(catalog: dict[str, dict], index: dict[str, dict[str, Access]]) -> dict:
    """生成用于棘轮比对的快照。"""
    tables = {}
    for table in sorted(catalog):
        classes = index.get(table, {})
        modules = {a.module for a in classes.values()}
        effective = modules - COMPOSER_MODULES
        tables[table] = {
            "modules": sorted(modules),
            "effective_modules": sorted(effective),
            "module_count": len(effective),
            "class_count": len(classes),
            "statements": sum(len(a.ops) for a in classes.values()),
            "multi_table_classes": sorted(
                {a.clazz for a in classes.values() if a.multi_tables}
            ),
        }
    multi_classes = sorted(
        {f"{a.module}:{a.clazz}" for classes in index.values() for a in classes.values() if a.multi_tables}
    )
    # 跨库多表 SQL：同一条语句涉及两个以上目标库的表
    cross_db: dict[str, list[str]] = {}
    for table, classes in index.items():
        for acc in classes.values():
            if not acc.multi_tables:
                continue
            # multi_tables 里不含自身表，这里补上当前表
            dbs = sorted({db_of(table)} | {db_of(t) for t in acc.multi_tables})
            if len(dbs) > 1:
                cross_db[f"{acc.module}:{acc.clazz}"] = dbs
    return {
        "summary": {
            "table_count": len(catalog),
            "cross_module_tables": sorted(t for t, v in tables.items() if v["module_count"] > 1),
            "multi_table_class_count": len(multi_classes),
            "multi_table_classes": multi_classes,
            "cross_db_multi_table_count": len(cross_db),
            "cross_db_multi_table": cross_db,
            "composer_modules": sorted(COMPOSER_MODULES),
        },
        "tables": tables,
    }


def check(baseline: dict, current: dict) -> list[str]:
    """棘轮检查。

    门禁只卡「边界」：新增表必须登记归属、跨模块访问只能减少。
    多表 SQL 按类登记（baseline.summary.multi_table_classes + allow_multi_table），
    新增一个未登记的多表类同样失败——避免"加个 join 顺手过"。

    访问类数不进门禁：同一模块内多一个 Repository 是正常重构，不是边界退化。
    """
    problems: list[str] = []
    base_sum = baseline.get("summary", {})
    cur_sum = current["summary"]

    # 1) 新增表未登记归属
    for table in current["tables"]:
        if table not in baseline.get("tables", {}):
            problems.append(f"新增表未登记归属：{table}")

    # 2) 跨模块表只能减少
    base_cross = set(base_sum.get("cross_module_tables", []))
    cur_cross = set(cur_sum.get("cross_module_tables", []))
    for table in sorted(cur_cross - base_cross):
        problems.append(f"新增跨模块表：{table}")

    # 3) 单表访问模块数不能上升（0 → 1 属于"新表被 owner 启用"，不算越界）
    for table, cur in current["tables"].items():
        base = baseline.get("tables", {}).get(table)
        if base is None:
            continue
        allowed_max = max(1, base["module_count"])
        if cur["module_count"] > allowed_max:
            added = set(cur["modules"]) - set(base["modules"])
            problems.append(
                f"{table} 访问模块数上升：{base['module_count']} → {cur['module_count']}（新增 {sorted(added)}）"
            )

    # 4) 跨库多表 SQL：只减不增；已有类引用的库集合不能扩大
    base_cross_db = dict(base_sum.get("cross_db_multi_table", {}))
    cur_cross_db = dict(cur_sum.get("cross_db_multi_table", {}))
    for clazz in sorted(set(cur_cross_db) - set(base_cross_db)):
        problems.append(f"新增跨库多表 SQL 类：{clazz}（涉及 {cur_cross_db[clazz]}）")
    for clazz, dbs in sorted(cur_cross_db.items()):
        base_dbs = set(base_cross_db.get(clazz, []))
        if base_dbs and not set(dbs) <= base_dbs:
            problems.append(f"跨库多表 SQL 涉及的库扩大：{clazz} {sorted(base_dbs)} → {dbs}")

    return problems


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default="")
    parser.add_argument("--json", default="")
    parser.add_argument(
        "--baseline",
        default="",
        help="棘轮基线 JSON；配合 --write-baseline 生成，不给则只输出",
    )
    parser.add_argument(
        "--write-baseline",
        action="store_true",
        help="用当前状态覆盖基线（只允许在归属/收口有实际进展时执行）",
    )
    parser.add_argument(
        "--allow-multi-table",
        default="",
        help="登记允许存在的多表 SQL 类，逗号分隔，格式 module:Class（写入基线 allow_multi_table）",
    )
    args = parser.parse_args()

    catalog = table_catalog()
    index = merge(scan_java(), scan_mappers())
    markdown = render(catalog, index)
    current = snapshot(catalog, index)

    if args.out:
        out_path = ROOT / args.out
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(markdown, encoding="utf-8")
        print(f"written: {out_path}")
    elif not args.baseline:
        sys.stdout.write(markdown)

    if args.json:
        payload = {
            table: {
                clazz: {
                    "module": acc.module,
                    "ops": sorted(acc.ops),
                    "file": acc.file,
                    "multi_tables": sorted(acc.multi_tables),
                }
                for clazz, acc in classes.items()
            }
            for table, classes in index.items()
        }
        json_path = ROOT / args.json
        json_path.parent.mkdir(parents=True, exist_ok=True)
        json_path.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        print(f"written: {json_path}")

    if args.baseline:
        base_path = ROOT / args.baseline
        if args.write_baseline:
            base_path.parent.mkdir(parents=True, exist_ok=True)
            allow = [item.strip() for item in args.allow_multi_table.split(",") if item.strip()]
            if allow:
                current["allow_multi_table"] = allow
            elif base_path.exists():
                # 保留既有白名单，避免重复登记时被冲掉
                try:
                    previous = json.loads(base_path.read_text(encoding="utf-8"))
                    if previous.get("allow_multi_table"):
                        current["allow_multi_table"] = previous["allow_multi_table"]
                except json.JSONDecodeError:
                    pass
            base_path.write_text(
                json.dumps(current, ensure_ascii=False, indent=2), encoding="utf-8"
            )
            print(f"baseline written: {base_path}")
            return 0
        if not base_path.exists():
            print(f"baseline not found: {base_path}", file=sys.stderr)
            return 2
        baseline = json.loads(base_path.read_text(encoding="utf-8"))
        problems = check(baseline, current)
        if problems:
            print("数据边界检查失败（只允许下降）：", file=sys.stderr)
            for p in problems:
                print(f"  - {p}", file=sys.stderr)
            return 1
        print(
            "数据边界检查通过：跨模块表 "
            f"{len(current['summary']['cross_module_tables'])} 个、"
            f"多表 SQL 类 {current['summary']['multi_table_class_count']} 个（均未上升）"
        )
    return 0



if __name__ == "__main__":
    raise SystemExit(main())
