#!/usr/bin/env bash
set -euo pipefail

# 本地启动 chatry（默认连本机 MySQL chatry_dev + Redis db0，mock 模型）
cd "$(dirname "$0")/.."

JAR="chatry-app/target/chatry-app-0.9.0-exec.jar"
if [ ! -f "$JAR" ]; then
  echo "==> 构建 $JAR"
  mvn -B -q -DskipTests package
fi

export DB_URL="${DB_URL:-jdbc:mysql://127.0.0.1:3306/chatry_dev?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true}"
export DB_USER="${DB_USER:-chat}"
export DB_PASSWORD="${DB_PASSWORD:-chatpass}"

JAVA_OPTS="${JAVA_OPTS:--Xms64m -Xmx384m -XX:MaxMetaspaceSize=192m}"

echo "==> chatry: http://127.0.0.1:${SERVER_PORT:-8084}（健康：/api/health，指标：127.0.0.1:9092/actuator/health）"
exec java $JAVA_OPTS -jar "$JAR"
