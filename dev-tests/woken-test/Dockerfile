# Pull base image
FROM hbpmip/scala-base-build:0.13.16-5

MAINTAINER Arnaud Jutzeler <arnaud.jutzeler@chuv.ch>

COPY docker/test-in-docker.sh /test.sh

COPY build.sbt /build/
COPY src/test/ /build/src/test/
COPY project/ /build/project/

RUN sbt compile test:compile
