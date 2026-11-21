#!/usr/bin/env bash
# atry P6 一键演示：登录 → 一次 LLM 调用（用量/成本）→ 余额扣减 → 最近用量事件。
#
# 依赖：curl、python3；默认连本地 atry-app（http://127.0.0.1:8084）。
# 用法：
#   docs/bench/p6-demo.sh
#   DEMO_BASE=http://127.0.0.1:8084 DEMO_ADMIN_PASSWORD='Demo1Pass!' docs/bench/p6-demo.sh
set -euo pipefail

BASE="${DEMO_BASE:-http://127.0.0.1:8084}"
USERNAME="${DEMO_ADMIN_USER:-admin}"
PASSWORD="${DEMO_ADMIN_PASSWORD:-Demo1Pass!}"
INITIAL_PASSWORD="${DEMO_ADMIN_INITIAL_PASSWORD:-Demo1Pass}"

jget() { python3 -c 'import sys,json;d=json.load(sys.stdin);print(d.get(sys.argv[1],""))' "$1"; }
post_json() { curl -sS -X POST "$1" -H 'Content-Type: application/json' -H "Authorization: Bearer $2" -d "$3"; }
get_json() { curl -sS "$1" -H "Authorization: Bearer $2"; }
login() { post_json "$BASE/api/auth/login" "" "{\"username\":\"$1\",\"password\":\"$2\"}"; }

echo "== 1/5 登录 $USERNAME"
TOKEN=$(login "$USERNAME" "$PASSWORD" | jget token)
if [ -z "$TOKEN" ]; then
  echo "  首次启动：用初始密码登录并完成强制改密"
  TOKEN=$(login "$USERNAME" "$INITIAL_PASSWORD" | jget token)
  curl -sS -X PUT "$BASE/api/auth/password" -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' \
    -d "{\"oldPassword\":\"$INITIAL_PASSWORD\",\"newPassword\":\"$PASSWORD\"}" >/dev/null
  TOKEN=$(login "$USERNAME" "$PASSWORD" | jget token)
fi
echo "  token ok (${#TOKEN} chars)"

echo
echo "== 2/5 演示账户余额（首次访问自动开户并注入演示余额）"
get_json "$BASE/api/demo/metering/balance" "$TOKEN"; echo

echo
echo "== 3/5 一次 LLM 调用（mock provider 的 mock-small 模型）"
# 直接输出原始 JSON：金额保持服务端精度（0.000039），不被二次格式化改写
post_json "$BASE/api/demo/llm/chat" "$TOKEN" \
  '{"message":"用一句话介绍 atry 基座","model":"mock-small","maxTokens":64}'; echo

echo
echo "== 4/5 结算后余额（应小于上一步的初始余额）"
get_json "$BASE/api/demo/metering/balance" "$TOKEN"; echo

echo
echo "== 5/5 最近用量事件（outbox 异步投递，等 2s 后可见 input/output 两条）"
sleep 2
get_json "$BASE/api/demo/metering/usage?limit=5" "$TOKEN"; echo
