# syntax=docker/dockerfile:1.7

# ---- Build: compile, test-skip, and explode the Boot jar into layers ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests \
 && java -Djarmode=tools -jar target/flight-notification-service-*.jar \
      extract --layers --launcher --destination target/extracted

# ---- Runtime: JRE only, non-root, layered for small rebuilds ----
FROM eclipse-temurin:25-jre AS runtime

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app --home-dir /app --shell /usr/sbin/nologin app

WORKDIR /app
# Least-frequently changing layers first.
COPY --from=build --chown=app:app /workspace/target/extracted/dependencies/ ./
COPY --from=build --chown=app:app /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /workspace/target/extracted/application/ ./

USER 10001:10001
EXPOSE 3101

ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=6 \
  CMD curl -fsS http://localhost:3101/actuator/health/liveness >/dev/null || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
