#!/usr/bin/env bash
# ============================================================
# start-app-local.sh —— 本地启动 SmartDesk（从 .env 注入环境变量）
#
# 为什么需要这个脚本：
#   1. Spring Boot 不会自己读 .env 文件，环境变量必须由外部注入
#   2. 密钥不能出现在命令行参数里（会进 shell 历史、进程列表、
#      AI 会话的命令记录）。本脚本只 source .env，密钥永不落日志
#
# 用法：
#   bash scripts/start-app-local.sh            # 前台启动（Ctrl+C 停止）
#   bash scripts/start-app-local.sh --bg       # 后台启动，日志写 logs/app.log
#
# 前置条件：
#   - MySQL dev 实例（3307）与 Redis dev 实例（6380）已启动
#   - target/ 下已有 jar（没有就先 mvn -B -q package -DskipTests）
#   - .env 已存在（密钥可为空，AI_PROVIDER=mock 时不需要）
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/.."

if [ ! -f .env ]; then
  echo "错误：找不到 .env。先执行 cp .env.example .env 并填写。" >&2
  exit 1
fi

# set -a：source 期间所有变量自动 export 给子进程
set -a
# shellcheck disable=SC1091
source .env
set +a

# 本地开发的固定端口约定（.env 里的 SERVER_PORT 会被 --server.port 覆盖）
MYSQL_PORT="${MYSQL_PORT:-3307}"
REDIS_PORT="${REDIS_PORT:-6380}"
export MYSQL_PORT REDIS_PORT

JAR="target/smartdesk-0.1.0-SNAPSHOT.jar"
if [ ! -f "$JAR" ]; then
  echo "错误：$JAR 不存在，先执行 mvn -B -q package -DskipTests" >&2
  exit 1
fi

# 只打印非敏感信息。密钥连长度都不要打——长度本身也是信息
PROVIDER="${AI_PROVIDER:-mock}"
echo "[start-app] provider=${PROVIDER} model=${AI_MODEL:-} base-url=${AI_BASE_URL:-}"
if [ "${PROVIDER}" = "openai" ] && [ -z "${OPENAI_API_KEY:-}" ]; then
  # 应用自身的 fail-fast 也会拦，这里提前给出人话提示
  echo "[start-app] 警告：AI_PROVIDER=openai 但 OPENAI_API_KEY 为空，应用将拒绝启动" >&2
fi

mkdir -p logs
if [ "${1:-}" = "--bg" ]; then
  nohup java -jar "$JAR" --spring.profiles.active=local --server.port=18080 \
    > logs/app.log 2>&1 &
  echo "[start-app] 已后台启动 PID=$! 日志=logs/app.log"
else
  exec java -jar "$JAR" --spring.profiles.active=local --server.port=18080
fi
