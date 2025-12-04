#!/usr/bin/env bash
set -euo pipefail

# 必需环境变量（由 Gradle 任务注入）
if [ -z "${REMOTE_SERVER:-}" ] || [ -z "${REMOTE_BASE_DIR:-}" ] || [ -z "${SERVICE_NAME:-}" ] || [ -z "${SERVICE_PORT:-}" ]; then
  echo "错误: 需要环境变量 REMOTE_SERVER, REMOTE_BASE_DIR, SERVICE_NAME, SERVICE_PORT"
  exit 1
fi

echo "远程以调试模式重启服务: $SERVICE_NAME (端口: $SERVICE_PORT)"
ssh "$REMOTE_SERVER" "su - www -c 'export JAVA_TOOL_OPTIONS=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=3$SERVICE_PORT && $REMOTE_BASE_DIR/$SERVICE_NAME/$SERVICE_NAME-start.sh'"
