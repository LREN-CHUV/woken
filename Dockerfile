FROM java:openjdk-8u92-jdk-alpine

MAINTAINER ludovic.claude54@gmail.com

COPY docker/runner/woken.sh /opt/woken/

RUN chmod +x /opt/woken/woken.sh && \
    ln -s /opt/woken/woken.sh /run.sh

ENV WOKEN_VERSION=0.1
COPY target/scala-2.11/woken_2.11-$WOKEN_VERSION.jar /opt/woken/woken.jar

CMD ["/run.sh"]
