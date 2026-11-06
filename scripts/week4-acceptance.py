#!/usr/bin/env python3
"""
SmartDesk 第 4 周验收脚本（Day 22-28）

用法：
    python scripts/week4-acceptance.py

前置条件：
    - MySQL dev 实例运行在 3307（bash scripts/start-mysql-dev.sh start）
    - Redis 运行在 6380（bash scripts/start-redis-dev.sh start）
    - 应用以 mock provider 运行在 18080：
        AI_PROVIDER=mock java -jar target/smartdesk-0.1.0-SNAPSHOT.jar \\
            --spring.profiles.active=local --server.port=18080

设计约束（承接第 2 周第 10 条经验）：
    脚本必须【连跑两次结果一致】。因此：
      1. 每次运行都用带时间戳的新用户名，不依赖历史数据
      2. 所有断言只看"本次响应"或"本次用户自己的数据"
      3. 不假设表是空的（生产环境跑也不会误判）

覆盖的验收点：
    Day22-23  工具注册、schema 不含身份参数
    Day24     工具只能查登录态用户的数据（越权用例）
    Day25     写操作只生成草稿，确认后才落库，重复确认 404
    Day26     轮数上限 / 重复调用熔断 / 工具异常不崩
    Day27     多轮会话、会话越权 404、历史落库
"""
import json
import subprocess
import sys
import time
from datetime import datetime, timedelta

BASE = "http://localhost:18080"

# 唯一后缀：保证连跑两次互不干扰
SUFFIX = str(int(time.time()))[-8:]
ALICE = "acc4a" + SUFFIX
BOB = "acc4b" + SUFFIX
PW = "accept123456"

# mock 的死循环注入标记（真实模型会当作普通文本）
FAIL_LOOP = "[[FAIL:LOOP]]"

passed = 0
failed = 0


def call(method, path, body=None, token=None, timeout=40):
    """发一个 HTTP 请求，返回 (status, json_body)。"""
    cmd = ["curl", "-s", "--max-time", str(timeout), "-X", method, BASE + path,
           "-w", "\n%{http_code}"]
    if body is not None:
        cmd += ["-H", "Content-Type: application/json", "-d", json.dumps(body, ensure_ascii=False)]
    if token:
        cmd += ["-H", "Authorization: Bearer " + token]
    out = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8").stdout
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
        print(f"  PASS  {name}")
    else:
        failed += 1
        print(f"  FAIL  {name}   {detail}")


def register(username):
    status, body = call("POST", "/api/auth/register", {"username": username, "password": PW})
    return status in (201, 409), body


def login(username):
    status, body = call("POST", "/api/auth/login", {"username": username, "password": PW})
    if status != 200:
        print(f"登录失败 {username}: {status} {body}")
        sys.exit(1)
    return body["data"]["token"]


def task_titles(token):
    status, body = call("GET", "/api/tasks?page=1&pageSize=100", token=token)
    data = body.get("data")
    if isinstance(data, dict):
        items = data.get("records") or data.get("list") or []
    else:
        items = data or []
    return [item["title"] for item in items]


def latest_confirmation_id(token):
    """从工具审计里取最近一条 createTask 的确认号。"""
    status, body = call("GET", "/api/ai/tool-calls?page=1&pageSize=10", token=token)
    for log in (body.get("data") or []):
        if log.get("toolName") != "createTask":
            continue
        try:
            payload = json.loads(log.get("resultSummary") or "{}")
        except json.JSONDecodeError:
            continue
        if payload.get("confirmationId"):
            return payload["confirmationId"]
    return None


def iso(days):
    return (datetime.now() + timedelta(days=days)).strftime("%Y-%m-%dT%H:%M:%S")


