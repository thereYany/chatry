#!/usr/bin/env python3
"""盯 GitHub Actions：只读公开仓库的 runs/jobs，不需要任何密钥。

用法：
  python3 tools/ci_watch.py --repo thereYany/atry --sha $(git rev-parse HEAD)
  python3 tools/ci_watch.py --repo thereYany/atry --branch main --watch --interval 20

说明：
- 未认证可读 runs 与 jobs（60 次/小时限流）；仓库私有或要拉失败日志时才需要 token
  （设置环境变量 GH_TOKEN，脚本会附带 Authorization 头）。
- 退出码：0 全部成功；1 有失败；2 超时/未找到对应提交的 CI。
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

API = "https://api.github.com"


def request(path: str, token: str | None) -> dict:
    req = urllib.request.Request(API + path)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("User-Agent", "atry-ci-watch")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=15) as response:
        return json.loads(response.read().decode("utf-8"))


def find_run(repo: str, sha: str | None, branch: str | None, token: str | None) -> dict | None:
    if sha:
        runs = request(f"/repos/{repo}/actions/runs?head_sha={sha}&per_page=20", token)["workflow_runs"]
        return runs[0] if runs else None
    runs = request(f"/repos/{repo}/actions/runs?branch={branch}&per_page=10", token)["workflow_runs"]
    return runs[0] if runs else None


def describe(run: dict, token: str | None) -> tuple[str, list[str]]:
    sha = run["head_sha"][:8]
    status = run["status"]
    conclusion = run["conclusion"] or "-"
    jobs = request(f"/repos/{run['repository']['full_name']}/actions/runs/{run['id']}/jobs", token)["jobs"]
    lines = []
    failed = []
    for job in jobs:
        steps = job.get("steps") or []
        step_text = " > ".join(
            f"{s['name']}[{s['conclusion'] or s['status']}]"
            for s in steps if s.get("conclusion") in ("failure", "timed_out", "cancelled")
        )
        lines.append(f"  - {job['name']}: {job['status']}/{job['conclusion'] or '-'}"
                     + (f"  失败步骤: {step_text}" if step_text else ""))
        if job.get("conclusion") in ("failure", "timed_out", "cancelled"):
            failed.append(job["name"])
    header = f"run #{run['run_number']} sha={sha} status={status} conclusion={conclusion}"
    return header, lines, failed


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", required=True)
    parser.add_argument("--sha", default=None)
    parser.add_argument("--branch", default="main")
    parser.add_argument("--watch", action="store_true")
    parser.add_argument("--interval", type=int, default=20)
    parser.add_argument("--timeout", type=int, default=1800)
    args = parser.parse_args()
    token = os.environ.get("GH_TOKEN")
    deadline = time.time() + args.timeout

    while True:
        try:
            run = find_run(args.repo, args.sha, args.branch, token)
        except urllib.error.HTTPError as e:
            print(f"GitHub API 错误：HTTP {e.code} {e.reason}")
            return 2
        if run is None:
            print(f"没有找到 sha={args.sha or '-'} branch={args.branch} 对应的 CI，可能还没推送")
            return 2
        header, lines, failed = describe(run, token)
        print(header)
        print("\n".join(lines))
        if run["status"] == "completed":
            if run["conclusion"] == "success":
                return 0
            print(f"CI 失败，job: {', '.join(failed)}")
            print(f"详情：{run['html_url']}")
            if not token:
                print("（未配置 GH_TOKEN：公开仓库可看状态，但失败日志需要认证才能拉取）")
            return 1
        if not args.watch:
            return 0 if run["status"] != "completed" else 0
        if time.time() > deadline:
            print("等待超时")
            return 2
        time.sleep(args.interval)


if __name__ == "__main__":
    sys.exit(main())
