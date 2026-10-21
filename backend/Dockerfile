FROM maven:3.9.16-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build
WORKDIR /workspace
COPY backend/pom.xml backend/pom.xml
RUN mvn -B -ntp -f backend/pom.xml dependency:go-offline
COPY backend/src backend/src
COPY db db
RUN mvn -B -ntp -f backend/pom.xml -DskipTests package

FROM eclipse-temurin:21.0.12.1_1-jre-jammy@sha256:affcab5356e1270ac35d2361ee83135af2f03e8d580eca78a6d5d6fdbf048db0
WORKDIR /app
RUN groupadd --system agentops && useradd --system --gid agentops agentops
COPY --from=build --chown=agentops:agentops /workspace/backend/target/agentops-backend-0.1.0-SNAPSHOT.jar /app/app.jar
USER agentops
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
