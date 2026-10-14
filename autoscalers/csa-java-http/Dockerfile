FROM gradle:9.3.0-jdk21-alpine AS build

WORKDIR /home/gradle/src
COPY --chown=gradle:gradle . .
RUN gradle bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
ARG PROFILE=h
COPY profiles/${PROFILE}.yaml /app/config/application.yaml
COPY --from=build /home/gradle/src/build/libs/csa-java-http-0.1.0-SNAPSHOT.jar /app/csa-java-http.jar

USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/csa-java-http.jar", "--spring.config.additional-location=file:/app/config/"]
