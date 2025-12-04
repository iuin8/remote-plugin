#!/usr/bin/env bash
set -euo pipefail

# 必需环境变量（由 Gradle 任务注入）
if [ -z "${LOCAL_BASE_DIR:-}" ] || [ -z "${REMOTE_SERVER:-}" ] || [ -z "${REMOTE_BASE_DIR:-}" ] || [ -z "${SERVICE_NAME:-}" ] || [ -z "${SERVICE_PORT:-}" ]; then
  echo "错误: 需要环境变量 LOCAL_BASE_DIR, REMOTE_SERVER, REMOTE_BASE_DIR, SERVICE_NAME, SERVICE_PORT"
  exit 1
fi

echo "处理服务: $SERVICE_NAME (端口: $SERVICE_PORT)"

echo "远程启动服务..."
# 将端口通过环境变量传递给启动脚本（如需使用）
ssh "$REMOTE_SERVER" "su - www -c '$REMOTE_BASE_DIR/$SERVICE_NAME/$SERVICE_NAME-start.sh'"

echo "$SERVICE_NAME 完成"
