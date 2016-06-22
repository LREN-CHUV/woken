FROM java:8u66-jre

MAINTAINER ludovic.claude54@gmail.com

LABEL eu.humanbrainproject.function=linear-regression \
      eu.humanbrainproject.function.location=federation \
      eu.humanbrainproject.function.version=0.1 \
      eu.humanbrainproject.function.author="Ludovic Claude" \
      eu.humanbrainproject.function.organisation="LREN, CHUV" \
      eu.humanbrainproject.function.date="2015-10-09"

COPY downloads/woken.jar /opt/woken/
COPY downloads/woken.sh /opt/woken/

RUN chmod +x /opt/woken/woken.sh && \
    ln -s /opt/woken/woken.sh /run.sh

CMD /run.sh
