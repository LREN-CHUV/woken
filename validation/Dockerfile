# Verified with http://hadolint.lukasmartinelli.ch/

FROM hbpmip/scala-base-build:dc0eb54 as build-scala-env

RUN sbt about

COPY project/build.properties project/plugins.sbt /cache/dummy/project/
WORKDIR /cache/dummy

USER build
RUN sbt about

USER root
COPY build.sbt /my-project/
RUN  mkdir -p /my-project/project/ && chown -R build:build /my-project/
COPY project/ /my-project/project/
RUN  chown -R build:build /my-project/

USER build
WORKDIR /my-project
RUN sbt about

COPY src/ /my-project/src/

RUN sbt assembly

FROM openjdk:8u131-jdk-alpine

MAINTAINER ludovic.claude54@gmail.com

RUN mkdir -p /opt/woken-validation/config

RUN adduser -H -D -u 1000 woken \
    && chown -R woken:woken /opt/woken-validation

COPY --from=build-scala-env /my-project/target/scala-2.11/woken-validation-assembly-dev.jar /opt/woken-validation/woken-validation.jar

USER woken

# org.label-schema.build-date=$BUILD_DATE
# org.label-schema.vcs-ref=$VCS_REF
LABEL org.label-schema.schema-version="1.0" \
        org.label-schema.license="Apache 2.0" \
        org.label-schema.name="woken" \
        org.label-schema.description="An orchestration platform for Docker containers running data mining algorithms" \
        org.label-schema.url="https://github.com/LREN-CHUV/woken" \
        org.label-schema.vcs-type="git" \
        org.label-schema.vcs-url="https://github.com/LREN-CHUV/woken" \
        org.label-schema.vendor="LREN CHUV" \
        org.label-schema.version="githash" \
        org.label-schema.docker.dockerfile="Dockerfile" \
        org.label-schema.memory-hint="2048"

WORKDIR /opt/woken-validation

ENTRYPOINT java -jar -Dconfig.file=config/application.conf woken-validation.jar

