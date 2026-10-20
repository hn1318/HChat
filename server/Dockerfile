# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 先只复制 pom，利用 Docker 层缓存依赖
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package

# ---------- 运行阶段 ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# 统一用 UTC，避免时间列受容器时区影响
ENV TZ=UTC \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC"

RUN addgroup -S hchat && adduser -S -G hchat hchat \
    && mkdir -p /app/.data/uploads && chown -R hchat:hchat /app

COPY --from=build /build/target/hchat-server.jar /app/hchat-server.jar

USER hchat
EXPOSE 8080

HEALTHCHECK --interval=20s --timeout=5s --start-period=40s --retries=5 \
  CMD wget -qO- http://127.0.0.1:8080/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/hchat-server.jar"]
