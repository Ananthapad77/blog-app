# The Jenkins pipeline runs "mvn package" first, so the jar already exists in target/
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY target/blog-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-Xmx256m", "-jar", "app.jar"]
