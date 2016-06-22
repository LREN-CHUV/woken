FROM java:8u66-jre

RUN apt-get update && apt-get install -y wget
RUN wget https://github.com/jwilder/dockerize/releases/download/v0.2.0/dockerize-linux-amd64-v0.2.0.tar.gz
RUN tar -C /usr/local/bin -xzvf dockerize-linux-amd64-v0.2.0.tar.gz

COPY woken.sh /opt/woken/bin/

VOLUME /opt/woken/config
VOLUME /opt/woken/lib

EXPOSE 8087

CMD "/opt/woken/bin/woken.sh"
