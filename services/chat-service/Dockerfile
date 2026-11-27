# syntax=docker/dockerfile:1
# chat-service image (ADR-004). The build context is the repo root, for the parent POM and the wrapper:
#   docker build -f services/chat-service/Dockerfile -t chatconnect/chat-service:dev .

ARG JAVA_VERSION=17

# ---- build: this module plus only what it depends on (-am), with the pinned ./mvnw
FROM eclipse-temurin:${JAVA_VERSION}-jdk-noble AS build
# ./mvnw verifies the Maven *zip* checksum; without unzip it fetches the .tar.gz and fails (P1.C1).
RUN apt-get update \
 && apt-get install -y --no-install-recommends unzip \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn/ .mvn/
COPY services/ services/
# One shared ~/.m2 cache for all service images; "locked": the Maven local repo isn't safe for
# concurrent writers, and compose builds the images in parallel. Tests ran in ./mvnw verify (I12).
RUN --mount=type=cache,id=chatconnect-m2,target=/root/.m2,sharing=locked \
    ./mvnw -B -q -pl services/chat-service -am -Dmaven.test.skip=true package \
 && java -Djarmode=tools -jar services/chat-service/target/chat-service-*.jar extract --layers --launcher --destination /extract

# ---- runtime: JRE only, non-root, layers ordered from least to most frequently changed
FROM eclipse-temurin:${JAVA_VERSION}-jre-noble
ARG GIT_SHA=unknown
ARG APP_VERSION=dev
# version overrides the Ubuntu base image's own "24.04" label.
LABEL org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.title="chatconnect-chat-service" \
      org.opencontainers.image.revision="${GIT_SHA}"
RUN groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
# Root-owned and not writable by the app user: the process can't modify its own code.
COPY --from=build /extract/dependencies/ ./
COPY --from=build /extract/spring-boot-loader/ ./
COPY --from=build /extract/snapshot-dependencies/ ./
COPY --from=build /extract/application/ ./
USER 10001:10001
EXPOSE 8082
# Exec form: the JVM is PID 1 and gets SIGTERM directly (DEBT-4); "sh -c" would swallow it.
# Flags live here, not in JAVA_TOOL_OPTIONS/JDK_JAVA_OPTIONS: the JVM prints a non-JSON
# "Picked up ..." line for those (breaks ADR-003's JSON-only logs).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "org.springframework.boot.loader.launch.JarLauncher"]
