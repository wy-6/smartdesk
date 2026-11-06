#!/usr/bin/env python3
"""
SmartDesk 第 2 周验收脚本（Day 8-14）

用法：
    python3 scripts/week2-acceptance.py

前置条件：
    - MySQL dev 实例运行在 3307（bash scripts/start-mysql-dev.sh start）
    - Redis 运行在 6380
    - 应用运行在 18080
"""
import json
import subprocess
import sys

BASE = "http://localhost:18080"

# 测试账号（密码从环境变量读，不写在命令行里，避免明文出现在 shell 历史）
ALICE_PW = "alice123456"
BOB_PW = "bob123456"

passed = 0
failed = 0


def call(method, path, body=None, token=None):
    """发一个 HTTP 请求，返回 (status, json_body)。

    注意：curl 的 -w "%{http_code}" 会把状态码追加到输出末尾，
    所以要先剥掉最后一行再解析 JSON。
    """
    cmd = ["curl", "-s", "--max-time", "25", "-X", method, BASE + path,
           "-w", "\n%{http_code}"]
    if body is not None:
        cmd += ["-H", "Content-Type: application/json", "-d", json.dumps(body)]
    if token:
        cmd += ["-H", "Authorization: Bearer " + token]
    out = subprocess.run(cmd, capture_output=True, text=True).stdout
    if "\n" not in out:
        return 0, {"raw": out}
    body_text, _, status_text = out.rpartition("\n")
    try:
        status = int(status_text.strip())
    except ValueError:
        status = 0
    try:
        return status, json.loads(body_text)
    except json.JSONDecodeError:
        return status, {"raw": body_text}


def check(name, condition, detail=""):
    global passed, failed
    if condition:
        passed += 1
        print(f"  [PASS] {name}")
    else:
        failed += 1
        print(f"  [FAIL] {name}  {detail}")


def section(title):
    print(f"\n=== {title} ===")


# ---------------------------------------------------------------- Day 8
section("Day 8: 注册 / 登录 / 基础 CRUD")

status, r = call("POST", "/api/auth/register",
                 {"username": "alice", "password": ALICE_PW})
check("注册 alice", status == 201 and r.get("success"), f"status={status} {r}")
check("注册响应不含密码哈希", "passwordHash" not in json.dumps(r) and "$2a$" not in json.dumps(r))

status, r = call("POST", "/api/auth/register",
                 {"username": "bob", "password": BOB_PW})
check("注册 bob", status == 201 and r.get("success"), f"status={status} {r}")

status, r = call("POST", "/api/auth/login",
                 {"username": "alice", "password": ALICE_PW})
alice_token = r.get("data", {}).get("token") if r.get("data") else None
alice_user_id = r.get("data", {}).get("userId") if r.get("data") else None
check("登录 alice 拿到 token", bool(alice_token), f"{r}")

status, r = call("POST", "/api/auth/login",
                 {"username": "bob", "password": BOB_PW})
bob_token = r.get("data", {}).get("token") if r.get("data") else None
check("登录 bob 拿到 token", bool(bob_token), f"{r}")

# 创建任务
status, r = call("POST", "/api/tasks",
                 {"title": "写周报", "description": "整理本周进展",
                  "priority": 5, "dueDate": "2026-12-31T18:00:00"},
                 token=alice_token)
alice_task_id = r.get("data", {}).get("id") if r.get("data") else None
check("alice 创建任务", bool(alice_task_id), f"{r}")
check("任务归属 ownerId 非空",
      r.get("data", {}).get("ownerId") is not None, f"{r}")

for t, p in [("需求评审", 4), ("联调测试", 3), ("写部署文档", 2)]:
    call("POST", "/api/tasks",
         {"title": t, "priority": p, "dueDate": "2026-12-20T18:00:00"},
         token=alice_token)

status, r = call("POST", "/api/tasks",
                 {"title": "bob的任务", "priority": 3,
                  "dueDate": "2026-12-31T18:00:00"}, token=bob_token)
bob_task_id = r.get("data", {}).get("id") if r.get("data") else None
check("bob 创建任务", bool(bob_task_id), f"{r}")

status, r = call("GET", "/api/tasks", token=alice_token)
alice_tasks = r.get("data", [])
check("alice 只看到自己的 4 个任务", len(alice_tasks) == 4, f"count={len(alice_tasks)}")
check("alice 的任务里没有 bob 的",
      all(t["title"] != "bob的任务" for t in alice_tasks))

status, r = call("GET", "/api/tasks", token=bob_token)
bob_tasks = r.get("data", [])
check("bob 只看到自己的 1 个任务", len(bob_tasks) == 1, f"count={len(bob_tasks)}")

# 筛选
status, r = call("GET", "/api/tasks?status=TODO", token=alice_token)
check("按状态筛选 TODO", all(t["status"] == "TODO" for t in r.get("data", [])))

status, r = call("GET", "/api/tasks?minPriority=4", token=alice_token)
check("按优先级筛选 >=4",
      all(t["priority"] >= 4 for t in r.get("data", [])),
      f"{[t['priority'] for t in r.get('data', [])]}")

