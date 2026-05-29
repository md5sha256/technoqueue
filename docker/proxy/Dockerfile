# syntax=docker/dockerfile:1.7
# Stage 1: build the Technoqueue plugin jar from source using the project's
# Gradle wrapper, so the build image is decoupled from base-image Gradle drift.
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /app
COPY --link gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY --link gradle gradle
COPY --link src src
RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew && ./gradlew --no-daemon build -x test

# Stage 2: layer the jar onto itzg's Velocity image.
FROM itzg/mc-proxy:latest
COPY --from=builder /app/build/libs/technoqueue-*.jar /server/plugins/
