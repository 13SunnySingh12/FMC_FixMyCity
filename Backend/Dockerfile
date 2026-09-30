# Build: tests run in CI (they need Docker for Testcontainers), so the image build only packages.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -ntp -q dependency:go-offline
COPY src src
RUN mvn -B -ntp -q package -DskipTests

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 fmc
WORKDIR /app
COPY --from=build /app/target/fmc-backend-*.jar app.jar
USER fmc
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
