# Build stage: compile with the same JDK the project targets (17).
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml ./
COPY common-rabbitmq ./common-rabbitmq
COPY source-changer ./source-changer
RUN mvn -q -pl source-changer -am package -DskipTests

# Runtime stage: maven image so the built-in local worker can compile
# (javac via the JDK, mvn via maven) when EXAMPLE_WORKER_LOCAL=true.
FROM maven:3.9-eclipse-temurin-17
WORKDIR /app
COPY --from=build /build/source-changer/target/source-changer-0.0.1-SNAPSHOT.jar ./app.jar
EXPOSE 8100
ENTRYPOINT ["java", "-jar", "app.jar"]
