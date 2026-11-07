# syntax=docker/dockerfile:1
# Build context: the repository root (the Gradle build needs settings.gradle.kts and the fake-provider module, a test dependency).

FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY server ./server
COPY fake-provider ./fake-provider
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :server:bootJar --no-daemon -x test \
    && mkdir -p /out && cp server/build/libs/*.jar /out/app.jar

# Split the fat jar into layers so that a code change does not invalidate the dependency layer.
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
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=6 \
    CMD wget -qO- http://127.0.0.1:8080/actuator/health >/dev/null || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
