# Stage 1: Build
FROM maven:3.9-eclipse-temurin-17-alpine AS builder

WORKDIR /app

# Copy pom.xml first for layer caching
COPY pom.xml .

# Download dependencies (resolve instead of go-offline for better BOM support)
RUN mvn dependency:resolve -B

# Copy source code. The vendored WSDLs under src/main/resources/wsdl/ auto-activate the
# CXF codegen profiles, so no network is needed for codegen — only Maven Central for deps.
# NOTE: .dockerignore excludes src/main/resources/application-local.yaml (real credentials);
# the image must never contain secrets — runtime config comes from the .env file via compose.
COPY src ./src

# Build the application
RUN mvn clean package -DskipTests -B

# Stage 2: Run
FROM eclipse-temurin:17-jre-alpine

# Create non-root user
RUN addgroup -S spring && adduser -S spring -G spring

WORKDIR /app

COPY --from=builder /app/target/promostandards-0.0.1-SNAPSHOT.jar app.jar

# --- PaceSetter TLS fallback (only if live SOAP calls 502 with a PKIX/TLS error) -----------
# The supplier's cert chain may be missing from the JRE truststore (known gotcha). If the
# smoke test in DEPLOY.md fails on TLS, fetch the chain on the server:
#   openssl s_client -connect pacesetterawards.com:443 -showcerts </dev/null \
#     | awk '/BEGIN CERT/,/END CERT/' > pacesetter-chain.pem
# then uncomment the two lines below and rebuild:
# COPY pacesetter-chain.pem /tmp/pacesetter-chain.pem
# RUN keytool -importcert -cacerts -storepass changeit -noprompt \
#       -alias pacesetter -file /tmp/pacesetter-chain.pem && rm /tmp/pacesetter-chain.pem
# -------------------------------------------------------------------------------------------

RUN chown -R spring:spring /app

USER spring:spring

# Force the app to listen on 8080 so it matches EXPOSE and the healthcheck below.
ENV SERVER_PORT=8080

# JVM container ergonomics: cap heap at 75% of cgroup memory, lower idle CPU usage.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080

# Give Spring Boot time to start before the healthcheck starts counting failures.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

# The Spring profile (prod) is NOT baked in — docker-compose.yaml sets SPRING_PROFILES_ACTIVE.
ENTRYPOINT ["java", "-jar", "app.jar"]
