#!/bin/sh -e

JAVA="java"

if [ -n "$TRACE" ]; then
  apk add --update --no-cache strace
  JAVA="strace java"
fi

exec ${JAVA} ${JAVA_OPTS} -javaagent:/opt/woken/aspectjweaver.jar \
          -Djava.library.path=/lib \
          -Dconfig.file=/opt/woken/config/application.conf \
          -DLog4jContextSelector=org.apache.logging.log4j.core.async.AsyncLoggerContextSelector \
          -jar /opt/woken/woken.jar
