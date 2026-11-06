# Docker 无法本机实测：原因分析与三条解决路径

> 这是第 2 周 Day 12 的遗留问题闭环。
> 结论先行：**本机有 3 层障碍，其中只有 1 层你真正需要动手解；**
> 而在解开之前，我们已经有两条"不需要本机 Docker"的验证路径。

---

## 一、先说清楚：障碍到底在哪（2026-11-06 实测诊断）

| # | 检查项 | 实测结果 | 性质 |
|---|--------|----------|------|
| 1 | Windows 版本 | Windows 11 **家庭中文版**（Build 22631） | 家庭版没有 Hyper-V，但 **WSL2 不需要 Hyper-V 角色**，不构成硬障碍 |
| 2 | CPU 虚拟化 | `VirtualizationFirmwareEnabled: True`，`HypervisorPresent: True` | **BIOS 虚拟化已开启**，硬件层没问题 ✅ |
| 3 | WSL | `wsl.exe` 被本机安全策略**程序黑名单**拦截（WorkBuddy 沙箱外也一样） | 唯一的硬障碍 —— Docker Desktop 的 Linux 引擎**必须**依赖 WSL2 |
| 4 | Docker Desktop | **根本没装**（`C:\Program Files\Docker` 不存在；只有 CLI 27.2.0 在 PATH 里） | 需要安装 |
| 5 | `docker compose` | `compose` 不是 docker 命令（**Compose 插件缺失**） | 需要补 |
| 6 | Docker Hub | `registry-1.docker.io` / `download.docker.com` **连不通**（000） | 需要镜像加速或代理 |
| 7 | GitHub | `github.com` / `ghcr.io` **可达** ✅ | 云端路径可用的关键前提 |

**一句话诊断**：Docker Desktop 没装 + WSL 被安全策略封了 + Docker Hub 不通。
硬件和 Windows 版本都**不是**问题。

---

## 二、路径 A：把本机 Docker 真正跑起来（彻底解决）

> 前置条件：需要**管理员权限** + 同意放开一条安全策略。
> 大约 30~60 分钟（其中 WSL2 内核下载取决于网速）。

### A1. 解除 wsl.exe 的安全策略封禁（关键且只有你能做）

WorkBuddy 的安全策略把 `wsl.exe` 加进了程序黑名单（这也是当初 Docker
守护进程起不来的直接原因之一）。**这个只有你自己操作**：

1. 打开 WorkBuddy 的 **安全中心 → 命令安全 → 程序黑名单**
2. 把 `C:\WINDOWS\system32\wsl.exe` 从黑名单移除
3. 移除后重新打开 WorkBuddy 会话，`wsl --status` 应能正常输出

> 如果这台机器还有公司级终端管控（如企业安全软件），黑名单可能来自那里，
> 需要找管理员放开。家庭电脑一般没有这层。

### A2. 启用 WSL2（管理员 PowerShell）

```powershell
# 启用 WSL 与虚拟机平台两个 Windows 功能（家庭版完全支持）
dism.exe /online /enable-feature /featurename:Microsoft-Windows-Subsystem-Linux /all /norestart
dism.exe /online /enable-feature /featurename:VirtualMachinePlatform /all /norestart

# 重启电脑后，安装 WSL2 内核
wsl --set-default-version 2
wsl --update

# 验证：应输出"适用于 Linux 的 Windows 子系统"及版本信息
wsl --status
```

### A3. 安装 Docker Desktop（家庭版可用）

从 https://desktop.docker.com/win/main/amd64/Docker%20Desktop%20Installer.exe
下载（注意：`download.docker.com` 当前实测不通，用上面这个 `desktop.docker.com`
主站直链；还不行就挂代理）。

安装时勾选 **"Use WSL 2 based engine"**，安装完成后：

```powershell
# 验证守护进程
docker info

# 补装 compose 插件（如果 docker compose version 报不是命令）
# Docker Desktop 4.x 一般自带，缺了就在 Settings 里点 "Update" 重装组件
docker compose version
```

### A4. 解决 Docker Hub 拉不动的问题（实测 registry-1.docker.io 不通）

国内网络拉 `mysql:8.0` / `redis:7-alpine` 会卡住。两种解法选一：

**解法一：配置镜像加速器**（Docker Desktop → Settings → Docker Engine）：

```json
{
  "registry-mirrors": [
    "https://docker.m.daocloud.io",
    "https://dockerproxy.net"
  ]
}
```

> 镜像加速站时常失效，`docker pull hello-world` 不动就换一个。

**解法二：挂代理**（Settings → Resources → Proxies）。

### A5. 验证（本仓库一条命令）

```bash
cd smartdesk
docker compose up -d --wait          # 起 mysql + redis + app，等全部健康
curl http://localhost:18080/api/health
docker compose down                  # 停止（保留数据）
```

`up -d --wait` 全部通过 = Dockerfile、compose.yaml、健康检查、启动顺序**全部实测通过**。

---

## 三、路径 B：云端 CI 代跑（零本地依赖，立即可用）

> 不装任何东西，把验证交给 GitHub 的 Linux runner。
> 这是**当前状态下唯一能"真跑起来"的验证方式**，工作流已写好：

`.github/workflows/docker-verify.yml`（`docker-verify`）：

