FROM hbpmip/java-base:latest

MAINTAINER arnaud@ahead-solutions.ch

#ENV JAVA_CLASSPATH=./dist/scala-hadrian.jar
#ENV JAVA_MAINCLASS=eu.hbp.mip.validation.Main
#ENV JAVA_CONFIG=/dist/config/application.conf

COPY target/scala-2.11/woken-validation.jar /opt/woken-validation/woken-validation.jar

RUN mkdir /opt/woken-validation/config

WORKDIR /opt/woken-validation

ENTRYPOINT java -jar -Dconfig.file=config/application.conf woken-validation.jar

