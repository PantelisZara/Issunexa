# syntax=docker/dockerfile:1
FROM node:24.22.2-bookworm-slim AS frontend
WORKDIR /build
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/tsconfig*.json frontend/vite.config.ts frontend/index.html ./
COPY frontend/src/ src/
COPY frontend/public/ public/
RUN npm run build

FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /build
COPY --chmod=0755 backend/mvnw ./
COPY backend/.mvn/ .mvn/
COPY backend/pom.xml ./
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    ./mvnw --batch-mode --no-transfer-progress dependency:go-offline
COPY backend/src/main/ src/main/
COPY --from=frontend /build/dist/ src/main/resources/static/
# Tests are verified separately; image construction is not a test gate.
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    ./mvnw --batch-mode --no-transfer-progress -DskipTests package \
    && cp target/*.jar application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:21-jre-noble AS runtime
RUN groupadd --gid 10001 issunexa \
    && useradd --uid 10001 --gid 10001 --no-create-home --home-dir /app --shell /usr/sbin/nologin issunexa
WORKDIR /app
COPY --from=build --chown=10001:10001 /build/extracted/dependencies/ ./
COPY --from=build --chown=10001:10001 /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=10001:10001 /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=10001:10001 /build/extracted/application/ ./
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50 -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=32m -Xss512k"
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "application.jar"]
