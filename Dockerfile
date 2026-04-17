# === Backend Build ===
FROM eclipse-temurin:21-jdk AS backend-build
WORKDIR /app
COPY gradlew settings.gradle build.gradle ./
COPY gradle/ gradle/
COPY api/ api/
COPY application/ application/
COPY domain/ domain/
COPY infra/ infra/
RUN chmod +x gradlew && ./gradlew :api:bootJar --no-daemon -x test

# === Frontend Build ===
FROM node:20-alpine AS frontend-build
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN VITE_API_BASE_URL=/api npm run build

# === Production ===
FROM eclipse-temurin:21-jre
WORKDIR /app

# curl 설치 (healthcheck용)
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Backend bootJar만 복사 (-plain.jar 제외)
COPY --from=backend-build /app/api/build/libs/api-*.jar /app/app.jar

# Frontend 정적 파일을 Spring Boot static resources 경로에 배치
COPY --from=frontend-build /app/dist/ /app/static/

EXPOSE 8081

# React SPA fallback + Spring Boot 컨트롤러 우선순위 유지
# static-locations: 정적 파일 위치 / spring.mvc.static-path-pattern: /** (기본값)
# → /api 컨트롤러는 매핑 우선, 나머지는 static에서 서빙
ENTRYPOINT ["java", "-jar", "/app/app.jar", \
    "--spring.profiles.active=local,docker", \
    "--spring.web.resources.static-locations=file:/app/static/"]
