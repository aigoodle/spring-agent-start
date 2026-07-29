# -----------------------------------------------------------------------------
# spring-agent-start: multi-stage build → runnable agent-start-example jar.
#
# Build once from the repo root:
#   docker build -t spring-agent-start:0.1.0 .
#
# Run in H2 (zero-setup) mode:
#   docker run --rm -p 18090:18090 spring-agent-start:0.1.0
#
# Run with an external Postgres:
#   docker run --rm -p 18090:18090 \
#     -e SPRING_PROFILES_ACTIVE=postgres \
#     -e SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/spring-agent-start \
#     spring-agent-start:0.1.0
# -----------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# 复制 pom 先，用 Maven 缓存加速：源码变更时依赖不重下
COPY pom.xml ./
COPY agent-start-common/pom.xml               agent-start-common/
COPY agent-start-model/pom.xml                agent-start-model/
COPY agent-start-provider/pom.xml       agent-start-provider/
COPY agent-start-provider/agent-start-provider-zhipu/pom.xml       agent-start-provider/agent-start-provider-zhipu/
COPY agent-start-provider/agent-start-provider-deepseek/pom.xml    agent-start-provider/agent-start-provider-deepseek/
COPY agent-start-provider/agent-start-provider-volcengine/pom.xml  agent-start-provider/agent-start-provider-volcengine/
COPY agent-start-knowledge/pom.xml            agent-start-knowledge/
COPY agent-start-store/pom.xml      agent-start-store/
COPY agent-start-store/agent-start-store-pgvector/pom.xml       agent-start-store/agent-start-store-pgvector/
COPY agent-start-store/agent-start-store-elasticsearch/pom.xml  agent-start-store/agent-start-store-elasticsearch/
COPY agent-start-store/agent-start-store-milvus/pom.xml         agent-start-store/agent-start-store-milvus/
COPY agent-start-tools/pom.xml                agent-start-tools/
COPY agent-start-agent/pom.xml                agent-start-agent/
COPY agent-start-workflow/pom.xml             agent-start-workflow/
COPY agent-start-trigger/pom.xml              agent-start-trigger/
COPY agent-start-observability/pom.xml        agent-start-observability/
COPY agent-start-web/pom.xml                  agent-start-web/
COPY agent-start-example/pom.xml              agent-start-example/
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -pl agent-start-example -am dependency:go-offline -DskipTests || true

COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -pl agent-start-example -am -DskipTests package

# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
COPY --from=build /workspace/agent-start-example/target/agent-start-example-*.jar app.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseZGC"
EXPOSE 18090
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
