# -----------------------------------------------------------------------------
# spring-agent-start: multi-stage build → runnable spring-agent-start-example jar.
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
COPY spring-agent-start-common/pom.xml               spring-agent-start-common/
COPY spring-agent-start-model/pom.xml                spring-agent-start-model/
COPY spring-agent-start-model-provider/pom.xml       spring-agent-start-model-provider/
COPY spring-agent-start-model-provider/spring-agent-start-model-provider-zhipu/pom.xml       spring-agent-start-model-provider/spring-agent-start-model-provider-zhipu/
COPY spring-agent-start-model-provider/spring-agent-start-model-provider-deepseek/pom.xml    spring-agent-start-model-provider/spring-agent-start-model-provider-deepseek/
COPY spring-agent-start-model-provider/spring-agent-start-model-provider-volcengine/pom.xml  spring-agent-start-model-provider/spring-agent-start-model-provider-volcengine/
COPY spring-agent-start-knowledge/pom.xml            spring-agent-start-knowledge/
COPY spring-agent-start-knowledge-store/pom.xml      spring-agent-start-knowledge-store/
COPY spring-agent-start-knowledge-store/spring-agent-start-knowledge-store-pgvector/pom.xml       spring-agent-start-knowledge-store/spring-agent-start-knowledge-store-pgvector/
COPY spring-agent-start-knowledge-store/spring-agent-start-knowledge-store-elasticsearch/pom.xml  spring-agent-start-knowledge-store/spring-agent-start-knowledge-store-elasticsearch/
COPY spring-agent-start-knowledge-store/spring-agent-start-knowledge-store-milvus/pom.xml         spring-agent-start-knowledge-store/spring-agent-start-knowledge-store-milvus/
COPY spring-agent-start-tools/pom.xml                spring-agent-start-tools/
COPY spring-agent-start-agent/pom.xml                spring-agent-start-agent/
COPY spring-agent-start-workflow/pom.xml             spring-agent-start-workflow/
COPY spring-agent-start-trigger/pom.xml              spring-agent-start-trigger/
COPY spring-agent-start-observability/pom.xml        spring-agent-start-observability/
COPY spring-agent-start-web/pom.xml                  spring-agent-start-web/
COPY spring-agent-start-example/pom.xml              spring-agent-start-example/
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -pl spring-agent-start-example -am dependency:go-offline -DskipTests || true

COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -pl spring-agent-start-example -am -DskipTests package

# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
COPY --from=build /workspace/spring-agent-start-example/target/spring-agent-start-example-*.jar app.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseZGC"
EXPOSE 18090
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
