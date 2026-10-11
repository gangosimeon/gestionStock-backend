# syntax=docker/dockerfile:1
# =============================================================================
# Image de production du backend GestStock.
#   docker build -t geststock-backend .
# Aucun secret dans l'image : tout passe par des variables d'environnement au démarrage
# (voir docs/production.md). Les tests tournent en CI, pas pendant la construction.
# =============================================================================

# --- Construction ------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Dépendances d'abord : couche réutilisée tant que le pom.xml ne change pas.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src src
RUN ./mvnw -B -q package -DskipTests \
    && cp target/geststock-backend-*.jar app.jar \
    && java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# --- Exécution ---------------------------------------------------------------------
FROM eclipse-temurin:21-jre

# curl : healthcheck du conteneur uniquement.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system geststock \
    && useradd --system --gid geststock --home /app --shell /usr/sbin/nologin geststock

WORKDIR /app
# Couches Spring Boot : dépendances (stables) puis code applicatif (change à chaque version).
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

USER geststock

ENV SPRING_PROFILES_ACTIVE=prod \
    TZ=UTC \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
