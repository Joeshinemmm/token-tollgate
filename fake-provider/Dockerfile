# syntax=docker/dockerfile:1
# Build context: the repository root. Same shape as server/Dockerfile.

FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY server ./server
COPY fake-provider ./fake-provider
# A checkout from Windows may lose the execute bit, so set it before running the wrapper.
# sharing=locked: the server and fake-provider images build in parallel and Gradle cannot share one cache dir at the same time.
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    chmod +x gradlew && ./gradlew :fake-provider:bootJar --no-daemon -x test \
    && mkdir -p /out && cp fake-provider/build/libs/*.jar /out/app.jar

FROM eclipse-temurin:25-jre-alpine AS extract
WORKDIR /app
COPY --from=build /out/app.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=extract /app/extracted/dependencies/ ./
COPY --from=extract /app/extracted/spring-boot-loader/ ./
COPY --from=extract /app/extracted/snapshot-dependencies/ ./
COPY --from=extract /app/extracted/application/ ./
USER app
EXPOSE 9090
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=6 \
    CMD wget -qO- http://127.0.0.1:9090/actuator/health >/dev/null || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
