FROM java:8u92-jdk-alpine

MAINTAINER ludovic.claude54@gmail.com

COPY downloads/woken.sh /opt/woken/

RUN chmod +x /opt/woken/woken.sh && \
    ln -s /opt/woken/woken.sh /run.sh

COPY downloads/woken.jar /opt/woken/

CMD /run.sh
