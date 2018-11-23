# Verified with http://hadolint.lukasmartinelli.ch/
FROM hbpmip/scala-base-build:1.2.6-3 as scala-build-env

# First caching layer: build.sbt and sbt configuration
COPY build.sbt /build/
RUN  mkdir -p /build/project/
COPY project/build.properties project/plugins.sbt project/.gitignore /build/project/

# Run sbt on an empty project and force it to download most of its dependencies to fill the cache
RUN sbt -mem 1500 compile

# Second caching layer: project sources
COPY src/ /build/src/
COPY docker/ /build/docker/
COPY .git/ /build/.git/
COPY .circleci/ /build/.circleci/
COPY tests/ /build/tests/
COPY docs/ /build/docs/
COPY dev/ /build/dev/
COPY .*.cfg .*ignore .*.yaml .*.conf .gitattributes *.md *.sh *.yml *.json *.txt Dockerfile LICENSE /build/

RUN /check-sources.sh

RUN sbt -mem 1500 test assembly

FROM hbpmip/java-base:11.0.1-1

ARG BUILD_DATE
ARG VCS_REF
ARG VERSION

COPY docker/woken.sh /opt/woken/
COPY docker/lets-encrypt-install.sh /opt/woken/
COPY docker/weaver-agent.sh /opt/woken/

RUN apt-get update && apt-get install -y curl ca-cacert \
    && rm -rf /var/lib/apt/lists/*

RUN addgroup woken \
    && adduser --system --disabled-password --uid 1000 --ingroup woken woken \
    && chmod +x /opt/woken/woken.sh \
    && ln -s /opt/woken/woken.sh /run.sh \
    && chown -R woken:woken /opt/woken \
    && chmod +x /opt/woken/lets-encrypt-install.sh \
    && /opt/woken/lets-encrypt-install.sh \
    && chmod +x /opt/woken/weaver-agent.sh \
    && /opt/woken/weaver-agent.sh

COPY --from=scala-build-env /build/target/scala-2.11/woken-all.jar /opt/woken/woken.jar

USER woken
ENV HOME=/home/woken \
    WOKEN_BUGSNAG_KEY=4cfb095348013b4424338126f23da70b
WORKDIR /home/woken

ENTRYPOINT ["/run.sh"]

# 8087: Web service API, health checks on http://host:8087/health
# 8088: Akka cluster
EXPOSE 8087 8088

HEALTHCHECK --start-period=60s CMD curl -v --silent http://localhost:8087/health 2>&1 | grep UP

LABEL org.label-schema.build-date=$BUILD_DATE \
      org.label-schema.name="hbpmip/woken" \
      org.label-schema.description="An orchestration platform for Docker containers running data mining algorithms" \
      org.label-schema.url="https://github.com/LREN-CHUV/woken" \
      org.label-schema.vcs-type="git" \
      org.label-schema.vcs-url="https://github.com/LREN-CHUV/woken" \
      org.label-schema.vcs-ref=$VCS_REF \
      org.label-schema.version="$VERSION" \
      org.label-schema.vendor="LREN CHUV" \
      org.label-schema.license="AGPLv3" \
      org.label-schema.docker.dockerfile="Dockerfile" \
      org.label-schema.memory-hint="2048" \
      org.label-schema.schema-version="1.0"
