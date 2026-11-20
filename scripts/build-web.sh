#!/usr/bin/env bash
set -euo pipefail

# 构建 React 前端并把产物放进后端静态资源目录（同源部署，8084 一个入口）
cd "$(dirname "$0")/.."

if [ ! -d web/node_modules ]; then
  echo "==> 安装前端依赖"
  npm --prefix web install --include=dev --no-audit --no-fund
fi

echo "==> 构建前端"
npm --prefix web run build

DEST="chatry-app/src/main/resources/static"
rm -rf "$DEST"
mkdir -p "$DEST"
cp -r web/dist/. "$DEST/"
echo "==> 已复制到 $DEST"
ls "$DEST"
