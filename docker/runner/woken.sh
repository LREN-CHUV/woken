#!/bin/sh -e

exec java -Dconfig.file=/opt/woken/config/application.conf \
          -DLog4jContextSelector=org.apache.logging.log4j.core.async.AsyncLoggerContextSelector \
          -jar /opt/woken/woken.jar
