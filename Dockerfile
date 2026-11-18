FROM node:22-alpine AS frontend-build
WORKDIR /frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
ARG VITE_GOOGLE_CLIENT_ID
ARG VITE_GOOGLE_API_KEY
ARG VITE_GOOGLE_APP_ID
ENV VITE_GOOGLE_CLIENT_ID=$VITE_GOOGLE_CLIENT_ID \
    VITE_GOOGLE_API_KEY=$VITE_GOOGLE_API_KEY \
    VITE_GOOGLE_APP_ID=$VITE_GOOGLE_APP_ID
RUN npm run build

FROM maven:3.9.9-eclipse-temurin-21 AS backend-build
WORKDIR /workspace
COPY backend/pom.xml ./
COPY backend/src ./src
COPY --from=frontend-build /frontend/dist ./src/main/resources/static
RUN mvn --batch-mode verify

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --create-home app
WORKDIR /app
COPY --from=backend-build /workspace/target/driveatlas-api-0.0.1-SNAPSHOT.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
