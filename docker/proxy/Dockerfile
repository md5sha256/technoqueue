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

# Stage 2: layer the jar onto itzg's Velocity image. Pull LuckPerms (Velocity)
# from its metadata endpoint so technoqueue's hard dependency is satisfied.
FROM itzg/mc-proxy:latest
ADD https://metadata.luckperms.net/data/downloads /tmp/lp-downloads.json
RUN mkdir -p /server/plugins \
    && LP_URL="$(grep -oE 'https://[^"]*velocity/LuckPerms-Velocity[^"]*\.jar' /tmp/lp-downloads.json | head -n1)" \
    && echo "Fetching LuckPerms from $LP_URL" \
    && curl -fsSL "$LP_URL" -o /server/plugins/LuckPerms-Velocity.jar \
    && rm -f /tmp/lp-downloads.json
COPY --from=builder /app/build/libs/technoqueue-*.jar /server/plugins/