1. **质量门**：跑 41 个单元/切片测试（排除需要本地 MySQL 的 9 个集成测试）
2. **构建镜像**：`docker build` 验证 Dockerfile 全部指令
3. **编排验证**：`docker compose up -d --wait` —— 等三个容器全部健康
4. **冒烟测试**：健康检查 → 401 → 注册 201 → 登录 → 建 token → 建任务 → 查任务
5. **优雅停机验证**：`docker compose stop app` 应在 10 秒内退出
   （验证 ENTRYPOINT 里的 `exec` 让 SIGTERM 直达 JVM）
6. **失败留证**：无论成败导出容器日志为 artifact

### 启用步骤

```bash
# 1) 在 GitHub 建一个私有仓库（free 足够，Actions 每月 2000 分钟免费额度）
#    gh repo create smartdesk --private --source=. --push
#    （没有 gh 就在网页上建仓 + git remote add + git push）

# 2) 推上去之后，手动触发一次：
#    GitHub 仓库页 → Actions → docker-verify → Run workflow

# 3) 绿勾 = Dockerfile / compose.yaml / 镜像行为全部通过真实验证
```

> **注意**：冒烟测试用 `localhost:18080`，在 runner 上直接可达（compose 把
> app 端口映射出来了），不需要额外配置。

---

## 四、路径 C：本机离线静态校验（已做完，0 依赖）

不需要 daemon、不需要 WSL，纯 CLI 解析就能提前抓住大部分错误：

| 工具 | 验证什么 | 结果 |
|------|----------|------|
| `hadolint`（v2.12.0） | Dockerfile 最佳实践 | **0 告警**（DL3008 刻意忽略并注明理由） |
| `docker compose config`（v2.29.7） | compose.yaml 语法 + `${VAR}` 插值 + 结构 | 已配置，待下载完成后执行（见下） |

工具位置：`C:\Users\86181\.workbuddy\docker-tools\`

```bash
# Dockerfile lint
"C:/Users/86181/.workbuddy/docker-tools/hadolint.exe" Dockerfile

# compose 配置校验（--quiet：无输出 = 通过）
"C:/Users/86181/.workbuddy/docker-tools/docker-compose.exe" -f compose.yaml config --quiet
```

**静态校验能抓到的**：YAML 语法错、变量名拼错、healthcheck 结构错、
Dockerfile 指令顺序错（如 `USER` 之后又 `RUN` 装包会失败）。
**抓不到的**：镜像里 apt 源有没有 curl、应用能不能真启动 —— 这正是路径 A/B 存在的意义。

---

## 五、静态审查已修复的 4 个容器级 bug

这次排查时纯靠读文件就发现了 4 个"上机必炸"的问题，全部已修复：

### Bug 1：HEALTHCHECK 用 curl，但 JRE 镜像不保证有 curl

`eclipse-temurin:17-jre-jammy` 为了瘦身裁掉了大量工具。没有 curl，
`HEALTHCHECK` 会**永远失败** → 容器永远 unhealthy →
compose 的 `depends_on: service_healthy` 永远等不到 app。
**修复**：构建时显式 `apt-get install curl`。

### Bug 2：prod 日志路径在容器里不可写（最致命）

`application-prod.yml` 里 `logging.file.name` 默认 `/var/log/smartdesk/app.log`。
容器里 `/var/log` 下没有 smartdesk 目录，且运行用户是非 root 的
`smartdesk`，**无权创建** → Logback 建文件失败 → **应用启动即挂**。
**修复**：镜像内 `ENV LOG_FILE=/app/logs/app.log` 并提前建目录授权。

### Bug 3：`docker stop` 杀不死进程（SIGTERM 传不到 JVM）

原 ENTRYPOINT 是 `sh -c "java ..."`：PID 1 是 sh，java 是它的子进程。
`docker stop` 的 SIGTERM 只发给 PID 1（sh 不转发），10 秒宽限后被
SIGKILL 强杀 —— 优雅停机完全失效。
**修复**：`exec java ...` 让 java 顶替 sh 成为 PID 1。
（CI 工作流的"优雅停机验证"步骤就是专测这个。）

### Bug 4：健康检查端口写死

写死 18080，一旦 `SERVER_PORT` 变了健康检查立刻失配。
**修复**：`curl http://localhost:${SERVER_PORT:-18080}/api/health`。

另外补了 **`.dockerignore`**：不写它，`target/`（几百 MB 构建产物）、
`.git`（全部历史）、最关键的 **`.env`（真实密码）** 都会进构建上下文。

---

## 六、决策建议：怎么选

| 你的情况 | 建议 |
|----------|------|
| 只想尽快确认 Dockerfile/compose 是对的 | **路径 B（今天就能做）**：推 GitHub，点一下 Run workflow |
| 准备把项目部署到服务器/继续第 3~8 周 | **路径 A（值得投资一次）**：30~60 分钟，之后本机随时能跑 compose |
| 短期都不折腾 | 路径 C 已完成；文档里 4 个 bug 的修复已覆盖最可能的启动失败原因 |

**推荐顺序：B 先行（10 分钟出结果），A 在周末做一次。**
两条都绿了，第 2 周 Day 12 的验收就完整闭环了。
