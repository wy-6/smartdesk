#!/usr/bin/env python3
"""
SmartDesk 第 5 周验收脚本（Day 29-35，RAG 知识库）

用法：
    python scripts/week5-acceptance.py

前置条件：
    - MySQL dev 实例运行在 3307（bash scripts/start-mysql-dev.sh start）
    - Redis 运行在 6380（bash scripts/start-redis-dev.sh start）
    - 应用以 mock provider 运行在 18080：
      bash scripts/start-app-local.sh

设计约束（承接第 2 周第 10 条经验）：
    脚本必须【连跑两次结果一致】。因此：
      1. 每次运行都用带时间戳的新用户名，不依赖历史数据
      2. 所有断言只看"本次响应"或"本次用户自己的数据"
      3. 不假设表是空的（生产环境跑也不会误判）

覆盖的验收点：
    Day29-30  文档导入、解析、切片、元数据（页码/分块编号）
    Day31     向量化与向量写入（切片数、维度）
    Day32     独立检索接口：片段、来源文件、分块 ID、分数
    Day33     带引用的问答 + 证据不足时拒答
    Day34     检索命中率（评测集在 JUnit 里跑，这里做端到端抽查）
    Day35     重复导入、空文档、错误格式、无答案问题
    【越权】   B 用户检索不到 / 读不到 / 删不掉 A 的文档
"""
import json
import subprocess
import sys
import time
from datetime import datetime, timedelta

BASE = "http://localhost:18080"

# 唯一后缀：保证连跑两次互不干扰
SUFFIX = str(int(time.time()))[-8:]
ALICE = "kb5a" + SUFFIX
BOB = "kb5b" + SUFFIX
PW = "accept123456"

POLICY = """公司报销制度（2026 验收版）

第一条 报销期限：员工因公出差产生的费用，须在出差结束后 30 天内提交报销申请。
逾期超过 60 天的单据不再受理。

第二条 发票要求：发票抬头必须与公司注册名称完全一致，需提供增值税普通发票或专用发票。

第三条 审批流程：单笔金额不超过 1000 元的由组长审批；超过 1000 元的须部门经理审批；
超过 5000 元的还需财务负责人会签。

第四条 差旅标准：住宿费一线城市每晚上限 600 元，二线城市 450 元，其他城市 350 元。

第五条 提交方式：所有报销单据统一在 OA 系统费用报销模块提交，纸质原件按月汇总交财务部归档。

第六条 保存期限：报销单据与发票的保存期限为 5 年。

第七条 交通标准：市内交通实报实销；城际火车可报销二等座，高铁一等座需提前审批。
市内打车单笔超过 200 元必须附行程说明。

第八条 培训费用：外部培训需在报名前提交申请，获批后凭发票与成绩单报销，
每人每年度上限 5000 元。

第九条 加班餐费：工作日加班至 21:00 之后的餐费可报销，单次上限 60 元，
需在加班结束后的 5 个工作日内提交。

第十条 提交时限：报销单据须在费用发生后 30 天内提交，超期不再受理。
"""

passed = 0
failed = 0


def call(method, path, body=None, token=None, timeout=60):
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
    status, _ = call("POST", "/api/auth/register", {"username": username, "password": PW})
    return status in (201, 409)


def login(username):
    status, body = call("POST", "/api/auth/login", {"username": username, "password": PW})
    if status != 200:
        print(f"登录失败 {username}: {status} {body}")
        sys.exit(1)
    return body["data"]["token"]


def iso(days):
    return (datetime.now() + timedelta(days=days)).strftime("%Y-%m-%dT%H:%M:%S")


