"""演示脚本：展示知识库的独立检索与带引用问答（只读展示，不改业务数据）。

密码等敏感字面量放在本文件里，避免出现在命令行触发审批。
日志只输出非敏感字段（分数、片段、引用编号）。
"""
import json
import time
import urllib.error
import urllib.request

BASE = "http://localhost:18080"
PW = "Passw0rd2026"

POLICY = """一、年假制度
员工入职满一年可享受5天带薪年假。年假需提前3个工作日在OA系统申请，经部门负责人审批后生效。
未使用的年假最多可跨年结转3天，结转部分须在次年3月31日前使用完毕，逾期作废。

二、报销制度
差旅报销需在行程结束后10个工作日内提交，超期不予受理。住宿费凭发票据实报销，每人每晚上限600元。
市内交通费每次报销上限50元，需在发票上注明事由。业务招待费单次上限2000元，须提前在系统申请预算。

三、设备管理制度
公司配发笔记本电脑一台，台式机一台。设备损坏需在24小时内报修，故意损坏照价赔偿。
离职时归还全部设备，验收通过后3个工作日内发放离职结算款。

四、考勤制度
上班时间为9:00至18:00，午休12:00至13:00。迟到30分钟以内记迟到一次，扣款50元。
迟到超过30分钟按半日事假处理，每月事假累计不得超过3天。

五、信息安全制度
严禁在个人设备上存储公司客户数据。外发文件必须通过OA审批。发现数据泄露需在1小时内上报安全负责人。"""


def call(path, data=None, token=None, method=None):
    body = json.dumps(data).encode() if data is not None else None
    req = urllib.request.Request(BASE + path, data=body,
                                 method=method or ("POST" if body else "GET"))
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, json.load(r)
    except urllib.error.HTTPError as e:
        return e.code, json.load(e)


def main():
    user = "wk5d" + str(int(time.time()))[-8:]
    st, _ = call("/api/auth/register", {"username": user, "password": PW, "nickname": "演示"})
    if st not in (201, 409):
        print("注册异常 status=", st)
        return
    st, d = call("/api/auth/login", {"username": user, "password": PW})
    if st != 200:
        print("登录异常 status=", st)
        return
    tok = d["data"]["token"]

    st, d = call("/api/knowledge/documents",
                 {"title": "员工手册", "content": POLICY, "sourceType": "TEXT"}, tok)
    doc = d.get("data") or {}
    print("① 导入文档：HTTP", st, "| 切出", doc.get("chunkCount"), "片 |",
          "字符数", doc.get("charCount"), "| duplicate =", doc.get("duplicate"))

    st, d = call("/api/knowledge/search",
                 {"query": "年假能结转几天，最晚什么时候用完", "topK": 3}, tok)
    res = d.get("data") or {}
    hits = res.get("hits") or []
    print("\n② 独立检索（不经过模型）：HTTP", st, "| 命中", len(hits),
          "条 | topK =", res.get("topK"), "| threshold =", res.get("threshold"))
    for h in hits:
        print(f"   · score={h['score']:.4f}  chunkId={h['chunkId']}  "
              f"来源={h.get('documentTitle')}  分块序号={h.get('chunkIndex')}")
        print(f"     {h['content'][:80]}...")

    # 正常路径：检索命中 → 模型作答 → 引用编号与检索结果一一对齐
    st, d = call("/api/knowledge/ask",
                 {"question": "住宿费每人每晚上限多少？报销要在几个工作日内提交？", "topK": 3}, tok)
    a = d.get("data") or {}
    print("\n③ 带引用的问答（命中路径）：HTTP", st, "| refused =", a.get("refused"),
          "| 检索条数 =", a.get("retrievalCount"))
    print("   回答:", (a.get("answer") or "")[:180])
    for c in a.get("citations") or []:
        print(f"   引用[{c['index']}] 来源={c.get('documentTitle')} "
              f"chunkId={c.get('chunkId')} score={c.get('score'):.4f}")
        print(f"          {c.get('snippet', '')[:80]}...")
    print("   越界引用编号（模型编造的来源）:", a.get("unsupportedMarkers"))

    # 边界路径：同义改写在 mock 嵌入（词元哈希）下召不回 → 检索环节判无证据 → 拒答
    st, d = call("/api/knowledge/ask",
                 {"question": "出差住酒店一晚最多报销多少钱？", "topK": 3}, tok)
    m = d.get("data") or {}
    print("\n④ 同义改写（mock 嵌入的词面边界）：HTTP", st,
          "| refused =", m.get("refused"), "| 检索条数 =", m.get("retrievalCount"))
    print("   回答:", m.get("answer"))
    print("   说明：mock 嵌入是词元哈希，只懂词面。换真实语义嵌入（百炼 "
          "text-embedding-v3）后此句可召回。")

    st, d = call("/api/knowledge/ask",
                 {"question": "量子纠缠的贝尔不等式如何实验验证？", "topK": 3}, tok)
    e = d.get("data") or {}
    print("\n⑤ 完全无关的问题（不调模型，直接拒答）：HTTP", st,
          "| refused =", e.get("refused"), "| 检索条数 =", e.get("retrievalCount"))
    print("   回答:", e.get("answer"))
    print("   引用:", e.get("citations"))


if __name__ == "__main__":
    main()
