# Build stage — Docker build context is the REPO ROOT (railway.json at repo root pins this);
# frontend/web is copied into the image and served same-origin by Ktor (WEB_DIR).
FROM docker.io/library/eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app

COPY backend/gradle/ gradle/
COPY backend/gradlew gradlew
COPY backend/build.gradle.kts backend/settings.gradle.kts backend/gradle.properties ./
# Cache dependencies
RUN ./gradlew --no-daemon dependencies --configuration runtimeClasspath 2>/dev/null || true

COPY backend/src/ src/
RUN ./gradlew --no-daemon installDist

# Runtime stage
FROM docker.io/library/eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=builder /app/build/install/alt-backend/ ./
COPY frontend/web/ web/

USER appuser

EXPOSE 8080

ENV PORT=8080
ENV TRANSPORT_PROVIDER=mock
ENV WEB_DIR=web

ENTRYPOINT ["bin/alt-backend"]
