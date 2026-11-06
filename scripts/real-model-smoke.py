#!/usr/bin/env python3
"""
SmartDesk 真实模型冒烟脚本（provider 无关）

什么时候用：
    AI_PROVIDER=openai（DeepSeek / 百炼 / OpenAI 兼容服务均可）跑起来的应用。
    mock provider 有专门的 week3/week4 验收脚本，本脚本不是给 mock 用的。

和 mock 验收脚本的区别：
    断言只看「协议是否成立」，不看模型说了什么字。例如：
      - 工具调用：只断言 trace 里出现了预期的工具名、且 ok=true
      - 结构化输出：只断言 category 落在枚举集合内、summary 非空
      - 记忆力：断言回答里包含我们埋的任务标题（唯一后缀，误报概率极低）
    真实模型每次输出都不一样，任何「精确文案匹配」都会偶发失败。

用法：
    python scripts/real-model-smoke.py

成本提示：全脚本约 6-8 次模型调用（含工具轮次），
          按主流兼容服务定价 ≈ 0.01-0.05 元。
"""
import json
import subprocess
import sys
import time
from datetime import datetime

BASE = "http://localhost:18080"
SUFFIX = str(int(time.time()))[-8:]
USER = "smoke" + SUFFIX
PW = "accept123456"
MARK = "冒烟" + SUFFIX  # 唯一标记，用来验证模型真的读到了工具返回的数据

CATEGORIES = {"BUG", "FEATURE", "BILLING", "ACCOUNT", "OTHER"}

passed = 0
failed = 0
tokens_total = {"prompt": 0, "completion": 0}


def call(method, path, body=None, token=None, timeout=90):
    """发一个 HTTP 请求，返回 (status, json_body)。超时给足：真实模型 + 工具多轮。"""
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


def count_tokens(meta):
    if not isinstance(meta, dict):
        return
    tokens_total["prompt"] += meta.get("promptTokens") or 0
    tokens_total["completion"] += meta.get("completionTokens") or 0


def register_and_login():
    status, _ = call("POST", "/api/auth/register", {"username": USER, "password": PW})
    if status not in (201, 409):
        print(f"注册失败: {status}")
        sys.exit(1)
    status, body = call("POST", "/api/auth/login", {"username": USER, "password": PW})
    if status != 200:
        print(f"登录失败: {status} {body}")
        sys.exit(1)
    return body["data"]["token"]


def seed_tasks(token):
    """直接走 REST 建两条任务，给模型的工具返回里埋进唯一标记。"""
    for title in (f"给{MARK}任务A准备材料", f"复核{MARK}任务B的账单"):
        call("POST", "/api/tasks",
             {"title": title, "description": "smoke seed", "priority": 2}, token=token)


def tool_names(result):
    return [t.get("name") for t in (result.get("toolCalls") or [])]


def task_titles(token):
    status, body = call("GET", "/api/tasks?page=1&pageSize=100", token=token)
    data = body.get("data")
    items = data.get("records") if isinstance(data, dict) else data
    return [i["title"] for i in (items or [])]


def find_confirmation(token):
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


def main():
    print("=" * 64)
    print("SmartDesk 真实模型冒烟（provider 无关，只验协议）")
    print(f"时间：{datetime.now():%Y-%m-%d %H:%M:%S}  用户：{USER}")
    print("=" * 64)

    # ---------------- 0. 服务就绪 ----------------
    status, body = call("GET", "/api/health")
    check("应用健康检查", status == 200, f"status={status}")
    if status != 200:
        print("\n应用没起来或还在启动中，先等 /api/health 返回 200。")
        return 1

    # ---------------- 1. 结构化输出（Week3 能力在真实模型上是否成立）----------------
    print("\n[1] 结构化输出")
    token = register_and_login()

    status, body = call("POST", "/api/ai/classify",
                        {"text": "页面上的支付按钮点了没反应，钱也没扣"}, token=token)
    data = body.get("data") or {}
    check("分类接口返回 200", status == 200, f"status={status} body={body}")
    check("category 落在枚举集合内（BUG）",
          data.get("category") in CATEGORIES, f"category={data.get('category')!r}")
    count_tokens(data.get("meta"))
    print(f"        模型返回：category={data.get('category')} reason={data.get('reason')}")

    long_text = ("本季度工单系统共收到 %d 条反馈。其中支付失败类占比最高，主要集中在"
                 "安卓机型和弱网环境；其次是登录态丢失。客服建议优先修复支付链路。") % 1379
    status, body = call("POST", "/api/ai/summarize",
                        {"text": long_text, "maxChars": 80}, token=token)
    data = body.get("data") or {}
    summary = data.get("summary") or ""
    check("摘要接口返回 200 且 summary 非空",
          status == 200 and len(summary.strip()) >= 10,
          f"status={status} summary={summary[:50]!r}")
    # 模型可能略超字数，给 50% 容差；超一倍才算不守约束
    check("摘要长度遵守约束（含 50% 容差）",
          len(summary) <= 120, f"len={len(summary)}")
    count_tokens(data.get("meta"))

    # ---------------- 2. Agent 只读工具调用 ----------------
    print("\n[2] Agent 只读工具调用")
    seed_tasks(token)

    status, body = call("POST", "/api/ai/agent",
                        {"message": "我现在手头有哪些任务？把标题列出来，并告诉我一共有几条。"},
                        token=token)
    data = body.get("data") or {}
    answer = data.get("answer") or ""
    names = tool_names(data)
    check("Agent 接口返回 200", status == 200, f"status={status}")
    check("真实模型发起了工具调用（listTasks/countTasks）",
          bool(set(names) & {"listTasks", "countTasks", "getTaskDetail"}),
          f"toolCalls={names}")
    check("工具调用执行成功（无 errorCode）",
          all(t.get("ok") for t in (data.get("toolCalls") or [])),
          f"toolCalls={data.get('toolCalls')}")
    check("回答里真的包含埋进去的任务标题（模型读到了工具数据）",
          MARK in answer, f"answer={answer[:80]!r}")
    count_tokens(data.get("meta"))
    print(f"        工具：{names} 轮数：{data.get('rounds')}")

    # ---------------- 3. Agent 写操作两阶段确认 ----------------
    print("\n[3] Agent 写操作与两阶段确认")
    status, body = call("POST", "/api/ai/agent",
                        {"message": f"帮我创建一个任务，标题是「交付{MARK}报告」，优先级高。"},
                        token=token)
    data = body.get("data") or {}
    names = tool_names(data)
    check("Agent 返回 200 且调用了 createTask",
          status == 200 and "createTask" in names,
          f"status={status} toolCalls={names}")

    cid = find_confirmation(token)
    check("审计日志里能取到 confirmationId", bool(cid), "tool-calls 里没有草稿记录")

    if cid:
        status, body = call("POST", f"/api/ai/confirmations/{cid}/confirm", token=token)
        check("确认接口返回 2xx", 200 <= status < 300, f"status={status} body={body}")
        titles = task_titles(token)
        check(f"确认后任务「交付{MARK}报告」真的落库",
              any(MARK in t and "交付" in t for t in titles),
              f"titles={titles}")
        status, body = call("POST", f"/api/ai/confirmations/{cid}/confirm", token=token)
        check("重复确认返回 404（草稿已被原子取走）", status == 404, f"status={status}")

    # ---------------- 4. 汇总 ----------------
    print("\n" + "=" * 64)
    print(f"结果：{passed} 通过 / {failed} 失败")
    print(f"本轮真实调用累计 tokens：输入 {tokens_total['prompt']} + 输出 {tokens_total['completion']}")
    print("=" * 64)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
