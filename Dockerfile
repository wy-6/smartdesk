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

# 以非 root 用户运行 —— 容器逃逸时危害更小
# 这是容器安全的基本要求，面试可讲
RUN groupadd -r smartdesk && useradd -r -g smartdesk smartdesk

COPY --from=builder /build/target/*.jar app.jar
RUN chown -R smartdesk:smartdesk /app
USER smartdesk

# 容器内端口（与宿主机映射的端口无关）
EXPOSE 18080

# JVM 参数：
#   -XX:MaxRAMPercentage=75  容器内 JVM 不知道物理内存上限，
#                            用百分比比写死 -Xmx 更适合容器
#   -XX:+UseContainerSupport 让 JVM 感知 cgroup 限制
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseContainerSupport -Djava.security.egd=file:/dev/./urandom"

# 健康检查：容器编排平台靠它判断容器是否就绪
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:18080/api/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar --spring.profiles.active=prod"]
