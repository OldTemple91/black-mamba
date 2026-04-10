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

# Backend JAR
COPY --from=backend-build /app/api/build/libs/*.jar app.jar

# Frontend static (serve via Spring Boot static resources)
COPY --from=frontend-build /app/dist/ /app/static/

# Nginx for frontend (optional, simple approach: serve from Spring Boot)
EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar", "--spring.web.resources.static-locations=file:/app/static/"]
