#
# Scala and sbt Dockerfile
# Originally taken from
# https://github.com/hseeberger/scala-sbt
#

# Pull base image
FROM maven:3.3.9-jdk-8

MAINTAINER Arnaud Jutzeler <arnaud.jutzeler@chuv.ch>

ENV SCALA_VERSION 2.11.7
ENV SBT_VERSION 0.13.9

# Install sbt
RUN \
  curl -L -o sbt-$SBT_VERSION.deb http://dl.bintray.com/sbt/debian/sbt-$SBT_VERSION.deb && \
  dpkg -i sbt-$SBT_VERSION.deb && \
  rm sbt-$SBT_VERSION.deb && \
  apt-get update && \
  apt-get install sbt && \
  sbt sbtVersion

# Create a user with id 1000, with some luck it should match your user on the host machine.
RUN adduser --quiet --uid 1000 build
USER build

# Install Scala
## Piping curl directly in tar
RUN \
  curl -fsL http://downloads.typesafe.com/scala/$SCALA_VERSION/scala-$SCALA_VERSION.tgz | tar xfz - -C /home/build/ && \
  echo >> /home/build/.bashrc && \
  echo 'export PATH=~/scala-$SCALA_VERSION/bin:$PATH' >> /home/build/.bashrc && \
  chown build:build /home/build/.bashrc

COPY ./docker/builder/build-in-docker.sh /

# Volume
VOLUME /build
# Define working directory
WORKDIR /build

CMD ["/build-in-docker.sh"]
