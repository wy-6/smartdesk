#!/usr/bin/env bash
# ============================================================
# 启动/停止 SmartDesk 本地 Redis 开发实例（端口 6380）
#
# 为什么单独起一个而不是用 6379？
#   本机可能已经有别的 Redis 占用 6379，避开冲突更省事。
#
# Redis 二进制从哪来？
#   GitHub 的 release 下载被沙箱阻断了，最终从 Maven Central 的
#   com.github.codemonstur:embedded-redis:1.4.3 jar 里提取的
#   redis-server-5.0.14.1-windows-amd64.exe。
#   这是个通用技巧 —— 很多"嵌入式中间件"的 Maven 包里直接打包了各平台二进制。
#
# 用法：
#   ./scripts/start-redis-dev.sh start    启动
#   ./scripts/start-redis-dev.sh stop     停止
#   ./scripts/start-redis-dev.sh status   查看状态
#   ./scripts/start-redis-dev.sh keys    列出缓存 key（排查缓存问题用）
# ============================================================
set -u

REDIS_HOME="C:/Users/86181/.workbuddy/redis-win"
REDIS_SERVER="$REDIS_HOME/redis-server.exe"
REDIS_CONF="$REDIS_HOME/redis.conf"
PORT=6380
PASSWORD="smartdesk_root"

start() {
  if [ ! -f "$REDIS_SERVER" ]; then
    echo "找不到 redis-server.exe：$REDIS_SERVER"
    echo "请先从 embedded-redis jar 里提取 redis-server-*-windows-amd64.exe"
    exit 1
  fi
  echo "==> 启动 Redis（端口 $PORT）"
  cd "$REDIS_HOME" && ./redis-server.exe redis.conf
}

stop() {
  echo "==> 停止 Redis"
  # Redis 没有 shutdown 命令的 CLI，但可以用 SHUTDOWN SAVE 通过 TCP 发
  python3 - <<'PYEOF'
import socket
try:
    s = socket.create_connection(("127.0.0.1", 6380), timeout=5)
    s.sendall(b"*2\r\n$4\r\nAUTH\r\n$14\r\nsmartdesk_root\r\n")
    s.recv(100)
    s.sendall(b"*1\r\n$8\r\nSHUTDOWN\r\n")
    s.close()
    print("    已发送 SHUTDOWN")
except Exception as ex:
    print(f"    连接失败（可能没在运行）: {ex}")
PYEOF
  sleep 2
  if netstat -ano 2>/dev/null | grep -q ":$PORT.*LISTENING"; then
    echo "    端口仍被占用，强制结束进程"
    for pid in $(netstat -ano 2>/dev/null | grep ":$PORT.*LISTENING" | awk '{print $5}' | sort -u); do
      taskkill //PID "$pid" //F > /dev/null 2>&1
    done
  fi
  echo "==> 已停止"
}

status() {
  if netstat -ano 2>/dev/null | grep -q ":$PORT.*LISTENING"; then
    echo "==> 运行中 (127.0.0.1:$PORT)"
  else
    echo "==> 未运行"
  fi
}

keys() {
  python3 - <<'PYEOF'
import socket
s = socket.create_connection(("127.0.0.1", 6380), timeout=5)
s.sendall(b"*2\r\n$4\r\nAUTH\r\n$14\r\nsmartdesk_root\r\n")
s.recv(100)
s.sendall(b"*2\r\n$4\r\nKEYS\r\n$1\r\n*\r\n")
print(s.recv(8000).decode(errors="replace"))
s.close()
PYEOF
}

case "${1:-start}" in
  start)  start ;;
  stop)   stop ;;
  status) status ;;
  keys)   keys ;;
  *) echo "用法: $0 {start|stop|status|keys}"; exit 1 ;;
esac
