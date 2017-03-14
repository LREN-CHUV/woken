FROM java:8u66-jre

VOLUME /opt/woken-validation/config
VOLUME /opt/woken-validation/lib

EXPOSE 8082

ENTRYPOINT java -jar -Dconfig.file=/opt/woken-validation/config/application.conf /opt/woken-validation/lib/woken-validation.jar
