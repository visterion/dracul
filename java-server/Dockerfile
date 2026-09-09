# Stage 1 — build frontend (Node 22)
# NOTE: On hosts where Docker containers cannot spawn sub-processes
#       (some Proxmox/LXC kernels), pre-build with:
#           cd chronicle && npm run build
#       then use the companion java-server/Dockerfile.local for local builds.
#       CI (GitHub Actions ubuntu-latest) uses this file unchanged.
FROM node:26-bookworm-slim AS frontend
WORKDIR /app/chronicle
COPY chronicle/package*.json ./
RUN npm ci
COPY chronicle/ .
RUN npm run build

# Stage 2 — build backend (bakes frontend dist/ into the JAR)
FROM eclipse-temurin:25-jdk AS backend
WORKDIR /build
COPY java-server/ java-server/
COPY --from=frontend /app/chronicle/dist java-server/src/main/resources/static/
WORKDIR /build/java-server
RUN ./mvnw -q -DskipTests package

# Stage 3 — minimal runtime image
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app
COPY --from=backend /build/java-server/target/*.jar app.jar
EXPOSE 8080
# UTC is not optional: ExecutorSignal.createdAt is Timestamp.toString() of a TIMESTAMPTZ, rendered
# in the JVM default zone, while the trading-day age (TradingDays.ageOf) compares against
# LocalDate.now(UTC). Under a negative-offset JVM every 00:00-05:00 UTC emission would be dated one
# day early and silently counted one trading day short -- in veto #3 and in PendingSignalSweeper.
ENTRYPOINT ["java", "-Duser.timezone=UTC", "-jar", "app.jar"]
