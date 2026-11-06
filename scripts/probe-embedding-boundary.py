"""探针：同一份制度文档下，不同措辞的检索得分分布。

目的：量化 mock embedding 的能力边界 —— 它是词元哈希（词面匹配），
不是语义匹配。用文档原词一定能召回；换一套说法可能召不回。
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

QUERIES = [
    ("文档原词", "住宿费每人每晚上限多少"),
    ("近义改写", "出差住酒店一晚最多报销多少钱"),
    ("口语化", "住酒店能报多少一晚"),
    ("纯概念", "住宿标准"),
    ("无关问题", "量子纠缠的贝尔不等式如何实验验证"),
]


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
    user = "wk5p" + str(int(time.time()))[-8:]
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
    print("导入：HTTP", st, "| 切片", doc.get("chunkCount"), "片 | 字符数", doc.get("charCount"))
    print("阈值：", 0.05, "\n")

    for label, q in QUERIES:
        st, d = call("/api/knowledge/search", {"query": q, "topK": 3}, tok)
        hits = (d.get("data") or {}).get("hits") or []
        top = hits[0]["score"] if hits else None
        print(f"[{label}] {q}")
        if hits:
            print(f"    命中 {len(hits)} 条 | top score = {top:.4f} | "
                  f"来源={hits[0].get('documentTitle')} 分块#{hits[0].get('chunkIndex')}")
        else:
            print(f"    未命中（top score 低于阈值 0.05）→ 检索环节判为「无证据」")
        print()


if __name__ == "__main__":
    main()
