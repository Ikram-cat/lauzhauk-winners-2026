# Build stage
FROM docker.io/library/eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app

COPY gradle/ gradle/
COPY gradlew gradlew
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
# Cache dependencies
RUN ./gradlew --no-daemon dependencies --configuration runtimeClasspath 2>/dev/null || true

COPY src/ src/
RUN ./gradlew --no-daemon installDist

# Runtime stage
FROM docker.io/library/eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=builder /app/build/install/cff-plan-b-backend/ ./

USER appuser

EXPOSE 8080

ENV PORT=8080
ENV TRANSPORT_PROVIDER=mock

ENTRYPOINT ["bin/cff-plan-b-backend"]
