# 1. Build stage
FROM gradle:8.5-jdk21 AS build
WORKDIR /app

COPY build.gradle settings.gradle ./
COPY src ./src

# 테스트는 CI 단계에서 이미 했으므로 생략(-x test)하고, bootJar만 빌드
RUN gradle bootJar -x test --no-daemon

# 2. Run stage
FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /app/build/libs/*SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
