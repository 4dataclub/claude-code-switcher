# Build-Stage
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q -DskipTests dependency:go-offline || true
COPY src ./src
RUN mvn -B -q -DskipTests package

# Runtime-Stage (jre statt jre-alpine — alpine fehlt linux/arm64-Tag)
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 2000
ENTRYPOINT ["java","-jar","/app/app.jar"]
