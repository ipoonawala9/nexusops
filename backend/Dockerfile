FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon bootJar -x test && cp build/libs/backend-*-SNAPSHOT.jar /src/app.jar

FROM eclipse-temurin:25-jre
# The Ubuntu base ships Canonical's pebble service manager (a Go binary outside dpkg); the JVM entrypoint never uses
# it, and its bundled Go stdlib lags behind security fixes, so it is removed rather than scanned around.
RUN rm -f /usr/bin/pebble && rm -rf /var/lib/pebble \
    && useradd --system --uid 10001 --no-create-home app
WORKDIR /app
COPY --from=build /src/app.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
