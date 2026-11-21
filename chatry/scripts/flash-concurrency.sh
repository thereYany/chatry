#!/usr/bin/env bash
set -euo pipefail

# 秒杀并发证据：创建一件限量测试商品，N 个并发请求抢购，断言成功数 == 库存数（不超卖）。
# 用法：BASE=http://127.0.0.1:8084 ADMIN_USER=admin ADMIN_PASS=admin12345 STOCK=5 CONCURRENCY=20 scripts/flash-concurrency.sh
# 说明：测试商品创建后立即下架；订单保留在库里便于核对（不影响种子商品）。

BASE="${BASE:-http://127.0.0.1:8084}"
ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASS="${ADMIN_PASS:-admin12345}"
STOCK="${STOCK:-5}"
CONCURRENCY="${CONCURRENCY:-20}"
CODE="FLASH_CONC_$(date +%s)"

json() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)"; }
need() { command -v "$1" >/dev/null || { echo "缺少依赖：$1"; exit 1; }; }
need curl
need python3

echo "== 管理员登录并创建限量商品（库存 $STOCK）"
ADMIN_TOKEN=$(curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" | json 'd["token"]')
START=$(date -u +%Y-%m-%dT%H:%M:%SZ)
END=$(date -u -d '+2 hours' +%Y-%m-%dT%H:%M:%SZ)
SKU=$(curl -s -X POST "$BASE/api/admin/mall/skus" -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"code\":\"$CODE\",\"title\":\"并发压测秒杀\",\"description\":\"脚本创建\",\"priceCents\":1000,\"creditCents\":1000,\"stock\":$STOCK,\"perUserLimit\":0,\"flashSale\":true,\"flashPriceCents\":100,\"flashStartAt\":\"$START\",\"flashEndAt\":\"$END\"}")
SKU_ID=$(echo "$SKU" | json 'd["id"]')
echo "   skuId=$SKU_ID"

echo "== 普通用户登录"
TOKEN=$(curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"demo123456"}' | json 'd["token"]')

echo "== $CONCURRENCY 个并发抢购"
TMP=$(mktemp -d)
seq 1 "$CONCURRENCY" | xargs -P"$CONCURRENCY" -I{} sh -c \
  "curl -s -o '$TMP/{}.json' -w '%{http_code}\n' -X POST '$BASE/api/mall/orders' -H 'Authorization: Bearer $TOKEN' -H 'Content-Type: application/json' -d '{\"skuId\":$SKU_ID}'" \
  | sort | uniq -c

SUCCESS=$(grep -l '"orderNo"' "$TMP"/*.json 2>/dev/null | wc -l | tr -d ' ')
SOLD_OUT=$(grep -l '已售罄' "$TMP"/*.json 2>/dev/null | wc -l | tr -d ' ')
echo "== 结果：成功 $SUCCESS 单，售罄 $SOLD_OUT 单，理论库存 $STOCK"

echo "== 复核商品库存"
REMAIN=$(curl -s "$BASE/api/admin/mall/skus" -H "Authorization: Bearer $ADMIN_TOKEN" |
  python3 -c "import sys,json;rows=json.load(sys.stdin);print(next(r['stock'] for r in rows if r['id']==$SKU_ID))")
echo "   剩余库存=$REMAIN（应为 0）"
curl -s -X PUT "$BASE/api/admin/mall/skus/$SKU_ID/status" -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' -d '{"status":"OFF_SHELF"}' >/dev/null
rm -rf "$TMP"

if [ "$SUCCESS" -ne "$STOCK" ] || [ "$REMAIN" -ne 0 ]; then
  echo "✗ 防超卖校验失败"
  exit 1
fi
echo "✓ 成功数 == 库存数，未超卖"
