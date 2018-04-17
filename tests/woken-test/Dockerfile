# Pull base image
FROM hbpmip/scala-base-build:1.1.0-2

MAINTAINER Ludovic Claude <ludovic.claude@chuv.ch>

COPY docker/test-in-docker.sh /test.sh

COPY build.sbt /build/
COPY src/test/ /build/src/test/
COPY project/ /build/project/

RUN sbt compile test:compile

COPY application.conf /build/

ENTRYPOINT ["/test.sh"]
