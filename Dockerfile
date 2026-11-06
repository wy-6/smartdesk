# ============================================================
# SmartDesk 应用镜像（Day12）
#
# 多阶段构建：先用 Maven 镜像打包，再用 JRE 镜像运行。
#
# 为什么要多阶段？
#   阶段一需要 Maven + JDK + 依赖缓存，镜像很大（~600MB）
#   阶段二只需要 JRE + 编译好的 jar，镜像可以压到 ~200MB
#   最终产物镜像里既没有 Maven 也没有源码，攻击面小得多。
#
# 构建：
#   docker build -t smartdesk:0.1.0 .
# 运行：
#   docker run -p 18080:18080 --env-file .env smartdesk:0.1.0
# ============================================================

# ---------- 阶段一：编译 ----------
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# 先只拷 pom.xml 让依赖层单独缓存。
# 这样改代码重新构建时，不用重新下载依赖（依赖层命中缓存）。
COPY pom.xml .
RUN mvn -B dependency:go-offline -DskipTests

# 再拷源码编译
COPY src ./src
RUN mvn -B clean package -DskipTests

# ---------- 阶段二：运行 ----------
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# 健康检查要发 HTTP 请求，需要 curl。
# 不能假设基础镜像自带 —— Temurin 镜像为了瘦身裁掉了不少工具，
# 少了 curl 的话 HEALTHCHECK 会永远失败，容器永远 unhealthy，
# 编排平台（和 compose 的 depends_on: service_healthy）就一直等不到就绪。
# 显式装一次，5MB 换确定性。
#
# DL3008（pin apt 版本）刻意忽略：基础镜像是浮动标签（jammy），
# apt 源里的 curl 版本随镜像更新变化；写死 =X.Y.Z 会在下次
# 基础镜像更新时构建直接失败。对健康检查这种非核心依赖，
# "装得上就行"比"版本固定"更合理。
# hadolint ignore=DL3008
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# 以非 root 用户运行 —— 容器逃逸时危害更小
# 这是容器安全的基本要求，面试可讲
RUN groupadd -r smartdesk && useradd -r -g smartdesk smartdesk

COPY --from=builder /build/target/*.jar app.jar

# 日志目录（重要，见 docs/docker-setup.md 的"已修复的坑"）：
#   prod profile 的 LOG_FILE 默认值是 /var/log/smartdesk/app.log，
#   但容器里 /var/log 下没有 smartdesk 目录，而且非 root 用户
#   无权在 /var/log 建目录 → Logback 建文件失败 → 应用启动即挂。
#   所以在镜像里把日志目录换到 WORKDIR 下，并提前建好、授权给运行用户。
RUN mkdir -p /app/logs \
    && chown -R smartdesk:smartdesk /app
ENV LOG_FILE=/app/logs/app.log

USER smartdesk

# 容器内端口（与宿主机映射的端口无关）
EXPOSE 18080

# JVM 参数：
#   -XX:MaxRAMPercentage=75  容器内 JVM 不知道物理内存上限，
#                            用百分比比写死 -Xmx 更适合容器
#   -XX:+UseContainerSupport 让 JVM 感知 cgroup 限制
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseContainerSupport -Djava.security.egd=file:/dev/./urandom"

# 健康检查：容器编排平台靠它判断容器是否就绪
#   - curl 已在上面显式安装 —— 少了它这个检查会永远失败
#   - 端口跟随 SERVER_PORT 环境变量（默认 18080），
#     改端口不用改镜像
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -f "http://localhost:${SERVER_PORT:-18080}/api/health" || exit 1

# ENTRYPOINT 两个讲究：
#   1. ${SPRING_PROFILES_ACTIVE:-prod} —— profile 可被环境变量覆盖，
#      本地用 local 跑、线上用 prod，不用改镜像。
#   2. exec —— 让 java 顶替 sh 成为 PID 1。
#      没有 exec 时 PID 1 是 sh，docker stop 发出的 SIGTERM 传不到 JVM，
#      容器会被 SIGKILL 强杀，优雅停机（Spring 的优雅关闭钩子）失效。
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar --spring.profiles.active=${SPRING_PROFILES_ACTIVE:-prod}"]
