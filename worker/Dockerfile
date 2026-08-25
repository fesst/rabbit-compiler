# Build stage: compile the worker with the same JDK the project targets (17).
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml ./
COPY common-rabbitmq ./common-rabbitmq
COPY gateway ./gateway
COPY source-changer ./source-changer
COPY worker ./worker
RUN mvn -q -pl worker -am package -DskipTests

# Runtime stage: maven image so the built-in worker can compile
# (javac via the JDK, mvn via maven) workspaces submitted by the UI.
FROM maven:3.9-eclipse-temurin-17
WORKDIR /app
COPY --from=build /build/worker/target/worker-0.0.1-SNAPSHOT.jar ./app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
