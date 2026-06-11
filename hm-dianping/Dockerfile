FROM maven:3.8.6-openjdk-8 AS builder

WORKDIR /workspace
COPY settings.xml /root/.m2/settings.xml
COPY pom.xml .
RUN mvn -q -s /root/.m2/settings.xml -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -s /root/.m2/settings.xml -DskipTests package

FROM eclipse-temurin:8-jre

WORKDIR /app
COPY --from=builder /workspace/target/hm-dianping-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
