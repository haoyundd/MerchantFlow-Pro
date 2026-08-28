FROM maven:3.8.6-openjdk-8 AS builder

WORKDIR /workspace
COPY settings.xml /root/.m2/settings.xml
COPY pom.xml .
RUN mvn -q -s /root/.m2/settings.xml -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -s /root/.m2/settings.xml -DskipTests package

FROM eclipse-temurin:8-jre

ARG OTEL_JAVA_AGENT_VERSION=1.33.6
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && curl -fsSL -o /app/opentelemetry-javaagent.jar \
       "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_JAVA_AGENT_VERSION}/opentelemetry-javaagent.jar" \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /workspace/target/hm-dianping-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8081
HEALTHCHECK --interval=10s --timeout=5s --retries=20 \
    CMD curl -fsS http://localhost:8081/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
