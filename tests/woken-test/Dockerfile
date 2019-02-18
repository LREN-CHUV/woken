# Pull base image
FROM hbpmip/scala-base-build:1.2.6-7 as scala-build-env

RUN addgroup woken \
    && adduser --system --disabled-password --uid 1000 --ingroup woken woken \
    && cp -r /root/.m2 /root/.sbt /home/woken/ \
    && mkdir -p /home/woken/.ivy2 /root/.cache \
    && chmod a+rX /root \
    && ln -s -T /usr/share/ivy/ref/repository /home/woken/.ivy2/cache \
    && ln -s /root/.cache /home/woken/ \
    && chown -R woken:woken /root/.cache /home/woken/ /build/ /usr/share/ivy/ref/repository/

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
RUN chown woken:woken /home/woken/.cache && chown -R woken:woken /build/ /home/woken/.cache/

USER woken
ENV HOME=/home/woken
WORKDIR /build

ENTRYPOINT ["/test.sh"]

# 8197: Akka TCP
EXPOSE 8197