def main():
    print("=" * 64)
    print("SmartDesk 第 4 周验收（Tool Calling / 确认机制 / 多轮会话）")
    print("=" * 64)

    # ---------------- 0. 服务就绪 ----------------
    status, _ = call("GET", "/api/health")
    check("应用健康检查", status == 200, f"status={status}")
    if status != 200:
        print("\n应用没起来，先启动它：")
        print("  AI_PROVIDER=mock java -jar target/smartdesk-0.1.0-SNAPSHOT.jar "
              "--spring.profiles.active=local --server.port=18080")
        return 1

    # ---------------- 1. 鉴权 ----------------
    print("\n[1] 鉴权与工具暴露面")
    status, _ = call("GET", "/api/ai/tools")
    check("未登录访问工具列表 → 401", status == 401, f"status={status}")

    register(ALICE)
    register(BOB)
    alice = login(ALICE)
    bob = login(BOB)

    status, body = call("GET", "/api/ai/tools", token=alice)
    tools = body.get("data") or []
    check("工具列表返回 4 个工具", status == 200 and len(tools) == 4, f"status={status} got={len(tools)}")

    names = {t["name"] for t in tools}
    check("包含 listTasks / getTask / countTasks / createTask",
          {"listTasks", "getTask", "countTasks", "createTask"} <= names, str(names))

    leaked = []
    for tool in tools:
        props = (tool.get("parameters") or {}).get("properties") or {}
        for bad in ("userId", "ownerId", "user_id", "owner_id"):
            if bad in props:
                leaked.append(f"{tool['name']}.{bad}")
    check("schema 中不含任何身份字段（模型无从传起）", not leaked, str(leaked))

    side = {t["name"]: t.get("sideEffect") for t in tools}
    check("createTask 被标记为有副作用", side.get("createTask") is True, str(side))
    check("只读工具没有被误标副作用",
          not side.get("listTasks") and not side.get("getTask"), str(side))

    # ---------------- 2. 只读工具与数据隔离 ----------------
    print("\n[2] 只读工具与数据隔离（Day24）")
    call("POST", "/api/tasks", {"title": "补交季度报告", "description": "已过期",
                                "priority": 3, "dueDate": iso(-2)}, token=alice)
    call("POST", "/api/tasks", {"title": "下季度的规划", "description": "还没到期",
                                "priority": 2, "dueDate": iso(10)}, token=alice)
    before = task_titles(alice)
    check("准备数据：alice 有 2 条任务", len(before) == 2, str(before))

    status, body = call("POST", "/api/ai/agent", {"message": "我有哪些逾期的任务"}, token=alice)
    data = body.get("data") or {}
    calls = data.get("toolCalls") or []
    answer = data.get("answer") or ""
    check("Agent 调用成功", status == 200, f"status={status}")
    check("确实调用了 listTasks", bool(calls) and calls[0]["name"] == "listTasks", str(calls))
    check("工具执行成功", bool(calls) and calls[0]["ok"] is True, str(calls))
    check("回答里包含逾期任务", "补交季度报告" in answer, answer[:120])
    check("回答里不含未逾期任务", "下季度的规划" not in answer, answer[:120])
    check("返回了轮数与 traceId",
          data.get("rounds", 0) >= 1 and bool((data.get("meta") or {}).get("traceId")),
          str(data.get("rounds")))

    status, body = call("POST", "/api/ai/agent", {"message": "我有哪些任务"}, token=bob)
    bob_answer = (body.get("data") or {}).get("answer") or ""
    check("bob 看不到 alice 的任务", "补交季度报告" not in bob_answer, bob_answer[:120])

    # ---------------- 3. 写操作与确认机制 ----------------
    print("\n[3] 写操作确认机制（Day25）")
    status, body = call("POST", "/api/ai/agent",
                        {"message": "帮我创建一个明天提交报告的紧急任务"}, token=alice)
    data = body.get("data") or {}
    answer = data.get("answer") or ""
    check("创建意图被识别", status == 200 and "确认" in answer, answer[:150])
    check("草稿生成后任务表没有增长", len(task_titles(alice)) == len(before), str(task_titles(alice)))
    check("回答没有说「已创建」", "已创建" not in answer, answer[:150])

    confirmation_id = latest_confirmation_id(alice)
    check("审计里能查到确认号", bool(confirmation_id), str(confirmation_id))

    status, _ = call("POST", f"/api/ai/confirmations/{confirmation_id}/confirm", token=bob)
    check("bob 用 alice 的确认号 → 404", status == 404, f"status={status}")

    status, body = call("POST", f"/api/ai/confirmations/{confirmation_id}/confirm", token=alice)
    created = body.get("data") or {}
    check("alice 确认成功", status == 200, f"status={status} {body}")
    check("确认后真的创建了任务", created.get("status") == "TODO" and bool(created.get("id")), str(created))
    check("任务数量 +1", len(task_titles(alice)) == len(before) + 1, str(task_titles(alice)))

    status, _ = call("POST", f"/api/ai/confirmations/{confirmation_id}/confirm", token=alice)
    check("重复确认 → 404（不会创建第二个）", status == 404, f"status={status}")
    check("重复确认后任务数不变", len(task_titles(alice)) == len(before) + 1, str(task_titles(alice)))

    # 取消流程
    call("POST", "/api/ai/agent", {"message": "帮我创建一个取消测试的任务"}, token=alice)
    cancel_id = latest_confirmation_id(alice)
    count = len(task_titles(alice))
    status, _ = call("DELETE", f"/api/ai/confirmations/{cancel_id}", token=alice)
    check("取消草稿成功", status == 200, f"status={status}")
    status, _ = call("POST", f"/api/ai/confirmations/{cancel_id}/confirm", token=alice)
    check("取消后再确认 → 404", status == 404, f"status={status}")
    check("取消流程没有创建任务", len(task_titles(alice)) == count, str(task_titles(alice)))

    # ---------------- 4. 异常与保护 ----------------
    print("\n[4] 循环保护与工具异常（Day26）")
    status, body = call("POST", "/api/ai/agent",
                        {"message": FAIL_LOOP + " 统计一下任务"}, token=alice)
    check("死循环被终止 → 422", status == 422, f"status={status} {body}")
    code = body.get("errorCode") or ""
    check("错误码是循环相关（TOOL_LOOP_*）", code.startswith("AI_TOOL_LOOP"), code)

    # 工具参数非法 → 回喂模型，链路不崩
    status, body = call("POST", "/api/ai/agent",
                        {"message": "[[TOOL:getTask:{\"taskId\":\"abc\"}]] 看看这个任务"}, token=alice)
    check("工具参数非法时链路不崩（仍返回 200 或可读错误）",
          status in (200, 422), f"status={status} {str(body)[:150]}")
    if status == 200:
        failed_calls = [c for c in (body.get("data") or {}).get("toolCalls") or [] if not c.get("ok")]
        check("工具失败被记录在轨迹里并回喂模型", bool(failed_calls), str(failed_calls))

    # ---------------- 5. 多轮会话 ----------------
    print("\n[5] 多轮会话与隔离（Day27）")
    status, body = call("POST", "/api/ai/sessions", {}, token=alice)
    session_id = (body.get("data") or {}).get("id")
    check("创建会话成功", status == 200 and session_id is not None, f"status={status}")

    call("POST", f"/api/ai/sessions/{session_id}/messages", {"message": "我还有多少任务"}, token=alice)
    status, body = call("POST", f"/api/ai/sessions/{session_id}/messages",
                        {"message": "那我有哪些逾期任务"}, token=alice)
    check("第二轮消息成功", status == 200, f"status={status}")
    check("第二轮仍会调工具", bool((body.get("data") or {}).get("toolCalls")), str(body.get("data"))[:150])

    status, body = call("GET", f"/api/ai/sessions/{session_id}/messages", token=alice)
    messages = body.get("data") or []
    check("历史消息落库 4 条", len(messages) == 4, f"got={len(messages)}")
    check("角色顺序正确",
          [m["role"] for m in messages] == ["USER", "ASSISTANT", "USER", "ASSISTANT"],
          str([m["role"] for m in messages]))
    check("助手消息带工具调用摘要",
          any(m.get("toolCalls") for m in messages if m["role"] == "ASSISTANT"), "无工具摘要")

    status, _ = call("GET", f"/api/ai/sessions/{session_id}/messages", token=bob)
    check("bob 读 alice 会话 → 404", status == 404, f"status={status}")
    status, _ = call("POST", f"/api/ai/sessions/{session_id}/messages", {"message": "偷看"}, token=bob)
    check("bob 往 alice 会话发消息 → 404", status == 404, f"status={status}")

    status, body = call("GET", "/api/ai/sessions", token=bob)
    check("bob 的会话列表为空", status == 200 and not (body.get("data") or []),
          str(body.get("data"))[:120])

    # ---------------- 6. 审计与指标 ----------------
    print("\n[6] 审计与可观测性")
    status, body = call("GET", "/api/ai/tool-calls?page=1&pageSize=50", token=alice)
    logs = body.get("data") or []
    check("alice 有工具审计记录", status == 200 and len(logs) > 0, f"status={status}")
    check("审计记录带 traceId", bool(logs) and bool(logs[0].get("traceId")), str(logs[:1])[:150])
    check("审计里有 createTask 记录",
          any(log.get("toolName") == "createTask" for log in logs), "无 createTask 记录")

    status, body = call("GET", "/api/ai/tool-calls?page=1&pageSize=50", token=bob)
    bob_logs = body.get("data") or []
    check("bob 的审计里没有 alice 的记录",
          all(log.get("toolName") != "createTask" for log in bob_logs), str(bob_logs[:1])[:150])

    status, body = call("GET", "/actuator/metrics/ai.tool.calls", token=alice)
    check("ai.tool.calls 指标已注册", status == 200, f"status={status}")

    # ---------------- 汇总 ----------------
    print("\n" + "=" * 64)
    total = passed + failed
    print(f"结果：{passed}/{total} 通过" + ("" if failed == 0 else f"，{failed} 失败"))
    print("=" * 64)
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
