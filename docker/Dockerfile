FROM java:8u66-jre

MAINTAINER ludovic.claude54@gmail.com

LABEL eu.humanbrainproject.function=linear-regression \
      eu.humanbrainproject.function.location=federation \
      eu.humanbrainproject.function.version=0.1 \
      eu.humanbrainproject.function.author="Ludovic Claude" \
      eu.humanbrainproject.function.organisation="LREN, CHUV" \
      eu.humanbrainproject.function.date="2015-10-09"

COPY downloads/workflow.jar /opt/workflow/
COPY downloads/workflow.sh /opt/workflow/

RUN chmod +x /opt/workflow/workflow.sh && \
    ln -s /opt/workflow/workflow.sh /run.sh

CMD /run.sh