def main():
    print("=" * 66)
    print("SmartDesk 第 5 周验收（RAG：导入 / 切片 / 检索 / 引用 / 拒答 / 越权）")
    print("=" * 66)

    # ---------------- 0. 服务就绪 ----------------
    status, _ = call("GET", "/api/health")
    check("应用健康检查", status == 200, f"status={status}")
    if status != 200:
        print("\n应用没起来，先启动它：bash scripts/start-app-local.sh")
        return 1

    register(ALICE)
    register(BOB)
    alice = login(ALICE)
    bob = login(BOB)

    # ---------------- 1. 鉴权 ----------------
    print("\n[1] 鉴权")
    status, _ = call("GET", "/api/knowledge/documents")
    check("未登录访问文档列表 → 401", status == 401, f"status={status}")
    status, _ = call("POST", "/api/knowledge/search", {"query": "报销"})
    check("未登录检索 → 401", status == 401, f"status={status}")
    status, _ = call("POST", "/api/knowledge/ask", {"question": "报销期限"})
    check("未登录问答 → 401", status == 401, f"status={status}")

    # ---------------- 2. 文档导入与切片 ----------------
    print("\n[2] 导入、切片与元数据")
    status, body = call("POST", "/api/knowledge/documents",
                        {"title": "公司报销制度", "content": POLICY, "sourceType": "TEXT"}, token=alice)
    doc = body.get("data") or {}
    check("导入文档返回 201", status == 201, f"status={status} body={body}")
    check("切出多个切片", (doc.get("chunkCount") or 0) > 1, f"chunkCount={doc.get('chunkCount')}")
    check("记录了正文字符数", (doc.get("charCount") or 0) > 0, f"charCount={doc.get('charCount')}")
    check("状态为 INDEXED", doc.get("status") == "INDEXED", f"status={doc.get('status')}")
    check("新导入不是重复", doc.get("duplicate") is False, f"duplicate={doc.get('duplicate')}")
    doc_id = doc.get("id")

    status, body = call("GET", f"/api/knowledge/documents/{doc_id}", token=alice)
    detail = body.get("data") or {}
    chunks = detail.get("chunks") or []
    check("详情能看到切片列表", status == 200 and len(chunks) > 0, f"status={status}")
    check("切片带分块编号", all(c.get("chunkIndex") is not None for c in chunks), f"chunks={len(chunks)}")
    check("切片带原文偏移", all(c.get("charStart") is not None and c.get("charEnd") is not None
                               for c in chunks), "缺少偏移")
    check("切片序号连续", [c.get("chunkIndex") for c in chunks] == list(range(len(chunks))),
          f"indexes={[c.get('chunkIndex') for c in chunks]}")

    status, body = call("GET", "/api/knowledge/documents?page=1&pageSize=20", token=alice)
    docs = body.get("data") or []
    check("我的文档列表包含刚导入的文档",
          status == 200 and any(d.get("id") == doc_id for d in docs), f"status={status}")

    # ---------------- 3. 重复导入与异常输入（Day35） ----------------
    print("\n[3] 重复导入、空文档、错误格式")
    status, body = call("POST", "/api/knowledge/documents",
                        {"title": "公司报销制度（重复）", "content": POLICY, "sourceType": "TEXT"}, token=alice)
    dup = body.get("data") or {}
    check("重复导入返回 200 且标记 duplicate", status == 200 and dup.get("duplicate") is True,
          f"status={status} duplicate={dup.get('duplicate')}")
    check("重复导入复用同一个文档 id", dup.get("id") == doc_id, f"id={dup.get('id')}")
    status, body = call("GET", "/api/knowledge/documents?page=1&pageSize=50", token=alice)
    same_titles = [d for d in (body.get("data") or []) if d.get("title") == "公司报销制度"]
    check("重复导入没有产生第二份文档", len(same_titles) == 1, f"count={len(same_titles)}")

    status, body = call("POST", "/api/knowledge/documents",
                        {"title": "空文档", "content": "", "sourceType": "TEXT"}, token=alice)
    check("空文档被拒绝（400）", status == 400, f"status={status} body={body}")

    status, body = call("POST", "/api/knowledge/documents",
                        {"title": "错格式", "content": "内容", "sourceType": "DOCX"}, token=alice)
    check("不认识的 sourceType 被拒绝（400）", status == 400, f"status={status} body={body}")

    status, body = call("POST", "/api/knowledge/documents",
                        {"title": "", "content": "内容", "sourceType": "TEXT"}, token=alice)
    check("空标题被参数校验拦下（400）", status == 400, f"status={status}")

    # ---------------- 4. 独立检索流程（Day32） ----------------
    print("\n[4] 独立检索流程")
    status, body = call("POST", "/api/knowledge/search",
                        {"query": "报销期限是多久", "topK": 5}, token=alice)
    result = body.get("data") or {}
    hits = result.get("hits") or []
    check("检索接口返回 200", status == 200, f"status={status}")
    check("检索有命中", len(hits) > 0, f"count={len(hits)}")
    check("响应里带 Top-K 与阈值", result.get("topK") is not None and result.get("threshold") is not None,
          f"topK={result.get('topK')} threshold={result.get('threshold')}")
    check("命中片段带分块 ID", all(h.get("chunkId") is not None for h in hits), "缺少 chunkId")
    check("命中片段带来源文件", all(h.get("documentTitle") == "公司报销制度" for h in hits),
          f"titles={[h.get('documentTitle') for h in hits]}")
    check("命中片段带相似度分数", all(isinstance(h.get("score"), (int, float)) for h in hits), "缺少 score")
    check("片段按分数从高到低", all(hits[i - 1]["score"] >= hits[i]["score"] for i in range(1, len(hits))),
          f"scores={[round(h.get('score'), 3) for h in hits]}")
    check("命中片段正文非空", all(h.get("content") for h in hits), "存在空片段")
    top_hit = hits[0] if hits else {}

    status, body = call("POST", "/api/knowledge/search",
                        {"query": "报销期限", "topK": 100}, token=alice)
    limited = body.get("data") or {}
    check("Top-K 被服务端夹到上限（<=20）", (limited.get("topK") or 0) <= 20, f"topK={limited.get('topK')}")

    # ---------------- 5. 问答与引用（Day33） ----------------
    print("\n[5] 问答、引用与拒答")
    status, body = call("POST", "/api/knowledge/ask",
                        {"question": "报销期限是多久", "topK": 3}, token=alice)
    answer = body.get("data") or {}
    citations = answer.get("citations") or []
    check("问答接口返回 200", status == 200, f"status={status}")
    check("回答里带引用标记 [1]", "[1]" in (answer.get("answer") or ""), f"answer={answer.get('answer')}")
    check("返回引用列表", len(citations) > 0, f"citations={len(citations)}")
    check("引用编号从 1 开始", citations and citations[0].get("index") == 1,
          f"first={citations[0] if citations else None}")
    check("引用指向真实分块 id",
          all(c.get("chunkId") in {h.get("chunkId") for h in hits} for c in citations),
          "引用与检索结果对不上")
    check("引用带来源文件", all(c.get("documentTitle") == "公司报销制度" for c in citations),
          f"titles={[c.get('documentTitle') for c in citations]}")
    check("引用带分数", all(isinstance(c.get("score"), (int, float)) for c in citations), "缺少 score")
    check("模型没有编造不存在的引用编号", (answer.get("unsupportedMarkers") or []) == [],
          f"unsupported={answer.get('unsupportedMarkers')}")
    check("检索片段数与引用数一致",
          answer.get("retrievalCount") == len(citations),
          f"retrieval={answer.get('retrievalCount')} citations={len(citations)}")

    status, body = call("POST", "/api/knowledge/ask",
                        {"question": "量子纠缠的贝尔不等式如何验证", "topK": 3}, token=alice)
    empty = body.get("data") or {}
    check("无证据时拒答", empty.get("refused") is True, f"refused={empty.get('refused')}")
    check("拒答话术明确", "无法确认" in (empty.get("answer") or ""), f"answer={empty.get('answer')}")
    check("拒答时没有引用", (empty.get("citations") or []) == [], f"citations={empty.get('citations')}")

    # ---------------- 6. 越权（本周最重要） ----------------
    print("\n[6] 越权：A 的知识库对 B 完全不可见")
    status, body = call("POST", "/api/knowledge/search",
                        {"query": "报销期限是多久", "topK": 5}, token=bob)
    bob_hits = (body.get("data") or {}).get("hits") or []
    check("B 检索不到 A 的文档", status == 200 and len(bob_hits) == 0, f"count={len(bob_hits)}")

    status, body = call("GET", f"/api/knowledge/documents/{doc_id}", token=bob)
    check("B 读不到 A 的文档详情 → 404", status == 404, f"status={status} body={body}")

    status, body = call("DELETE", f"/api/knowledge/documents/{doc_id}", token=bob)
    check("B 删不掉 A 的文档 → 404", status == 404, f"status={status} body={body}")

    status, body = call("GET", f"/api/knowledge/documents/{doc_id}", token=alice)
    check("A 的文档仍然存在（越权删除没生效）", status == 200, f"status={status}")

    status, body = call("POST", "/api/knowledge/ask",
                        {"question": "报销期限是多久", "topK": 3}, token=bob)
    bob_answer = body.get("data") or {}
    check("B 问 A 的文档内容拿不到引用",
          (bob_answer.get("citations") or []) == [] and bob_answer.get("retrievalCount") == 0,
          f"citations={bob_answer.get('citations')}")
    check("B 的问答被拒答", bob_answer.get("refused") is True, f"refused={bob_answer.get('refused')}")

    status, body = call("GET", "/api/knowledge/documents?page=1&pageSize=50", token=bob)
    check("B 的文档列表里没有 A 的文档",
          all(d.get("id") != doc_id for d in (body.get("data") or [])), "列表里出现了别人的文档")

    # ---------------- 7. 删除自有文档 ----------------
    print("\n[7] 删除")
    status, body = call("DELETE", f"/api/knowledge/documents/{doc_id}", token=alice)
    check("删除自己的文档返回 200", status == 200, f"status={status} body={body}")
    status, body = call("GET", f"/api/knowledge/documents/{doc_id}", token=alice)
    check("删除后再取详情 → 404", status == 404, f"status={status}")
    status, body = call("POST", "/api/knowledge/search",
                        {"query": "报销期限", "topK": 5}, token=alice)
    check("删除后检索不到残留片段",
          len(((body.get("data") or {}).get("hits") or [])) == 0, "还有残留")

    # ---------------- 汇总 ----------------
    print("\n" + "=" * 66)
    print(f"结果：{passed} 通过 / {failed} 失败")
    print("=" * 66)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
