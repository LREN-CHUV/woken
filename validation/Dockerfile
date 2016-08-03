FROM hbpmip/java-base:latest

MAINTAINER arnaud@ahead-solutions.ch

#ENV JAVA_CLASSPATH=./dist/scala-hadrian.jar
#ENV JAVA_MAINCLASS=eu.hbp.mip.validation.Main
#ENV JAVA_CONFIG=/dist/config/application.conf

COPY target/scala-2.11/woken-validation-assembly-githash.jar /root/woken-validation/woken-validation-assembly-githash.jar

RUN mkdir /root/woken-validation/config

WORKDIR /root/woken-validation

ENTRYPOINT java -jar -Dconfig.file=config/application.conf woken-validation-assembly-githash.jar