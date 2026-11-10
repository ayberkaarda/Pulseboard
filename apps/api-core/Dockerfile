# syntax=docker/dockerfile:1.7

# ---- Build stage ---------------------------------------------------------
FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /workspace

COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
# Normalise line endings in case the wrapper script was checked out with CRLF on Windows.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null

COPY src ./src
RUN ./gradlew --no-daemon bootJar -x test \
    && java -Djarmode=tools -jar build/libs/api-core.jar extract --layers --launcher --destination build/extracted

# ---- Runtime stage -------------------------------------------------------
FROM eclipse-temurin:25-jre-noble AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 pulseboard \
    && useradd --system --uid 10001 --gid pulseboard --no-create-home pulseboard

WORKDIR /app
COPY --from=build --chown=pulseboard:pulseboard /workspace/build/extracted/dependencies/ ./
COPY --from=build --chown=pulseboard:pulseboard /workspace/build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=pulseboard:pulseboard /workspace/build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=pulseboard:pulseboard /workspace/build/extracted/application/ ./

USER pulseboard:pulseboard

ENV SPRING_PROFILES_ACTIVE=docker \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD curl --fail --silent http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
