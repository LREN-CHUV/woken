#
# Scala and sbt Dockerfile
# Originally taken from
# https://github.com/hseeberger/scala-sbt
#

# Pull base image
FROM maven:3.3.9

MAINTAINER Arnaud Jutzeler <arnaud.jutzeler@chuv.ch>

ENV SCALA_VERSION 2.11.7
ENV SBT_VERSION 0.13.9

# Install Scala
## Piping curl directly in tar
RUN \
  curl -fsL http://downloads.typesafe.com/scala/$SCALA_VERSION/scala-$SCALA_VERSION.tgz | tar xfz - -C /root/ && \
  echo >> /root/.bashrc && \
  echo 'export PATH=~/scala-$SCALA_VERSION/bin:$PATH' >> /root/.bashrc

# Install sbt
RUN \
  curl -L -o sbt-$SBT_VERSION.deb http://dl.bintray.com/sbt/debian/sbt-$SBT_VERSION.deb && \
  dpkg -i sbt-$SBT_VERSION.deb && \
  rm sbt-$SBT_VERSION.deb && \
  apt-get update && \
  apt-get install sbt && \
  sbt sbtVersion


# Volume
RUN mkdir /root/workflow

# Define working directory
WORKDIR /root

CMD ["/root/workflow/build/startup.sh"]


