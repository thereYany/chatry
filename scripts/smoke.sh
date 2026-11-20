#!/usr/bin/env bash
set -euo pipefail

# 端到端冒烟：登录 → 额度 → 建会话 → 同步对话（余额下降）→ SSE 流式（done 事件）
# 依赖：运行中的 chatry + python3（解析 JSON）。
BASE="${BASE:-http://127.0.0.1:8084}"
SMOKE_USER="${SMOKE_USER:-demo}"
SMOKE_PASS="${SMOKE_PASS:-demo123456}"

json() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)"; }

echo "== 1. 登录 $SMOKE_USER"
LOGIN=$(curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$SMOKE_USER\",\"password\":\"$SMOKE_PASS\"}")
TOKEN=$(echo "$LOGIN" | json 'd["token"]')
TOKEN_HEAD="${TOKEN:0:18}..."
echo "   token=$TOKEN_HEAD"

echo "== 2. 额度（首次访问自动发放新手额度）"
CREDITS=$(curl -s "$BASE/api/chat/credits" -H "Authorization: Bearer $TOKEN")
echo "   $CREDITS"
BEFORE=$(echo "$CREDITS" | json 'd["balance"]')

echo "== 3. 新建会话"
CONV=$(curl -s -X POST "$BASE/api/chat/conversations" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{}')
CID=$(echo "$CONV" | json 'd["id"]')
echo "   conversationId=$CID"

echo "== 4. 同步对话"
RESP=$(curl -s -X POST "$BASE/api/chat/conversations/$CID/messages" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"content":"你好，简单介绍一下你自己"}')
echo "   $RESP" | head -c 400; echo
AFTER=$(echo "$RESP" | json 'd["balance"]["balance"]')
COST=$(echo "$RESP" | json 'd["cost"]')

echo "== 5. SSE 流式对话（delta + done）"
STREAM_TAIL=$(curl -sN -X POST "$BASE/api/chat/conversations/$CID/messages/stream" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"content":"用一句话说明这是一次流式回答"}' | tail -4)
echo "$STREAM_TAIL"
AFTER2=$(echo "$STREAM_TAIL" | tail -1 | sed 's/^data://' | json 'd["balance"]["balance"]')

echo
echo "== 结果"
echo "   balance: $BEFORE -> $AFTER（同步，cost=$COST）-> $AFTER2（流式）"
python3 - "$BEFORE" "$AFTER" "$AFTER2" <<'PY'
import sys
before, after, after2 = map(float, sys.argv[1:])
assert after < before, "同步对话后余额没有下降"
assert after2 <= after, "流式对话后余额不应上升"
print("   ✓ 余额随对话下降，计量闭环通过")
PY
