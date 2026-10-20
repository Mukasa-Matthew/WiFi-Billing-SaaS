# ---------------------------------------------------------
# Stage 1: Build stage
# ---------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

# Cache Maven wrapper & dependencies
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B || true

# Copy source code and build production jar
COPY src src
RUN ./mvnw clean package -DskipTests -B

# ---------------------------------------------------------
# Stage 2: Minimal runtime image
# ---------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runner
WORKDIR /app

# Create unprivileged system user for security
RUN addgroup -S wifibilling && adduser -S wifibilling -G wifibilling

# Copy executable jar from builder
COPY --from=builder /workspace/target/*.jar app.jar
RUN chown -R wifibilling:wifibilling /app

USER wifibilling

EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
