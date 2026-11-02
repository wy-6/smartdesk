#!/usr/bin/env bash
# ============================================================
# 启动 SmartDesk 专用的 MySQL 开发实例（端口 3307）
#
# 为什么需要它？
#   本机 MySQL 8.0.46 已装在 3306，但 root 密码未知 / 或你不想动它。
#   这个脚本用同一个 mysqld 二进制，在独立数据目录 + 独立端口上起一个
#   专用实例，完全不影响本机原有的 MySQL。
#
# 用法：
#   ./scripts/start-mysql-dev.sh start    # 启动
#   ./scripts/start-mysql-dev.sh stop     # 停止
#   ./scripts/start-mysql-dev.sh status   # 查看状态
#   ./scripts/start-mysql-dev.sh reset    # 删库重来（会清空 smartdesk 库）
# ============================================================
set -u

MYSQL_HOME="C:/Program Files/MySQL/MySQL Server 8.0"
MYSQLD="$MYSQL_HOME/bin/mysqld.exe"
MYSQL="$MYSQL_HOME/bin/mysql.exe"
DEV_HOME="C:/Users/86181/.workbuddy/mysql-dev"
PORT=3307
ROOT_PWD="smartdesk_root"

init_instance() {
  echo "==> 初始化 MySQL 开发实例（首次运行需要，耗时约 10 秒）"
  rm -rf "$DEV_HOME"
  mkdir -p "$DEV_HOME/data"
  cat > "$DEV_HOME/my.ini" <<EOF
[mysqld]
basedir=C:/Program Files/MySQL/MySQL Server 8.0
datadir=$DEV_HOME/data
port=$PORT
bind-address=127.0.0.1
character-set-server=utf8mb4
collation-server=utf8mb4_unicode_ci
default-time-zone=+08:00
lower_case_table_names=1
pid-file=$DEV_HOME/mysqld.pid
log-error=$DEV_HOME/mysqld.err
skip-log-bin
[client]
port=$PORT
EOF
  "$MYSQLD" --defaults-file="$DEV_HOME/my.ini" --initialize-insecure --console 2>&1 | tail -2
}

start() {
  if [ ! -d "$DEV_HOME/data" ]; then
    init_instance
  fi
  echo "==> 启动 mysqld（端口 $PORT）"
  "$MYSQLD" --defaults-file="$DEV_HOME/my.ini" &
  MYSQLD_PID=$!
  for i in $(seq 1 30); do
    if "$MYSQL" -h 127.0.0.1 -P $PORT -uroot -p"$ROOT_PWD" -e "SELECT 1" > /dev/null 2>&1 \
       || "$MYSQL" -h 127.0.0.1 -P $PORT -uroot --skip-password -e "SELECT 1" > /dev/null 2>&1; then
      break
    fi
    sleep 1
  done
  # 首次启动时 root 密码为空，这里统一设置
  "$MYSQL" -h 127.0.0.1 -P $PORT -uroot --skip-password \
    -e "ALTER USER 'root'@'localhost' IDENTIFIED BY '$ROOT_PWD'; FLUSH PRIVILEGES;" 2>/dev/null
  echo "==> 初始化数据库与表"
  SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
  "$MYSQL" -h 127.0.0.1 -P $PORT -uroot -p"$ROOT_PWD" \
    < "$SCRIPT_DIR/../src/main/resources/db/schema.sql" 2>/dev/null
  echo "==> 完成。连接信息："
  echo "    host=127.0.0.1 port=$PORT user=root password=$ROOT_PWD database=smartdesk"
}

stop() {
  echo "==> 停止 mysqld"
  "$MYSQL" -h 127.0.0.1 -P $PORT -uroot -p"$ROOT_PWD" -e "SHUTDOWN;" 2>/dev/null
  sleep 2
  echo "==> 已停止"
}

status() {
  if "$MYSQL" -h 127.0.0.1 -P $PORT -uroot -p"$ROOT_PWD" -e "SELECT 1" > /dev/null 2>&1; then
    echo "==> 运行中 (127.0.0.1:$PORT)"
    "$MYSQL" -h 127.0.0.1 -P $PORT -uroot -p"$ROOT_PWD" \
      -e "SELECT id,title,status,priority FROM smartdesk.task ORDER BY id;" 2>/dev/null
  else
    echo "==> 未运行"
  fi
}

case "${1:-start}" in
  start)  start ;;
  stop)   stop ;;
  status) status ;;
  reset)  stop; rm -rf "$DEV_HOME"; start ;;
  *) echo "用法: $0 {start|stop|status|reset}"; exit 1 ;;
esac
