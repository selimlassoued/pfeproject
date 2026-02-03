#Start with a base image containing Java runtime
FROM eclipse-temurin:21-jdk
#Information around who maintains the image
LABEL "org.opencontainers.image.authors"="zaina.com"
# Add the application's jar to the image
COPY target/job-microservice-0.0.1-SNAPSHOT.jar job-microservice-0.0.1-SNAPSHOT.jar
# execute the application
ENTRYPOINT ["java", "-jar", "job-microservice-0.0.1-SNAPSHOT.jar"]