status, r = call("GET", "/api/tasks?page=1&pageSize=2", token=alice_token)
check("分页 page1 size=2", len(r.get("data", [])) == 2, f"{r}")
status, r = call("GET", "/api/tasks?page=2&pageSize=2", token=alice_token)
check("分页 page2 size=2", len(r.get("data", [])) == 2, f"{r}")

# ---------------------------------------------------------------- Day 9
section("Day 9: 参数校验与全局异常")

status, r = call("POST", "/api/tasks", {"title": "", "priority": 9}, token=alice_token)
check("空标题 + 越界优先级 → 400", status == 400, f"status={status}")
check("错误码 VALIDATION_ERROR", r.get("errorCode") == "VALIDATION_ERROR", f"{r}")

status, r = call("POST", "/api/auth/register",
                 {"username": "a", "password": "123"})
check("注册参数校验 → 400", status == 400, f"status={status}")

status, r = call("GET", "/api/tasks?status=NOT_A_STATUS", token=alice_token)
check("非法状态 → 400 INVALID_STATUS", r.get("errorCode") == "INVALID_STATUS", f"{r}")

status, r = call("GET", "/api/tasks/abc", token=alice_token)
check("非法 ID 类型 → 400 INVALID_PARAMETER",
      r.get("errorCode") == "INVALID_PARAMETER", f"{r}")

status, r = call("GET", "/api/nonexistent", token=alice_token)
check("不存在的接口 → 404", status == 404, f"status={status}")

# ---------------------------------------------------------------- Day 11
section("Day 11: 认证与越权防护")

status, r = call("GET", "/api/tasks")
check("未登录 → 401", status == 401, f"status={status}")

status, r = call("GET", "/api/tasks", token="eyJhbGciOiJIUzI1NiJ9.forged.sig")
check("伪造 token → 401", status == 401, f"status={status}")

status, r = call("GET", f"/api/tasks/{bob_task_id}", token=alice_token)
check("alice 读 bob 的任务 → 404", status == 404, f"status={status}")

status, r = call("PUT", f"/api/tasks/{bob_task_id}/status?status=DONE", token=alice_token)
check("alice 改 bob 的任务 → 404", status == 404, f"status={status}")

status, r = call("DELETE", f"/api/tasks/{bob_task_id}", token=alice_token)
check("alice 删 bob 的任务 → 404", status == 404, f"status={status}")

status, r = call("POST", "/api/auth/login",
                 {"username": "alice", "password": "wrongpassword"})
status2, r2 = call("POST", "/api/auth/login",
                   {"username": "nobody", "password": "wrongpassword"})
check("密码错误与用户不存在返回相同消息（防用户枚举）",
      r.get("errorMessage") == r2.get("errorMessage"),
      f"{r.get('errorMessage')} vs {r2.get('errorMessage')}")

# ---------------------------------------------------------------- Day 10
section("Day 10: 缓存")

# 检查 Redis 里确实有缓存
# 注意：@Cacheable 的 key 格式是 "cacheName::参数值"，即 taskDetail::1
def redis_keys(pattern):
    """返回匹配 pattern 的 Redis key 列表。"""
    import socket
    s = socket.create_connection(("127.0.0.1", 6380), timeout=5)
    s.sendall(b"*2\r\n$4\r\nAUTH\r\n$14\r\nsmartdesk_root\r\n")
    s.recv(100)
    cmd = ("*2\r\n$4\r\nKEYS\r\n$%d\r\n%s\r\n" % (len(pattern), pattern)).encode()
    s.sendall(cmd)
    raw = s.recv(8000).decode(errors="replace")
    s.close()
    return raw


status, r = call("GET", f"/api/tasks/{alice_task_id}", token=alice_token)
check("读任务详情（写缓存）", status == 200, f"status={status}")

keys = redis_keys("taskDetail*")
check("Redis 里有 taskDetail 缓存", "taskDetail" in keys, keys[:200])
check("缓存 key 含 ownerId（防缓存污染）",
      f"taskDetail::{alice_user_id}" in keys, keys[:300])

status, r = call("GET", f"/api/tasks/{alice_task_id}", token=alice_token)
check("再读（命中缓存）",
      status == 200 and r.get("data") and r["data"].get("id") == alice_task_id,
      f"status={status} data={r.get('data')}")

status, r = call("PUT", f"/api/tasks/{alice_task_id}/status?status=DONE", token=alice_token)
check("更新状态（清缓存）", status == 200 and r["data"]["status"] == "DONE", f"{r}")

status, r = call("GET", f"/api/tasks/{alice_task_id}", token=alice_token)
check("更新后再读拿到新值（缓存已失效重建）",
      r.get("data", {}).get("status") == "DONE", f"{r}")

keys_after = redis_keys("taskDetail*")
check("更新后缓存被重新写入", "taskDetail" in keys_after, keys_after[:200])

# ---------------------------------------------------------------- Day 12
section("Day 12: 配置与部署")

status, r = call("GET", "/api/health")
check("健康检查 UP", r.get("data", {}).get("status") == "UP", f"{r}")

status, r = call("GET", "/actuator/health")
check("Actuator 健康检查", status == 200, f"status={status}")

# ---------------------------------------------------------------- 汇总
print(f"\n{'='*50}")
print(f"通过 {passed} 项，失败 {failed} 项")
if failed:
    sys.exit(1)
print("全部验收通过 ✅")
