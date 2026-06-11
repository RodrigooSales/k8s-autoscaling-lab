FROM gradle:jdk21 AS build
RUN apt-get update
RUN apt-get install zip -y
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src
RUN gradle build --no-daemon
RUN cd /home/gradle/src/build/distributions/ && unzip /home/gradle/src/build/distributions/csa-java.zip


FROM registry.k8s.lab/custom-self-adapter:alpine-latest

WORKDIR /app


RUN apk add --no-cache jq unzip openjdk21-jre || apk add --no-cache jq unzip openjdk21
ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

ADD config.yaml /

COPY --from=build /home/gradle/src/build/distributions/csa-java /app/csa-java
