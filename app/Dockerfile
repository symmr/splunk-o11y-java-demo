FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre
ARG SPLUNK_OTEL_JAVA_VERSION=2.31.3
WORKDIR /app
ADD https://repo.maven.apache.org/maven2/com/splunk/splunk-otel-javaagent/${SPLUNK_OTEL_JAVA_VERSION}/splunk-otel-javaagent-${SPLUNK_OTEL_JAVA_VERSION}.jar /otel/splunk-otel-javaagent.jar
COPY --from=build /src/target/shop-app.jar /app/shop-app.jar
EXPOSE 8080
# Small fixed heap so gc-pressure and memory-leak show up quickly; die on OOM and let Docker restart us.
ENV JAVA_OPTS="-Xms256m -Xmx256m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java -javaagent:/otel/splunk-otel-javaagent.jar $JAVA_OPTS -jar /app/shop-app.jar"]
