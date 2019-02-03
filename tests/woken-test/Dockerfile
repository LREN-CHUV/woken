# Pull base image
FROM hbpmip/scala-base-build:1.2.6-5 as scala-build-env

# First caching layer: build.sbt and sbt configuration
COPY build.sbt /build/
RUN  mkdir -p /build/project/
COPY project/build.properties project/plugins.sbt project/.gitignore /build/project/

# Run sbt on an empty project and force it to download most of its dependencies to fill the cache
RUN sbt -mem 1500 compile

# Second caching layer: project sources
COPY docker/test-in-docker.sh /test.sh
COPY src/test/ /build/src/test/

RUN sbt -mem 1500 test:compile

ENTRYPOINT ["/test.sh"]

# 8197: Akka TCP
EXPOSE 8197
