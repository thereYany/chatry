#!/usr/bin/env bash
set -euo pipefail

# 用真实 DeepSeek 模型启动（会产生真实费用，只建议本机/内测使用）
# key 从 DEEPSEEK_API_KEY 或 DEEPSEEK_ENV_FILE（默认 ~/.config/atry/deepseek.env）读取
cd "$(dirname "$0")/.."

ENV_FILE="${DEEPSEEK_ENV_FILE:-$HOME/.config/atry/deepseek.env}"
if [ -z "${DEEPSEEK_API_KEY:-}" ] && [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a
fi
if [ -z "${DEEPSEEK_API_KEY:-}" ]; then
  echo "缺少 DEEPSEEK_API_KEY（或 $ENV_FILE）" >&2
  exit 1
fi

export DEEPSEEK_ENABLED=true
export CHATRY_CHAT_MODEL="${CHATRY_CHAT_MODEL:-deepseek-flash}"
exec "$(dirname "$0")/dev-run.sh"
