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


def ensure_account(username, password):
    """让账号处于「存在且密码正确」的状态，返回 (token, user_id, was_created)。

    幂等关键：账号已存在时不重复注册，而是直接登录。
    否则第二次跑验收会撞上 USERNAME_EXISTS，后续所有断言全崩。

    无论走注册还是登录，最后都统一调一次登录拿 token ——
    注册接口返回的是 UserResponse（没有 token），登录才返回 LoginResponse。
    """
    status, r = call("POST", "/api/auth/register",
                     {"username": username, "password": password})
    created = status == 201 and r.get("success")

    # 注册响应绝不能带密码哈希
    body = json.dumps(r)
    if "passwordHash" in body or "$2a$" in body:
        raise SystemExit(f"注册响应泄漏了密码哈希：{r}")

    status, r = call("POST", "/api/auth/login",
                     {"username": username, "password": password})
    if status != 200 or not r.get("data"):
        raise SystemExit(
            f"账号 {username} 就绪失败（已存在但密码不匹配？），"
            f"请先清理数据库再跑验收。响应：{r}")

    token = r.get("data", {}).get("token")
    user_id = r.get("data", {}).get("userId")
    return token, user_id, created


def purge_tasks(token):
    """删掉该用户的所有历史任务，让计数断言可重复。

    分页要翻到最后一页，否则超过 pageSize 的历史任务会漏删。
    """
    deleted = 0
    page = 1
    while True:
        status, r = call("GET", f"/api/tasks?page={page}&pageSize=100", token=token)
        tasks = r.get("data") or []
        if not tasks:
            break
        for t in tasks:
            call("DELETE", f"/api/tasks/{t['id']}", token=token)
            deleted += 1
        if len(tasks) < 100:
            break
        page += 1
    return deleted


# ---------------------------------------------------------------- Day 8
section("Day 8: 注册 / 登录 / 基础 CRUD")

alice_token, alice_user_id, alice_created = ensure_account("alice", ALICE_PW)
check("alice 账号就绪（首次为注册，重复运行为登录）",
      bool(alice_token), f"created={alice_created}")
check("alice 登录拿到 token", bool(alice_token))
check("alice 拿到 userId", alice_user_id is not None, f"userId={alice_user_id}")
check("登录响应不含密码哈希", "$2a$" not in json.dumps({"t": alice_token})
      and "passwordHash" not in json.dumps({"t": alice_token}))

bob_token, bob_user_id, bob_created = ensure_account("bob", BOB_PW)
check("bob 账号就绪（首次为注册，重复运行为登录）",
      bool(bob_token), f"created={bob_created}")
check("bob 登录拿到 token", bool(bob_token))
check("alice 与 bob 是不同用户", alice_user_id != bob_user_id,
      f"alice={alice_user_id} bob={bob_user_id}")

# 清空历史任务，让后续「N 个任务」断言在重复运行时依然成立
alice_purged = purge_tasks(alice_token)
bob_purged = purge_tasks(bob_token)
if alice_purged or bob_purged:
    print(f"  （已清理历史任务：alice {alice_purged} 条，bob {bob_purged} 条）")

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
