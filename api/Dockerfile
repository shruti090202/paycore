# ---------- build ----------
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B -DskipTests package \
 && java -Djarmode=tools -jar target/paycore-api-*.jar extract --layers --destination extracted

# ---------- runtime ----------
FROM eclipse-temurin:21-jre-jammy
RUN useradd --system --uid 1001 --create-home paycore
WORKDIR /app
# Layered copy: dependencies change rarely, so rebuilds/pushes only ship the application layer.
COPY --from=build --chown=paycore:paycore /app/extracted/dependencies/ ./
COPY --from=build --chown=paycore:paycore /app/extracted/spring-boot-loader/ ./
COPY --from=build --chown=paycore:paycore /app/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=paycore:paycore /app/extracted/application/ ./
USER paycore

# Tuned for Render free: ~512 MB RAM, ~0.1 vCPU.
#  MaxRAMPercentage=60  -> ~300 MB heap, leaving room for metaspace/code cache/threads/native
#  UseSerialGC          -> single-threaded GC has the least overhead on a fractional CPU
#  TieredStopAtLevel=1  -> C1 only: avoids C2 compilation bursts that stall a 0.1-CPU box (the load test decides)
#  Xss512k              -> smaller platform-thread stacks; request handling uses virtual threads anyway
#  ExitOnOutOfMemoryError -> let Render restart us instead of limping along
ENV JAVA_OPTS="-XX:MaxRAMPercentage=60.0 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k \
 -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m -XX:+ExitOnOutOfMemoryError \
 -Djava.security.egd=file:/dev/./urandom"
ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080
# extract --layers yields a thin jar whose manifest Class-Path points at lib/, so a plain -jar launch works (fast startup, no nested-jar loader).
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/paycore-api-*.jar"]
