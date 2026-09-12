FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B -DskipTests package \
 && java -Djarmode=tools -jar target/paycore-api-*.jar extract --layers --destination extracted

FROM eclipse-temurin:21-jre-jammy
RUN useradd --system --uid 1001 --create-home paycore
WORKDIR /app
COPY --from=build --chown=paycore:paycore /app/extracted/dependencies/ ./
COPY --from=build --chown=paycore:paycore /app/extracted/spring-boot-loader/ ./
COPY --from=build --chown=paycore:paycore /app/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=paycore:paycore /app/extracted/application/ ./
USER paycore

# Sized for Render free (512 MB, fractional CPU): small heap, SerialGC, C1 only (measured under load).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=60.0 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k \
 -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m -XX:+ExitOnOutOfMemoryError \
 -Djava.security.egd=file:/dev/./urandom"
ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/paycore-api-*.jar"]
