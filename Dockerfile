# Runtime-only image for the Quarkus fast-jar artifact.
#
# The artifact (api-application/build/quarkus-app/) is produced by Gradle
# BEFORE `docker build` runs. The JVM jars are architecture-independent, so a
# single Gradle build feeds every target platform of a multi-arch buildx run.
#
# Base is glibc (eclipse-temurin:25-jre), NOT alpine/musl: sqlite-jdbc ships
# native libraries and glibc is the safe choice to load them, which the smoke
# test confirms by running the SQLite migrations at startup.
FROM eclipse-temurin:25-jre

# curl is used only by the HEALTHCHECK below. libvips42t64 is the native
# library vips-ffm loads for api-imaging-vips (eclipse-temurin:25-jre is
# Ubuntu-based, post 64-bit-time_t transition, hence the t64 suffix). Install
# both minimally and drop the apt lists to keep the layer small.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl libvips42t64 \
    && rm -rf /var/lib/apt/lists/*

# Non-root runtime user.
RUN groupadd --system --gid 1001 pinry \
    && useradd --system --uid 1001 --gid 1001 --home-dir /app --no-create-home pinry

WORKDIR /app

# The SQLite database lives on a mounted volume. DB_PATH is wired into
# datasource.db.url by application.properties and read by EbeanDatabaseProducer;
# Ebean runs the migrations against it at startup.
ENV DB_PATH=/data/pinry.db
RUN mkdir -p /data && chown 1001:1001 /data
VOLUME /data

# On-disk bytes live outside the database (no blobs in SQLite): original image
# bytes under images.data_dir, user data export archives under exports.data_dir,
# and uploaded import archives under imports.data_dir. All three default to
# /var/lib/pinry/* (see application.properties) and MUST be redirected to
# writable, persistent locations at deploy time, e.g.
#   -e IMAGES_DATA_DIR=/data/images -e EXPORTS_DATA_DIR=/data/exports \
#   -e IMPORTS_DATA_DIR=/data/imports
# so they share the /data volume above. exports.data_dir is a SEPARATE dataset
# from images: export archives are large, short-lived (7-day retention) and
# regenerable, so an operator may want it on its own volume with its own backup
# and quota policy. imports.data_dir is a third one for the same reasons, and it
# takes uploads of up to imports.max_archive_bytes (20 GiB by default) each.
#
# images is created on first write, so the container leaves it alone. imports
# and exports are different: ImportDataDirectoryCheck and ExportDataDirectoryCheck
# create and probe theirs from a startup observer, so a default the image does not
# provide refuses the boot of every deployment, whether or not anyone imports or
# exports. /var/lib is root-owned and uid 1001 cannot create under it, hence the
# line below. An operator who leaves IMPORTS_DATA_DIR or EXPORTS_DATA_DIR unset
# therefore writes into the container's writable layer rather than onto a volume;
# ImportDataDirectoryImageTest and ExportDataDirectoryImageTest hold the pairs.
# One chown per directory: the tests above read `chown uid:gid path` and stop at
# the first path, so a shared call would leave the second one unpinned.
RUN mkdir -p /var/lib/pinry/imports /var/lib/pinry/exports \
    && chown 1001:1001 /var/lib/pinry/imports \
    && chown 1001:1001 /var/lib/pinry/exports

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

# --enable-native-access: sqlite-jdbc loads its native library via the restricted
# System::load, which JDK 25 warns about and will block in a future release unless
# native access is granted. (vips-ffm, added later, needs the same grant.)
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "quarkus-run.jar"]
