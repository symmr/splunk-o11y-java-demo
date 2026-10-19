FROM maven:3.9-eclipse-temurin-21 AS build
# Secure Application demo: deliberately old middleware with known CVEs (loaded at runtime, so the agent reports them).
# Override these (docker-compose.yml / .env) with a fixed version to show the findings disappear.
#   embedded Tomcat 10.1.34   -> CVE-2025-24813 and others
#   PostgreSQL JDBC 42.7.1    -> CVE-2024-1597
ARG TOMCAT_VERSION=10.1.34
ARG POSTGRESQL_VERSION=42.7.1
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B -Dtomcat.version=${TOMCAT_VERSION} -Dpostgresql.version=${POSTGRESQL_VERSION} dependency:go-offline
COPY src ./src
RUN mvn -q -B -Dtomcat.version=${TOMCAT_VERSION} -Dpostgresql.version=${POSTGRESQL_VERSION} package -DskipTests

FROM eclipse-temurin:21-jre
ARG SPLUNK_OTEL_JAVA_VERSION=2.31.3
WORKDIR /app
# Secure Application build of the agent (drop-in for splunk-otel-javaagent: APM + runtime library/vulnerability data).
# The file name must keep "splunk-otel-javaagent" in it.
ADD https://repo.maven.apache.org/maven2/com/splunk/splunk-otel-javaagent-csa/${SPLUNK_OTEL_JAVA_VERSION}/splunk-otel-javaagent-csa-${SPLUNK_OTEL_JAVA_VERSION}.jar /otel/splunk-otel-javaagent.jar
COPY --from=build /src/target/shop-app.jar /app/shop-app.jar
EXPOSE 8080
# Small fixed heap so gc-pressure and memory-leak show up quickly; die on OOM and let Docker restart us.
ENV JAVA_OPTS="-Xms256m -Xmx256m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java -javaagent:/otel/splunk-otel-javaagent.jar $JAVA_OPTS -jar /app/shop-app.jar"]
