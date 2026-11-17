# syntax=docker/dockerfile:1
#
# iou as one container: the Spring Boot API plus the static web app it serves.
# Used by Render (see render.yaml) and by `docker compose --profile app up --build`.

# ---- build: compile and package with Maven ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
# Dependencies first, so they are cached until pom.xml changes.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
# Tests run in CI (they need a database), not while building the image.
RUN mvn -B -q -DskipTests package \
 && java -Djarmode=tools -jar target/iou.jar extract --destination /workspace/app

# ---- run: a small JRE image ----
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 1001 --home-dir /app --shell /usr/sbin/nologin iou \
 && mkdir -p /app && chown iou:iou /app
WORKDIR /app
USER iou
COPY --from=build --chown=iou:iou /workspace/app/lib lib
COPY --from=build --chown=iou:iou /workspace/app/iou.jar iou.jar

# JVM settings for a small host (Render free: 0.1 CPU, 512 MB):
#   SerialGC + C1-only compilation   least memory and the quickest start on a slow CPU
#   MaxRAMPercentage=50              heap is half the container, leaving room for the rest of the JVM
ENV JAVA_OPTS="-XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:MaxRAMPercentage=50 -Xss512k -XX:+ExitOnOutOfMemoryError"

# Start the app once without a database (profile "cds") and record the classes it loads.
# The archive lets every later start skip most class loading: about a third faster,
# which matters each time the free service wakes from sleep.
RUN java $JAVA_OPTS -XX:ArchiveClassesAtExit=iou.jsa -Dspring.context.exit=onRefresh \
      -Dspring.profiles.active=prod,cds \
      -Dspring.datasource.url=jdbc:postgresql://127.0.0.1:1/none \
      -DIOU_JWT_SECRET=image-build-only-not-a-real-secret-0000 \
      -jar iou.jar \
 && test -s iou.jsa

ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080
# The platform sets PORT; the app listens on it (8080 if unset).
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -XX:SharedArchiveFile=iou.jsa -jar iou.jar"]
