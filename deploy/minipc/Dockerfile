FROM eclipse-temurin:21-jre

WORKDIR /app
COPY scds-next.jar /app/scds-next.jar
USER 10001:10001

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65", "-jar", "/app/scds-next.jar", "--spring.config.additional-location=file:/run/scds/application.properties"]
