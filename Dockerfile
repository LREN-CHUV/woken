# Verified with http://hadolint.lukasmartinelli.ch/
FROM hbpmip/scala-base-build:0.13.16-5 as scala-build-env

# First caching layer: build.sbt and sbt configuration
COPY build.sbt /build/
RUN  mkdir -p /build/project/
COPY project/build.properties project/plugins.sbt project/.gitignore /build/project/

# Run sbt on an empty project and force it to download most of its dependencies to fill the cache
RUN sbt compile

# Second caching layer: project sources
COPY src/ /build/src/
COPY .git/ /build/.git/
COPY .circleci/ /build/.circleci/
COPY dev-tests/ /build/dev-tests/
COPY docker/ /build/docker/
COPY docs/ /build/docs/
COPY .*.cfg .*ignore .*.yaml .*.conf .gitattributes *.md *.sh *.yml *.json *.txt Dockerfile LICENSE /build/

RUN /check-sources.sh

RUN sbt assembly

FROM hbpmip/java-base:8u131-2

MAINTAINER Ludovic Claude <ludovic.claude@chuv.ch>

ARG BUILD_DATE
ARG VCS_REF
ARG VERSION

COPY docker/runner/woken.sh /opt/woken/

RUN adduser -H -D -u 1000 woken \
    && chmod +x /opt/woken/woken.sh \
    && ln -s /opt/woken/woken.sh /run.sh \
    && chown -R woken:woken /opt/woken

COPY --from=scala-build-env /build/target/scala-2.11/woken-assembly-$VERSION.jar /opt/woken/woken.jar

USER woken

LABEL org.label-schema.build-date=$BUILD_DATE \
      org.label-schema.name="hbpmip/woken" \
      org.label-schema.description="An orchestration platform for Docker containers running data mining algorithms" \
      org.label-schema.url="https://github.com/LREN-CHUV/woken" \
      org.label-schema.vcs-type="git" \
      org.label-schema.vcs-url="https://github.com/LREN-CHUV/woken" \
      org.label-schema.vcs-ref=$VCS_REF \
      org.label-schema.version="$VERSION" \
      org.label-schema.vendor="LREN CHUV" \
      org.label-schema.license="Apache 2.0" \
      org.label-schema.docker.dockerfile="Dockerfile" \
      org.label-schema.memory-hint="2048" \
      org.label-schema.schema-version="1.0"

EXPOSE 8087
EXPOSE 8088

CMD ["/run.sh"]
