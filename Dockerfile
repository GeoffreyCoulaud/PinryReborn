# Runtime-only image for the Quarkus fast-jar artifact.
#
# The artifact (api-application/build/quarkus-app/) is produced by Gradle
# BEFORE `docker build` runs. The JVM jars are architecture-independent, so a
# single Gradle build feeds every target platform of a multi-arch buildx run.
#
# Base is glibc (eclipse-temurin:21-jre), NOT alpine/musl: sqlite-jdbc ships
# native libraries and glibc is the safe choice to load them, which the smoke
# test confirms by running the SQLite migrations at startup.
FROM eclipse-temurin:21-jre

# curl is used only by the HEALTHCHECK below. Install it minimally and drop the
# apt lists to keep the layer small.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Non-root runtime user.
RUN groupadd --system --gid 1001 pinry \
    && useradd --system --uid 1001 --gid 1001 --home-dir /app --no-create-home pinry

WORKDIR /app

# The SQLite database lives on a mounted volume. DB_PATH is read by
# EbeanDatabaseProducer; Ebean runs the migrations against it at startup.
ENV DB_PATH=/data/pinry.db
RUN mkdir -p /data && chown 1001:1001 /data
VOLUME /data

# Copy the Quarkus fast-jar layout. lib/ changes least often (better layer
# caching), then the application classes, and the tiny runner jar last.
COPY --chown=1001:1001 api-application/build/quarkus-app/lib/ /app/lib/
COPY --chown=1001:1001 api-application/build/quarkus-app/app/ /app/app/
COPY --chown=1001:1001 api-application/build/quarkus-app/quarkus/ /app/quarkus/
COPY --chown=1001:1001 api-application/build/quarkus-app/quarkus-run.jar /app/quarkus-run.jar

USER 1001

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD curl -fsS http://localhost:8080/q/health || exit 1

ENTRYPOINT ["java", "-jar", "quarkus-run.jar"]
