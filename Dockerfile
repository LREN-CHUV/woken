FROM java:openjdk-8u92-jdk-alpine

MAINTAINER ludovic.claude54@gmail.com

COPY docker/runner/woken.sh /opt/woken/

RUN chmod +x /opt/woken/woken.sh && \
    ln -s /opt/woken/woken.sh /run.sh

ENV WOKEN_VERSION=0.1

# org.label-schema.build-date=$BUILD_DATE
# org.label-schema.vcs-ref=$VCS_REF
LABEL org.label-schema.schema-version="1.0" \
        org.label-schema.license="Apache 2.0" \
        org.label-schema.name="woken" \
        org.label-schema.description="An orchestration platform for Docker containers running data mining algorithms" \
        org.label-schema.url="https://github.com/LREN-CHUV/woken" \
        org.label-schema.vcs-type="git" \
        org.label-schema.vcs-url="https://github.com/LREN-CHUV/woken" \
        org.label-schema.vendor="CHUV" \
        org.label-schema.version="$WOKEN_VERSION" \
        org.label-schema.docker.dockerfile="Dockerfile" \
        org.label-schema.memory-hint="2048"

COPY target/scala-2.11/woken_2.11-$WOKEN_VERSION.jar /opt/woken/woken.jar

CMD ["/run.sh"]
