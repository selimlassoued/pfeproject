# Start with a base image containing Java runtime
FROM eclipse-temurin:21-jdk

# Information around who maintains the image
LABEL org.opencontainers.image.authors="recrutment.com"

# Add the application's jar to the image
COPY target/audit-service-0.0.1-SNAPSHOT.jar audit-service-0.0.1-SNAPSHOT.jar

# Execute the application
ENTRYPOINT ["java", "-jar", "audit-service-0.0.1-SNAPSHOT.jar"]
