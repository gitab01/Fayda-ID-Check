# Build stage: JDK 21 + Maven. Tests are run by `mvn test` on the host; the image build skips
# them so a deploy does not depend on the test suite's H2 profile.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre AS run
WORKDIR /app
# curl exists only so docker-compose can probe /api/v1/health; the service itself needs nothing.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/target/verification-service.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70.0", "-jar", "/app/app.jar"]
