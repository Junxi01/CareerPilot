# syntax=docker/dockerfile:1
## Multi-stage build: Gradle installDist → JRE runtime (API port 8080).
FROM gradle:8.10.2-jdk21 AS build
WORKDIR /app

COPY backend/ ./
RUN chmod +x gradlew \
    && ./gradlew installDist --no-daemon --no-build-cache

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl

WORKDIR /app
COPY --from=build /app/build/install/careerpilot-backend/ ./

ENV BACKEND_PORT=8080
EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=5s --start-period=50s --retries=6 \
    CMD curl -fsS http://127.0.0.1:8080/health || exit 1

ENTRYPOINT ["./bin/careerpilot-backend"]
